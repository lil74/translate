package com.example.bilingualreader.di;

import android.content.Context;
import com.example.bilingualreader.data.repository.PreferencesSettingsRepository;
import com.example.bilingualreader.data.repository.LocalLibraryRepository;
import com.example.bilingualreader.debug.DemoRepository;

public final class AppGraphFactory {
  public static AppContainer create(Context context) {
    DemoRepository demo = new DemoRepository();
    LocalLibraryRepository library = new LocalLibraryRepository(context);
    return new AppContainer(library, library, new PreferencesSettingsRepository(context), demo, false);
  }
}
