package com.example.bilingualreader.di;

import com.example.bilingualreader.contract.repository.*;
import com.example.bilingualreader.contract.translation.TranslationCoordinator;
import com.example.bilingualreader.contract.translation.TranslationService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 唯一组装入口：B/C 接入时替换 Factory 中的实现，UI 不变。 */
public final class AppContainer {
  public final BookRepository books;
  public final ReaderRepository reader;
  public final SettingsRepository settings;
  public final TranslationCoordinator translation;
  public final boolean demo;
  public final TranslationService selectionTranslation;
  public final boolean selectionDemo;
  public final ExecutorService io = Executors.newSingleThreadExecutor();

  public AppContainer(
      BookRepository books,
      ReaderRepository reader,
      SettingsRepository settings,
      TranslationCoordinator translation,
      boolean demo) {
    this(
        books,
        reader,
        settings,
        translation,
        demo,
        new com.example.bilingualreader.translation.UnconfiguredSelectionService(),
        false);
  }

  public AppContainer(
      BookRepository books,
      ReaderRepository reader,
      SettingsRepository settings,
      TranslationCoordinator translation,
      boolean demo,
      TranslationService selectionTranslation,
      boolean selectionDemo) {
    this.selectionTranslation = java.util.Objects.requireNonNull(selectionTranslation);
    this.selectionDemo = selectionDemo;
    this.books = books;
    this.reader = reader;
    this.settings = settings;
    this.translation = translation;
    this.demo = demo;
  }
}
