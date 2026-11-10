package com.example.bilingualreader.di;

/** Optional debug-only controls. Never implemented by a production translator. */
public interface TranslationDiagnostics {
  void failNextRequest();
}
