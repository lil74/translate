package com.example.bilingualreader.di;

import com.example.bilingualreader.contract.repository.*;
import com.example.bilingualreader.contract.translation.TranslationCoordinator;
import com.example.bilingualreader.contract.translation.TranslationService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.example.bilingualreader.translation.TranslationConfigStore;

/** 唯一组装入口：B/C 接入时替换 Factory 中的实现，UI 不变。 */
public final class AppContainer {
  public final BookRepository books;
  public final ReaderRepository reader;
  public final SettingsRepository settings;
  public final TranslationCoordinator translation;
  public final boolean demo;
<<<<<<< Updated upstream
  public final TranslationService selectionTranslation;
  public final boolean selectionDemo;
=======
  public final TranslationRuntimeStore translationStore;
  public final TranslationConfigStore translationConfig;
>>>>>>> Stashed changes
  public final ExecutorService io = Executors.newSingleThreadExecutor();

  public AppContainer(
      BookRepository books,
      ReaderRepository reader,
      SettingsRepository settings,
      TranslationCoordinator translation,
<<<<<<< Updated upstream
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
=======
      boolean demo, TranslationRuntimeStore translationStore, TranslationConfigStore translationConfig) {
>>>>>>> Stashed changes
    this.books = books;
    this.reader = reader;
    this.settings = settings;
    this.translation = translation;
    this.demo = demo;
    this.translationStore = translationStore;
    this.translationConfig = translationConfig;
  }
}
