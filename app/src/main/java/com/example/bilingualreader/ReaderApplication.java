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
    container.io.execute(() -> {
      try {
        ((com.example.bilingualreader.translation.WorkTranslationCoordinator) container.translation)
            .recoverUnfinishedTasks();
      } catch (com.example.bilingualreader.contract.model.AppException error) {
        android.util.Log.w("TranslationRecovery", "Recovery deferred: " + error.getCode());
      }
    });
  }

  public AppContainer getContainer() {
    return container;
  }
}
