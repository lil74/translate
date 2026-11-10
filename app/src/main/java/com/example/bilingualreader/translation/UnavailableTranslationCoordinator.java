package com.example.bilingualreader.translation;

import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.TranslationStore;
import com.example.bilingualreader.contract.translation.TranslationCoordinator;

/** Production scheduling belongs to C; release can already read saved results. */
public final class UnavailableTranslationCoordinator implements TranslationCoordinator {
  private final TranslationStore store;

  public UnavailableTranslationCoordinator(TranslationStore store) {
    this.store = store;
  }

  public String startOrResumeChapter(String id, String src, String lang) throws AppException {
    throw new AppException(ErrorCode.TRANSLATION_FAILED, "在线翻译尚未配置，请由团队接入正式翻译调度服务", false);
  }

  public LiveData<TranslationTask> observeTask(String id) {
    return store.observeTask(id);
  }

  public LiveData<TranslationTask> observeLatestTask(String id, String lang) {
    return store.observeLatestTask(id, lang);
  }

  public void cancelTask(String id) throws AppException {
    store.cancelTask(id);
  }
}
