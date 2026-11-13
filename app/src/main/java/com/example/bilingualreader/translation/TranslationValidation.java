package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Phase-one boundary: validate before any result is passed to TranslationStore. */
public final class TranslationValidation {
  private TranslationValidation() {}

  static boolean isBlank(String value) {
    return value.codePoints().allMatch(Character::isWhitespace);
  }

  public static String sha256(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(64);
      for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  public static void validateRequest(TranslationBatchRequest request) throws AppException {
    if (request == null) throw invalid("翻译请求为空");
    boolean forward = "en".equals(request.getSourceLanguage())
        && "zh-CN".equals(request.getTargetLanguage());
    boolean reverse = "zh-CN".equals(request.getSourceLanguage())
        && "en".equals(request.getTargetLanguage());
    if (!forward && !reverse) {
      throw new AppException(ErrorCode.UNSUPPORTED_LANGUAGE, "当前仅支持英语与简体中文互译", false);
    }
    if (isBlank(request.getTaskId()) || isBlank(request.getRequestId())
        || request.getItems().isEmpty() || request.getItems().size() > 8) {
      throw invalid("批次需包含 1–8 个段落和有效请求标识");
    }
    Set<String> ids = new HashSet<>();
    long count = 0;
    for (TranslationInput item : request.getItems()) {
      if (item == null || isBlank(item.getParagraphId())
          || !ids.add(item.getParagraphId()) || isBlank(item.getText())
          || !sha256(item.getText()).equals(item.getSourceHash())) {
        throw invalid("原文、段落标识或原文哈希无效");
      }
      count += item.getText().codePointCount(0, item.getText().length());
    }
    if (count > 4000) throw invalid("第一阶段批次最多 4000 个 Unicode 码点，请先拆分长段落");
  }

  /** Reject ambiguous results; missing entries become explicit failures, ordered by input ID. */
  public static TranslationBatchResult validateResult(
      TranslationBatchRequest request, TranslationBatchResult result) throws AppException {
    validateRequest(request);
    if (result == null || !request.getTaskId().equals(result.getTaskId())
        || !request.getRequestId().equals(result.getRequestId())) {
      throw invalid("翻译响应与请求标识不一致");
    }
    Map<String, TranslationInput> inputs = new HashMap<>();
    for (TranslationInput item : request.getItems()) inputs.put(item.getParagraphId(), item);
    Map<String, TranslationOutput> outputs = new HashMap<>();
    for (TranslationOutput output : result.getItems()) {
      if (output == null) throw invalid("翻译响应包含空条目");
      TranslationInput input = inputs.get(output.getParagraphId());
      if (input == null || outputs.put(output.getParagraphId(), output) != null
          || !input.getSourceHash().equals(output.getSourceHash())
          || isBlank(output.getProvider())
          || (output.getStatus() != TranslationStatus.SUCCESS
              && output.getStatus() != TranslationStatus.FAILED)
          || (output.getStatus() == TranslationStatus.SUCCESS
              && (output.getTranslatedText() == null || isBlank(output.getTranslatedText())))
          || (output.getStatus() == TranslationStatus.FAILED
              && (output.getErrorCode() == null || isBlank(output.getErrorCode())))) {
        throw invalid("翻译响应有重复、未知、错配或无效段落");
      }
    }
    List<TranslationOutput> ordered = new ArrayList<>();
    for (TranslationInput input : request.getItems()) {
      TranslationOutput output = outputs.get(input.getParagraphId());
      ordered.add(output != null ? output : new TranslationOutput(input.getParagraphId(),
          input.getSourceHash(), TranslationStatus.FAILED, null, "validation", null,
          ErrorCode.INVALID_RESPONSE.name()));
    }
    return new TranslationBatchResult(request.getTaskId(), request.getRequestId(), ordered);
  }

  private static AppException invalid(String message) {
    return new AppException(ErrorCode.INVALID_RESPONSE, message, false);
  }
}
