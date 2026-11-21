package com.example.bilingualreader.viewmodel;

import android.content.Context;
import android.os.*;
import androidx.lifecycle.*;
import com.example.bilingualreader.video.*;
import java.util.*;

public final class VideoLibraryViewModel extends ViewModel {
  public final MutableLiveData<UiState<List<VideoLibrary.Entry>>> items =
      new MutableLiveData<>(UiState.loading());
  public final MutableLiveData<String> notice = new MutableLiveData<>();
  private final VideoProjectStore store;
  private final Handler main = new Handler(Looper.getMainLooper());
  private long generation;
  private boolean cleared;

  public VideoLibraryViewModel(Context context) {
    store = new VideoProjectStore(context.getApplicationContext());
  }

  public void refresh() {
    run(null);
  }

  public void add(String uri, String name) {
    add(uri, name, null);
  }

  public void add(String uri, String name, String customName) {
    run(
        () -> {
          boolean exists = store.list().stream().anyMatch(e -> e.uri.equals(uri));
          store.add(uri, name);
          if (customName != null && !customName.trim().isEmpty()) store.rename(uri, customName);
          return exists ? "视频已在库中" : "视频已导入";
        });
  }

  public void delete(String uri) {
    run(
        () -> {
          store.delete(uri);
          return "已移除视频";
        });
  }

  private interface Action {
    String execute() throws java.io.IOException;
  }

  private void run(Action action) {
    long token = ++generation;
    items.setValue(UiState.loading());
    VideoProjectStore.IO.execute(
        () -> {
          UiState<List<VideoLibrary.Entry>> result;
          String message = null;
          try {
            if (action != null) message = action.execute();
            result = UiState.success(store.list());
          } catch (Exception error) {
            result = UiState.error(error.getMessage() == null ? "视频库读取失败，请重试" : error.getMessage());
          }
          UiState<List<VideoLibrary.Entry>> next = result;
          String text = message;
          main.post(
              () -> {
                if (!cleared && generation == token) {
                  items.setValue(next);
                  if (text != null) notice.setValue(text);
                }
              });
        });
  }

  @Override
  protected void onCleared() {
    cleared = true;
    generation++;
  }
}
