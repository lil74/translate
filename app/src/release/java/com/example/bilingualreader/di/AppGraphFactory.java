package com.example.bilingualreader.di;

import android.content.Context;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.TranslationService;
import com.example.bilingualreader.data.repository.*;
import com.example.bilingualreader.translation.*;

public final class AppGraphFactory {
  public static AppContainer create(Context context) {
    LocalLibraryRepository library = new LocalLibraryRepository(context);
    TranslationConfigStore config = new TranslationConfigStore(context, false);
    WorkTranslationCoordinator coordinator = new WorkTranslationCoordinator(context, library, config);
    AppContainer graph = new AppContainer(new TranslationBookRepository(library, library, coordinator), library,
        new PreferencesSettingsRepository(context), coordinator, false,
        selectionService(config.mode(), config.endpoint()), false, library, config);
    config.setOnChanged(() -> graph.setSelectionTranslation(
        selectionService(config.mode(), config.endpoint()), false));
    return graph;
  }
  private static TranslationService selectionService(String mode, String endpoint) {
    // Captured settings: a retained sheet cannot switch provider after user confirmation.
    return new IsolatedSelectionTranslationService(() -> service(mode, endpoint));
  }
  public static TranslationService service(String mode, String endpoint) throws AppException {

    if (!TranslationConfigStore.ONLINE.equals(mode) || endpoint.isEmpty()) throw TranslationConfigStore.configuration();
    try {
      if (!"https".equals(new java.net.URL(endpoint).getProtocol())) throw TranslationConfigStore.configuration();
      return new LibreTranslateService(endpoint);
    } catch (java.net.MalformedURLException | IllegalArgumentException invalid) {
      throw TranslationConfigStore.configuration();
    }
  }
}
