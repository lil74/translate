package com.example.bilingualreader.contract.model;

import java.util.*;

public final class ImportResult {
  private final Book book;
  private final boolean alreadyExists;
  private final List<String> warnings;

  public ImportResult(Book book, boolean alreadyExists, List<String> warnings) {
    this.book = Objects.requireNonNull(book);
    this.alreadyExists = alreadyExists;
    this.warnings = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(warnings)));
  }

  public Book getBook() {
    return book;
  }

  public boolean isAlreadyExists() {
    return alreadyExists;
  }

  public List<String> getWarnings() {
    return warnings;
  }
}
