package com.example.bilingualreader.di;

import android.content.Context;
import com.example.bilingualreader.data.repository.PreferencesSettingsRepository;
import com.example.bilingualreader.debug.DemoRepository;

public final class AppGraphFactory {
  public static AppContainer create(Context context) {
    DemoRepository demo = new DemoRepository();
    return new AppContainer(demo, demo, new PreferencesSettingsRepository(context), demo, true);
  }
}
