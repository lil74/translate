package com.example.bilingualreader.contract.model;

import java.util.*;

public final class ReadingProgress {
  private final String bookId;
  private final String chapterId;
  private final String paragraphId;
  private final int offsetPx;
  private final long updatedAt;

  public ReadingProgress(
      String bookId, String chapterId, String paragraphId, int offsetPx, long updatedAt) {
    this.bookId = Objects.requireNonNull(bookId);
    this.chapterId = Objects.requireNonNull(chapterId);
    this.paragraphId = Objects.requireNonNull(paragraphId);
    this.offsetPx = offsetPx;
    this.updatedAt = updatedAt;
  }

  public String getBookId() {
    return bookId;
  }

  public String getChapterId() {
    return chapterId;
  }

  public String getParagraphId() {
    return paragraphId;
  }

  public int getOffsetPx() {
    return offsetPx;
  }

  public long getUpdatedAt() {
    return updatedAt;
  }
}
