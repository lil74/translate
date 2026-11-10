package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;

/** Provider waiting hint, persisted by the runner instead of sleeping in a Worker. */
public final class RetryAfterException extends AppException {
  public final long notBefore;
  public RetryAfterException(ErrorCode code, String message, long notBefore) {
    super(code, message, true); this.notBefore = notBefore;
  }
}
