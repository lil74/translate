package com.example.bilingualreader.di;

import android.content.Context;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.TranslationService;
import com.example.bilingualreader.data.repository.*;
import com.example.bilingualreader.translation.*;

public final class AppGraphFactory {
  public static AppContainer create(Context context) {
    LocalLibraryRepository library = new LocalLibraryRepository(context);
    TranslationConfigStore config = new TranslationConfigStore(context, true);
    WorkTranslationCoordinator coordinator = new WorkTranslationCoordinator(context, library, config);
    AppContainer graph = new AppContainer(new TranslationBookRepository(library, library, coordinator), library,
        new PreferencesSettingsRepository(context), coordinator, false,
        selectionService(config, config.mode(), config.endpoint()), TranslationConfigStore.MOCK.equals(config.mode()), library, config);
    config.setOnChanged(() -> graph.setSelectionTranslation(
        selectionService(config, config.mode(), config.endpoint()), TranslationConfigStore.MOCK.equals(config.mode())));
    return graph;
  }
  private static TranslationService selectionService(TranslationConfigStore config, String mode, String endpoint) {
    // Captured settings: a retained sheet cannot switch provider after user confirmation.
    try {
      String token=config.tokenForEndpoint(endpoint);
      return new IsolatedSelectionTranslationService(() -> service(mode, endpoint, token));
    } catch (AppException error) { return request -> { throw error; }; }
  }
  public static TranslationService service(String mode, String endpoint) throws AppException {
    return service(mode, endpoint, "");
  }
  public static TranslationService service(String mode, String endpoint, String token) throws AppException {
    if (TranslationConfigStore.MOCK.equals(mode)) return new FakeTranslationService();
    if (!TranslationConfigStore.ONLINE.equals(mode) || endpoint.isEmpty()) throw TranslationConfigStore.configuration();
    try {

      return new LibreTranslateService(endpoint, token);
    } catch (java.net.MalformedURLException | IllegalArgumentException invalid) {
      throw TranslationConfigStore.configuration();
    }
  }
}
