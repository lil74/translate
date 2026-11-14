package com.example.bilingualreader.viewmodel;

import android.os.*;
import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.translation.LookupQuery;
import java.util.concurrent.*;

/** One retained screen, explicit requests only. Each request captures its own provider binding. */
public final class LookupViewModel extends ViewModel {
  public final MutableLiveData<UiState<String>> result = new MutableLiveData<>();
  public String input = "";
  public int direction = LookupQuery.AUTO;
  public Snapshot submitted;
  private final AppContainer graph;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Future<?> pending;
  private long generation;
  private boolean cleared;

  public LookupViewModel(AppContainer graph) {
    this.graph = graph;
  }

  public static final class Snapshot {
    public final TranslationBatchRequest request;
    public final AppContainer.SelectionBinding binding;
    public final String endpoint;
    private final long generation;

    private Snapshot(TranslationBatchRequest request, AppContainer.SelectionBinding binding, String endpoint, long generation) {
      this.request = request;
      this.binding = binding;
      this.endpoint = endpoint;
      this.generation = generation;
    }

    public String label() {
      return "en".equals(request.getSourceLanguage()) ? "英文 → 中文" : "中文 → 英文";
    }
  }

  public Snapshot prepare() throws AppException {
    return new Snapshot(LookupQuery.request(input, direction), graph.selectionBinding(),
        graph.translationConfig == null ? "当前配置的在线翻译服务" : graph.translationConfig.endpoint(), generation);
  }

  public void invalidate() {
    cancel();
    submitted = null;
    result.setValue(null);
  }

  public boolean isCurrent(Snapshot snapshot) {
    return !cleared && snapshot.generation == generation
        && snapshot.request.getItems().get(0).getText().equals(input);
  }

  public void translate(Snapshot snapshot) {
    if (!isCurrent(snapshot)) return;
    cancel();
    final long token = generation;
    submitted = snapshot;
    result.setValue(UiState.loading());
    pending =
        executor.submit(
            () -> {
              UiState<String> next;
              try {
                next =
                    UiState.success(
                        LookupQuery.translate(snapshot.binding.service, snapshot.request));
              } catch (AppException e) {
                next =
                    UiState.error(
                        e instanceof com.example.bilingualreader.translation.RetryAfterException
                            ? "翻译服务繁忙，请稍后点击重试"
                            : e.getUserMessage());
              } catch (Exception e) {
                next = UiState.error("暂时无法查询，请稍后重试");
              }
              UiState<String> delivered = next;
              main.post(
                  () -> {
                    if (!cleared && token == generation) result.setValue(delivered);
                  });
            });
  }

  private void cancel() {
    generation++;
    if (pending != null) {
      pending.cancel(true);
      pending = null;
    }
  }

  public void stop() {
    cancel();
    if (result.getValue() != null && result.getValue().status == UiState.Status.LOADING)
      result.setValue(UiState.error("查询已取消，可重新查询"));
  }

  @Override
  protected void onCleared() {
    cleared = true;
    cancel();
    executor.shutdownNow();
  }
}
