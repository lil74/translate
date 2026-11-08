package com.example.bilingualreader.viewmodel;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.translation.TranslationValidation;
import java.util.*;
import java.util.concurrent.*;

/** Transient lookup: no Chapter Coordinator, Worker, TranslationStore or DAO access. */
public final class SelectionTranslationViewModel extends ViewModel {
  public final MutableLiveData<UiState<String>> result = new MutableLiveData<>();
  private final TranslationService service;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Future<?> pending;
  private long generation;
  private boolean cleared;

  public SelectionTranslationViewModel(TranslationService service) {
    this.service = service;
  }

  public void translate(String selected) {
    cancel();
    long requestGeneration = generation;
    result.setValue(UiState.loading());
    if (selected == null || selected.codePoints().allMatch(Character::isWhitespace)) {
      result.setValue(UiState.error("请先选择原文中的单词或句子"));
      return;
    }
    if (selected.codePointCount(0, selected.length()) > 4000) {
      result.setValue(UiState.error("所选内容超过 4000 个字符，请缩小选择范围"));
      return;
    }
    pending =
        executor.submit(
            () -> {
              UiState<String> next;
              try {
                String id = "selection:" + UUID.randomUUID();
                TranslationBatchRequest request =
                    new TranslationBatchRequest(
                        id,
                        UUID.randomUUID().toString(),
                        "en",
                        "zh-CN",
                        Collections.singletonList(
                            new TranslationInput(
                                id, TranslationValidation.sha256(selected), selected)));
                TranslationBatchResult response =
                    TranslationValidation.validateResult(request, service.translate(request));
                TranslationOutput output = response.getItems().get(0);
                if (output.getStatus() != TranslationStatus.SUCCESS)
                  throw new AppException(ErrorCode.TRANSLATION_FAILED, "所选内容翻译失败，请重试", true);
                next = UiState.success(output.getTranslatedText());
              } catch (AppException e) {
                next = UiState.error(e.getUserMessage());
              } catch (Exception e) {
                next = UiState.error("暂时无法翻译，请稍后重试");
              }
              UiState<String> delivered = next;
              main.post(
                  () -> {
                    if (!cleared && generation == requestGeneration) result.setValue(delivered);
                  });
            });
  }

  public void cancel() {
    generation++;
    if (pending != null) {
      pending.cancel(true);
      pending = null;
    }
  }

  @Override
  protected void onCleared() {
    cleared = true;
    cancel();
    executor.shutdownNow();
    super.onCleared();
  }
}
