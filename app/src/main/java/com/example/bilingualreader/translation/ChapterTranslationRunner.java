package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.TranslationRuntimeStore;
import com.example.bilingualreader.contract.translation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Android-independent execution core. All progress/attempt writes use the existing Room store. */
public final class ChapterTranslationRunner {
  public enum Outcome { DONE, RETRY }
  private final TranslationRuntimeStore store;
  private final TranslationService service;
  private final BooleanSupplier stopped;
  private final LongSupplier clock;
  private String taskId;
  private TranslationTask task;
  private long deadline;

  public ChapterTranslationRunner(TranslationRuntimeStore store, TranslationService service,
      BooleanSupplier stopped, LongSupplier clock) {
    this.store = store; this.service = service; this.stopped = stopped; this.clock = clock;
  }
  public Outcome run(String id) throws AppException {
    taskId = id; task = store.getTask(id);
    if (!active()) return Outcome.DONE;
    store.markTaskRunning(id);
    boolean waiting = false;
    deadline = clock.getAsLong() + 7 * 60_000L;
    for (List<Paragraph> batch : TranslationBatcher.batches(store.getRunnableParagraphs(id))) {
      if (!active()) return Outcome.DONE;
      if (clock.getAsLong() >= deadline) { waiting = true; break; }
      List<String> ids = new ArrayList<>();
      for (Paragraph p : batch) ids.add(p.getParagraphId());
      store.markBatchRunning(id, ids);
      if (batch.size() == 1 && points(batch.get(0).getOriginalText()) > TranslationBatcher.MAX_POINTS) {
        waiting |= translateLong(batch.get(0));
      } else {
        List<TranslationInput> inputs = new ArrayList<>();
        for (Paragraph p : batch) inputs.add(new TranslationInput(
            p.getParagraphId(), p.getSourceHash(), p.getOriginalText()));
        Response response = send(inputs);
        if (!active()) return Outcome.DONE;
        if (!response.items.isEmpty()) store.applyBatchResult(id,
            new TranslationBatchResult(id, requestId(inputs), response.items));
        if (response.fatal != null) throw response.fatal;
        waiting |= response.waiting;
      }
    }
    if (!active()) return Outcome.DONE;
    if (waiting) {
      store.markTaskWaiting(id, ErrorCode.NETWORK_ERROR.name(), "等待网络或退避后继续，已完成译文已保存");
      return Outcome.RETRY;
    }
    store.finishTask(id);
    return Outcome.DONE;
  }
  private boolean translateLong(Paragraph paragraph) throws AppException {
    List<String> parts = TranslationBatcher.chunks(paragraph.getOriginalText());
    StringBuilder translated = new StringBuilder();
    String provider = "", version = null;
    for (int i = 0; i < parts.size(); i++) {
      if (!active()) return false;
      if (clock.getAsLong() >= deadline) return true;
      String chunk = parts.get(i);
      TranslationInput input = new TranslationInput(paragraph.getParagraphId() + "#chunk-" + i,
          TranslationValidation.sha256(chunk), chunk);
      Response response = send(Collections.singletonList(input));
      if (!active()) return false;
      if (response.waiting) return true;
      if (response.fatal != null) {
        store.markBatchFailed(taskId, Collections.singletonList(paragraph.getParagraphId()),
            response.fatal.getCode().name(), response.fatal.getUserMessage());
        throw response.fatal;
      }
      TranslationOutput output = response.items.get(0);
      if (output.getStatus() != TranslationStatus.SUCCESS) {
        store.markBatchFailed(taskId, Collections.singletonList(paragraph.getParagraphId()),
            output.getErrorCode(), "长段落的一个分块未完成，请重试");
        return false;
      }
      if (translated.length() > 0) translated.append('\n');
      translated.append(output.getTranslatedText());
      provider = output.getProvider(); version = output.getEngineVersion();
    }
    TranslationOutput merged = new TranslationOutput(paragraph.getParagraphId(), paragraph.getSourceHash(),
        TranslationStatus.SUCCESS, translated.toString(), provider, version, null);
    store.applyBatchResult(taskId, new TranslationBatchResult(taskId,
        requestId(Collections.singletonList(new TranslationInput(paragraph.getParagraphId(),
            paragraph.getSourceHash(), paragraph.getOriginalText()))), Collections.singletonList(merged)));
    return false;
  }
  private static final class Response {
    final List<TranslationOutput> items = new ArrayList<>(); boolean waiting;
    AppException fatal;
  }
  private Response send(List<TranslationInput> inputs) throws AppException {
    Response response = new Response();
    List<TranslationInput> ready = new ArrayList<>();
    for (TranslationInput input : inputs) {
      TranslationRuntimeStore.Attempt attempt = store.getAttempt(attemptId(input));
      if (attempt.count >= 3) {
        response.items.add(failure(input, attempt.errorCode == null
            ? ErrorCode.TRANSLATION_FAILED.name() : attempt.errorCode));
      } else if (attempt.notBefore > clock.getAsLong()) response.waiting = true;
      else if (store.claimAttempt(taskId, attemptId(input), clock.getAsLong())) ready.add(input);
      else response.waiting = true;
    }
    if (ready.isEmpty() || !active()) return response;
    TranslationBatchRequest request = new TranslationBatchRequest(taskId, requestId(ready),
        task.getSourceLanguage(), task.getTargetLanguage(), ready);
    TranslationBatchResult result;
    try {
      result = TranslationValidation.validateResult(request, service.translate(request));
    } catch (AppException error) {
      if (!active()) return response;
      if (fatal(error.getCode())) throw error;
      for (TranslationInput input : ready) {
        if (retryable(error.getCode()) && error.isRetryable()) {
          waitOrFail(input, error.getCode().name(), error.getUserMessage(),
              error instanceof RetryAfterException ? ((RetryAfterException) error).notBefore : 0, response);
        } else response.items.add(failure(input, error.getCode().name()));
      }
      return response;
    }
    if (!active()) return response;
    Map<String, TranslationInput> byId = new HashMap<>();
    for (TranslationInput input : ready) byId.put(input.getParagraphId(), input);
    for (TranslationOutput output : result.getItems()) {
      if (output.getStatus() == TranslationStatus.FAILED && retryableCode(output.getErrorCode())) {
        waitOrFail(byId.get(output.getParagraphId()), output.getErrorCode(), "部分段落请求暂时失败", 0, response);
      } else if (output.getStatus() == TranslationStatus.FAILED && fatalCode(output.getErrorCode())) {
        response.items.add(output);
        response.fatal = new AppException(ErrorCode.valueOf(output.getErrorCode()), "翻译服务认证或额度异常", false);
      } else response.items.add(output);
    }
    return response;
  }
  private void waitOrFail(TranslationInput input, String code, String message, long providerWait,
      Response response) throws AppException {
    TranslationRuntimeStore.Attempt attempt = store.getAttempt(attemptId(input));
    long until = Math.max(providerWait, clock.getAsLong() + 30_000L * (1L << (attempt.count - 1)));
    store.postponeAttempt(taskId, attemptId(input), until, code, message);
    if (attempt.count >= 3) response.items.add(failure(input, code));
    else response.waiting = true;
  }
  private boolean active() throws AppException {
    if (stopped.getAsBoolean()) return false;
    TranslationTask current = store.getTask(taskId);
    return current != null && (current.getState() == TaskState.QUEUED || current.getState() == TaskState.RUNNING);
  }
  private String attemptId(TranslationInput input) {
    return TranslationValidation.sha256(taskId + "|" + input.getParagraphId() + "|" + input.getSourceHash());
  }
  private String requestId(List<TranslationInput> inputs) {
    StringBuilder key = new StringBuilder(taskId);
    for (TranslationInput input : inputs) key.append('|').append(input.getParagraphId()).append('|').append(input.getSourceHash());
    return UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8)).toString();
  }
  private static TranslationOutput failure(TranslationInput input, String code) {
    return new TranslationOutput(input.getParagraphId(), input.getSourceHash(), TranslationStatus.FAILED,
        null, "execution", null, code);
  }
  private static int points(String text) { return text.codePointCount(0, text.length()); }
  private static boolean fatal(ErrorCode code) {
    return code == ErrorCode.AUTH_FAILED || code == ErrorCode.QUOTA_EXCEEDED || code == ErrorCode.UNSUPPORTED_LANGUAGE;
  }
  private static boolean retryable(ErrorCode code) {
    return code == ErrorCode.NETWORK_ERROR || code == ErrorCode.TIMEOUT || code == ErrorCode.RATE_LIMITED;
  }
  private static boolean retryableCode(String code) {
    try { return retryable(ErrorCode.valueOf(code)); } catch (Exception ignored) { return false; }
  }
  private static boolean fatalCode(String code) {
    try { return fatal(ErrorCode.valueOf(code)); } catch (Exception ignored) { return false; }
  }
}
