package com.example.bilingualreader.di;

import com.example.bilingualreader.contract.repository.*;
import com.example.bilingualreader.contract.translation.TranslationCoordinator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 唯一组装入口：B/C 接入时替换 Factory 中的实现，UI 不变。 */
public final class AppContainer {
  public final BookRepository books;
  public final ReaderRepository reader;
  public final SettingsRepository settings;
  public final TranslationCoordinator translation;
  public final boolean demo;
  public final ExecutorService io = Executors.newSingleThreadExecutor();

  public AppContainer(
      BookRepository books,
      ReaderRepository reader,
      SettingsRepository settings,
      TranslationCoordinator translation,
      boolean demo) {
    this.books = books;
    this.reader = reader;
    this.settings = settings;
    this.translation = translation;
    this.demo = demo;
  }
}
