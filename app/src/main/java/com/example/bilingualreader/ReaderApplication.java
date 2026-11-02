package com.example.bilingualreader;

import android.app.Application;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.di.AppGraphFactory;

public final class ReaderApplication extends Application {
  private AppContainer container;

  @Override
  public void onCreate() {
    super.onCreate();
    container = AppGraphFactory.create(this);
  }

  public AppContainer getContainer() {
    return container;
  }
}
