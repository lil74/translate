package com.example.bilingualreader.contract.repository;

import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import java.util.List;

/** observe 方法可从主线程调用；其他方法只在后台线程调用。详见 docs/PROJECT_SPEC.md。 */
public interface TranslationStore {
  TranslationTask prepareTask(String chapterId, String sourceLanguage, String targetLanguage)
      throws AppException;

  List<Paragraph> getPendingParagraphs(String taskId) throws AppException;

  void markTaskRunning(String taskId) throws AppException;

  void markBatchRunning(String taskId, List<String> paragraphIds) throws AppException;

  void applyBatchResult(String taskId, TranslationBatchResult result) throws AppException;

  void markBatchFailed(String taskId, List<String> paragraphIds, String errorCode, String message)
      throws AppException;

  void finishTask(String taskId) throws AppException;

  void failTask(String taskId, String errorCode, String message) throws AppException;

  void cancelTask(String taskId) throws AppException;

  boolean isTaskCancelled(String taskId) throws AppException;

  LiveData<TranslationTask> observeTask(String taskId);

  LiveData<TranslationTask> observeLatestTask(String chapterId, String targetLanguage);
}
