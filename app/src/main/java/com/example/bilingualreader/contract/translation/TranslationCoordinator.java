package com.example.bilingualreader.contract.translation;

import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;

/** observe 方法可从主线程调用；其他方法只在后台线程调用。详见 docs/PROJECT_SPEC.md。 */
public interface TranslationCoordinator {
  String startOrResumeChapter(String chapterId, String sourceLanguage, String targetLanguage)
      throws AppException;

  LiveData<TranslationTask> observeTask(String taskId);

  LiveData<TranslationTask> observeLatestTask(String chapterId, String targetLanguage);

  void cancelTask(String taskId) throws AppException;
}
