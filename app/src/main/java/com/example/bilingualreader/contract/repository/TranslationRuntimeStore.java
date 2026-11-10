package com.example.bilingualreader.contract.repository;

import com.example.bilingualreader.contract.model.*;
import androidx.annotation.Nullable;
import java.util.List;

/** Durable execution metadata, implemented by B's existing Room repository (no second cache). */
public interface TranslationRuntimeStore extends TranslationStore {
  final class Attempt {
    public final int count;
    public final long notBefore;
    public final String errorCode, message;
    public Attempt(int count, long notBefore, String errorCode, String message) {
      this.count = count; this.notBefore = notBefore;
      this.errorCode = errorCode; this.message = message;
    }
  }
  @Nullable TranslationTask getTask(String taskId) throws AppException;
  default TranslationTask prepareConfiguredTask(String chapterId, String source, String target,
      String mode, String endpoint) throws AppException {
    TranslationTask task = prepareTask(chapterId, source, target);
    bindService(task.getTaskId(), mode, endpoint);
    return task;
  }
  List<TranslationTask> getActiveTasks() throws AppException;
  List<TranslationTask> getBookTasks(String bookId) throws AppException;
  void bindService(String taskId, String mode, String endpoint) throws AppException;
  String[] getService(String taskId) throws AppException;
  List<Paragraph> getRunnableParagraphs(String taskId) throws AppException;
  Attempt getAttempt(String requestId) throws AppException;
  boolean claimAttempt(String taskId, String requestId, long now) throws AppException;
  void postponeAttempt(String taskId, String requestId, long notBefore,
      String errorCode, String message) throws AppException;
  void markTaskWaiting(String taskId, String code, String message) throws AppException;
}
