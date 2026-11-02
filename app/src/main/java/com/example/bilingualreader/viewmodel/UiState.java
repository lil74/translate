package com.example.bilingualreader.viewmodel;

import androidx.annotation.Nullable;

public final class UiState<T> {
  public enum Status {
    LOADING,
    SUCCESS,
    ERROR
  }

  public final Status status;
  @Nullable public final T data;
  @Nullable public final String message;

  private UiState(Status status, T data, String message) {
    this.status = status;
    this.data = data;
    this.message = message;
  }

  public static <T> UiState<T> loading() {
    return new UiState<>(Status.LOADING, null, null);
  }

  public static <T> UiState<T> success(T data) {
    return new UiState<>(Status.SUCCESS, data, null);
  }

  public static <T> UiState<T> error(String message) {
    return new UiState<>(Status.ERROR, null, message);
  }
}
