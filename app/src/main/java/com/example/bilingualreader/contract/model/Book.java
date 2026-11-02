package com.example.bilingualreader.contract.model;

import androidx.annotation.Nullable;
import java.util.*;

public final class Book {
  private final String bookId;
  private final String title;
  private final String author;
  private final String language;
  private final String originalFileName;
  private final String localFilePath;
  private final @Nullable String coverPath;
  private final int chapterCount;
  private final int parserVersion;
  private final long importedAt;
  private final @Nullable Long lastReadAt;

  public Book(
      String bookId,
      String title,
      String author,
      String language,
      String originalFileName,
      String localFilePath,
      @Nullable String coverPath,
      int chapterCount,
      int parserVersion,
      long importedAt,
      @Nullable Long lastReadAt) {
    this.bookId = Objects.requireNonNull(bookId);
    this.title = Objects.requireNonNull(title);
    this.author = Objects.requireNonNull(author);
    this.language = Objects.requireNonNull(language);
    this.originalFileName = Objects.requireNonNull(originalFileName);
    this.localFilePath = Objects.requireNonNull(localFilePath);
    this.coverPath = coverPath;
    this.chapterCount = chapterCount;
    this.parserVersion = parserVersion;
    this.importedAt = importedAt;
    this.lastReadAt = lastReadAt;
  }

  public String getBookId() {
    return bookId;
  }

  public String getTitle() {
    return title;
  }

  public String getAuthor() {
    return author;
  }

  public String getLanguage() {
    return language;
  }

  public String getOriginalFileName() {
    return originalFileName;
  }

  public String getLocalFilePath() {
    return localFilePath;
  }

  public @Nullable String getCoverPath() {
    return coverPath;
  }

  public int getChapterCount() {
    return chapterCount;
  }

  public int getParserVersion() {
    return parserVersion;
  }

  public long getImportedAt() {
    return importedAt;
  }

  public @Nullable Long getLastReadAt() {
    return lastReadAt;
  }
}
