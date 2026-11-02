package com.example.bilingualreader.contract.model;

import androidx.annotation.Nullable;
import java.util.*;

public final class TranslationTask {
  private final String taskId;
  private final String chapterId;
  private final String sourceLanguage;
  private final String targetLanguage;
  private final TaskState state;
  private final int totalCount;
  private final int successCount;
  private final int failedCount;
  private final long createdAt;
  private final long updatedAt;
  private final @Nullable String errorCode;
  private final @Nullable String errorMessage;

  public TranslationTask(
      String taskId,
      String chapterId,
      String sourceLanguage,
      String targetLanguage,
      TaskState state,
      int totalCount,
      int successCount,
      int failedCount,
      long createdAt,
      long updatedAt,
      @Nullable String errorCode,
      @Nullable String errorMessage) {
    this.taskId = Objects.requireNonNull(taskId);
    this.chapterId = Objects.requireNonNull(chapterId);
    this.sourceLanguage = Objects.requireNonNull(sourceLanguage);
    this.targetLanguage = Objects.requireNonNull(targetLanguage);
    this.state = Objects.requireNonNull(state);
    this.totalCount = totalCount;
    this.successCount = successCount;
    this.failedCount = failedCount;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getChapterId() {
    return chapterId;
  }

  public String getSourceLanguage() {
    return sourceLanguage;
  }

  public String getTargetLanguage() {
    return targetLanguage;
  }

  public TaskState getState() {
    return state;
  }

  public int getTotalCount() {
    return totalCount;
  }

  public int getSuccessCount() {
    return successCount;
  }

  public int getFailedCount() {
    return failedCount;
  }

  public long getCreatedAt() {
    return createdAt;
  }

  public long getUpdatedAt() {
    return updatedAt;
  }

  public @Nullable String getErrorCode() {
    return errorCode;
  }

  public @Nullable String getErrorMessage() {
    return errorMessage;
  }
}
