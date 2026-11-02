package com.example.bilingualreader.contract.translation;

import com.example.bilingualreader.contract.model.*;
import java.util.*;

public final class TranslationBatchRequest {
  private final String taskId;
  private final String requestId;
  private final String sourceLanguage;
  private final String targetLanguage;
  private final List<TranslationInput> items;

  public TranslationBatchRequest(
      String taskId,
      String requestId,
      String sourceLanguage,
      String targetLanguage,
      List<TranslationInput> items) {
    this.taskId = Objects.requireNonNull(taskId);
    this.requestId = Objects.requireNonNull(requestId);
    this.sourceLanguage = Objects.requireNonNull(sourceLanguage);
    this.targetLanguage = Objects.requireNonNull(targetLanguage);
    this.items = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(items)));
  }

  public String getTaskId() {
    return taskId;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getSourceLanguage() {
    return sourceLanguage;
  }

  public String getTargetLanguage() {
    return targetLanguage;
  }

  public List<TranslationInput> getItems() {
    return items;
  }
}
