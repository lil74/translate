package com.example.bilingualreader.contract.model;

import androidx.annotation.Nullable;
import java.util.*;

public final class TocEntry {
  private final String tocId;
  private final String bookId;
  private final @Nullable String parentTocId;
  private final int orderIndex;
  private final String title;
  private final String chapterId;
  private final @Nullable String paragraphId;

  public TocEntry(
      String tocId,
      String bookId,
      @Nullable String parentTocId,
      int orderIndex,
      String title,
      String chapterId,
      @Nullable String paragraphId) {
    this.tocId = Objects.requireNonNull(tocId);
    this.bookId = Objects.requireNonNull(bookId);
    this.parentTocId = parentTocId;
    this.orderIndex = orderIndex;
    this.title = Objects.requireNonNull(title);
    this.chapterId = Objects.requireNonNull(chapterId);
    this.paragraphId = paragraphId;
  }

  public String getTocId() {
    return tocId;
  }

  public String getBookId() {
    return bookId;
  }

  public @Nullable String getParentTocId() {
    return parentTocId;
  }

  public int getOrderIndex() {
    return orderIndex;
  }

  public String getTitle() {
    return title;
  }

  public String getChapterId() {
    return chapterId;
  }

  public @Nullable String getParagraphId() {
    return paragraphId;
  }
}
