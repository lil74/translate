package com.example.bilingualreader.viewmodel;

import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.di.AppContainer;

public final class SettingsViewModel extends BaseViewModel {
  public final LiveData<ReaderSettings> settings;

  public SettingsViewModel(AppContainer graph) {
    super(graph);
    settings = graph.settings.observeSettings();
  }

  public void save(int font, float spacing, boolean night) {
    execute(
        () -> {
          graph.settings.saveSettings(
              new ReaderSettings(font, spacing, night ? ReaderTheme.NIGHT : ReaderTheme.DAY));
          return "阅读设置已保存";
        });
  }
}
