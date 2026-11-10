package com.example.bilingualreader.contract.model;

public class AppException extends Exception {
  private final ErrorCode code;
  private final String userMessage;
  private final boolean retryable;

  public AppException(ErrorCode code, String userMessage, boolean retryable) {
    this(code, userMessage, retryable, null);
  }

  public AppException(ErrorCode code, String userMessage, boolean retryable, Throwable cause) {
    super(userMessage, cause);
    this.code = code;
    this.userMessage = userMessage;
    this.retryable = retryable;
  }

  public ErrorCode getCode() {
    return code;
  }

  public String getUserMessage() {
    return userMessage;
  }

  public boolean isRetryable() {
    return retryable;
  }
}
