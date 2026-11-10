package com.example.bilingualreader.di;

import androidx.annotation.Nullable;
import com.example.bilingualreader.contract.repository.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.translation.*;
import java.util.Objects;
import java.util.concurrent.*;

/** Combines A's transient selection service with C's durable chapter execution. */
public final class AppContainer {
  public final BookRepository books;
  public final ReaderRepository reader;
  public final SettingsRepository settings;
  public final TranslationCoordinator translation;
  public final boolean demo;
  public volatile TranslationService selectionTranslation;
  public volatile boolean selectionDemo;
  public final @Nullable TranslationRuntimeStore translationStore;
  public final @Nullable TranslationConfigStore translationConfig;
  public final ExecutorService io = Executors.newSingleThreadExecutor();
  private volatile SelectionBinding selectionBinding;

  /** A per-sheet snapshot keeps provider choice and privacy indication together. */
  public static final class SelectionBinding {
    public final TranslationService service;
    public final boolean demo;
    private SelectionBinding(TranslationService service, boolean demo) {
      this.service = Objects.requireNonNull(service); this.demo = demo;
    }
  }
  public SelectionBinding selectionBinding() { return selectionBinding; }
  public synchronized void setSelectionTranslation(TranslationService service, boolean demo) {
    SelectionBinding binding = new SelectionBinding(service, demo);
    selectionTranslation = binding.service; selectionDemo = binding.demo; selectionBinding = binding;
  }
  public AppContainer(BookRepository books, ReaderRepository reader, SettingsRepository settings,
      TranslationCoordinator coordinator, boolean demo) {
    this(books, reader, settings, coordinator, demo, new UnconfiguredSelectionService(), false);
  }
  /** Documented A constructor; selectionDemo is independent of chapter demo. */
  public AppContainer(BookRepository books, ReaderRepository reader, SettingsRepository settings,
      TranslationCoordinator coordinator, boolean demo, TranslationService selection, boolean selectionDemo) {
    this(books, reader, settings, coordinator, demo, selection, selectionDemo,
        reader instanceof TranslationRuntimeStore ? (TranslationRuntimeStore) reader : null, null);
  }
  /** Compatibility with the preceding C integration. Does not silently reuse chapter translation. */
  public AppContainer(BookRepository books, ReaderRepository reader, SettingsRepository settings,
      TranslationCoordinator coordinator, boolean demo, TranslationRuntimeStore store, TranslationConfigStore config) {
    this(books, reader, settings, coordinator, demo, new UnconfiguredSelectionService(), false, store, config);
  }
  public AppContainer(BookRepository books, ReaderRepository reader, SettingsRepository settings,
      TranslationCoordinator coordinator, boolean demo, TranslationService selection, boolean selectionDemo,
      @Nullable TranslationRuntimeStore store, @Nullable TranslationConfigStore config) {
    this.books = Objects.requireNonNull(books); this.reader = Objects.requireNonNull(reader);
    this.settings = Objects.requireNonNull(settings); this.translation = Objects.requireNonNull(coordinator);
    this.demo = demo; this.translationStore = store; this.translationConfig = config;
    setSelectionTranslation(selection, selectionDemo);
  }
}
