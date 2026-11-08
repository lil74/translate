package com.example.bilingualreader.di;

import android.content.Context;
import com.example.bilingualreader.data.repository.*;
import com.example.bilingualreader.debug.StoreDemoCoordinator;
import com.example.bilingualreader.translation.FakeTranslationService;

public final class AppGraphFactory {
  public static AppContainer create(Context context) {
    LocalLibraryRepository library = new LocalLibraryRepository(context);
    return new AppContainer(
        library,
        library,
        new PreferencesSettingsRepository(context),
        new StoreDemoCoordinator(library, new FakeTranslationService()),
        true,
        new FakeTranslationService(),
        true);
  }
}
