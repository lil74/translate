package com.example.bilingualreader.contract.translation;

import com.example.bilingualreader.contract.model.*;
import java.util.*;

public final class TranslationBatchResult {
  private final String taskId;
  private final String requestId;
  private final List<TranslationOutput> items;

  public TranslationBatchResult(String taskId, String requestId, List<TranslationOutput> items) {
    this.taskId = Objects.requireNonNull(taskId);
    this.requestId = Objects.requireNonNull(requestId);
    this.items = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(items)));
  }

  public String getTaskId() {
    return taskId;
  }

  public String getRequestId() {
    return requestId;
  }

  public List<TranslationOutput> getItems() {
    return items;
  }
}
