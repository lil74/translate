package com.example.bilingualreader.contract.model;

import java.util.*;

public final class Chapter {
  private final String chapterId;
  private final String bookId;
  private final String title;
  private final int orderIndex;
  private final String resourcePath;
  private final int paragraphCount;

  public Chapter(
      String chapterId,
      String bookId,
      String title,
      int orderIndex,
      String resourcePath,
      int paragraphCount) {
    this.chapterId = Objects.requireNonNull(chapterId);
    this.bookId = Objects.requireNonNull(bookId);
    this.title = Objects.requireNonNull(title);
    this.orderIndex = orderIndex;
    this.resourcePath = Objects.requireNonNull(resourcePath);
    this.paragraphCount = paragraphCount;
  }

  public String getChapterId() {
    return chapterId;
  }

  public String getBookId() {
    return bookId;
  }

  public String getTitle() {
    return title;
  }

  public int getOrderIndex() {
    return orderIndex;
  }

  public String getResourcePath() {
    return resourcePath;
  }

  public int getParagraphCount() {
    return paragraphCount;
  }
}
