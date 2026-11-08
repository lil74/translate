package com.example.bilingualreader.debug;

import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.TranslationStore;
import com.example.bilingualreader.contract.translation.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Debug integration aid: real imported IDs + B's store + C's fake service. Not a production Worker.
 */
public final class StoreDemoCoordinator
    implements TranslationCoordinator, com.example.bilingualreader.di.TranslationDiagnostics {
  private final TranslationStore store;
  private final TranslationService service;
  private final ScheduledExecutorService serial = Executors.newSingleThreadScheduledExecutor();
  private final Set<String> running = new HashSet<>();
  private boolean failNext;

  public StoreDemoCoordinator(TranslationStore store, TranslationService service) {
    this.store = store;
    this.service = service;
  }

  public void failNextRequest() {
    serial.execute(() -> failNext = true);
  }

  private interface Command<T> {
    T run() throws AppException;
  }

  private <T> T call(Command<T> c) throws AppException {
    try {
      return serial.submit(() -> c.run()).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AppException(ErrorCode.UNKNOWN, "操作被中断", true, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AppException) throw (AppException) e.getCause();
      throw new AppException(ErrorCode.UNKNOWN, "模拟联调失败", true, e.getCause());
    }
  }

  public String startOrResumeChapter(String chapter, String src, String lang) throws AppException {
    return call(
        () -> {
          TranslationTask t = store.prepareTask(chapter, src, lang);
          if (t.getState() != TaskState.COMPLETED && running.add(t.getTaskId())) {
            store.markTaskRunning(t.getTaskId());
            schedule(t.getTaskId());
          }
          return t.getTaskId();
        });
  }

  private void schedule(String id) {
    serial.schedule(() -> step(id), 600, TimeUnit.MILLISECONDS);
  }

  private void step(String id) {
    try {
      if (!running.contains(id) || store.isTaskCancelled(id)) {
        running.remove(id);
        return;
      }
      List<Paragraph> ps = store.getPendingParagraphs(id);
      if (ps.isEmpty()) {
        store.finishTask(id);
        running.remove(id);
        return;
      }
      Paragraph p = ps.get(0);
      if (failNext) {
        failNext = false;
        store.markBatchFailed(
            id, Collections.singletonList(p.getParagraphId()), "NETWORK_ERROR", "模拟连接失败");
        store.failTask(id, "NETWORK_ERROR", "模拟连接失败，已完成译文保留，请重试");
        running.remove(id);
        return;
      }
      store.markBatchRunning(id, Collections.singletonList(p.getParagraphId()));
      TranslationBatchRequest request =
          new TranslationBatchRequest(
              id,
              UUID.randomUUID().toString(),
              "en",
              "zh-CN",
              Collections.singletonList(
                  new TranslationInput(
                      p.getParagraphId(), p.getSourceHash(), p.getOriginalText())));
      store.applyBatchResult(id, service.translate(request));
      schedule(id);
    } catch (AppException e) {
      running.remove(id);
      try {
        if (!store.isTaskCancelled(id)) store.failTask(id, e.getCode().name(), e.getUserMessage());
      } catch (AppException ignored) {
        /* cancelled or deleted */
      }
    }
  }

  public void cancelTask(String id) throws AppException {
    call(
        () -> {
          running.remove(id);
          store.cancelTask(id);
          return null;
        });
  }

  public LiveData<TranslationTask> observeTask(String id) {
    return store.observeTask(id);
  }

  public LiveData<TranslationTask> observeLatestTask(String id, String lang) {
    return store.observeLatestTask(id, lang);
  }
}
