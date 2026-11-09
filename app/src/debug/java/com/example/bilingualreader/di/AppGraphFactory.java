package com.example.bilingualreader.di;

import android.content.Context;
<<<<<<< Updated upstream
import com.example.bilingualreader.data.repository.*;
import com.example.bilingualreader.debug.StoreDemoCoordinator;
import com.example.bilingualreader.translation.FakeTranslationService;
=======
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.TranslationService;
import com.example.bilingualreader.data.repository.*;
import com.example.bilingualreader.translation.*;
>>>>>>> Stashed changes

public final class AppGraphFactory {
  public static AppContainer create(Context context) {
    LocalLibraryRepository library = new LocalLibraryRepository(context);
<<<<<<< Updated upstream
    return new AppContainer(
        library,
        library,
        new PreferencesSettingsRepository(context),
        new StoreDemoCoordinator(library, new FakeTranslationService()),
        true,
        new FakeTranslationService(),
        true);
=======
    TranslationConfigStore config = new TranslationConfigStore(context, true);
    WorkTranslationCoordinator coordinator = new WorkTranslationCoordinator(context, library, config);
    return new AppContainer(new TranslationBookRepository(library, library, coordinator), library,
        new PreferencesSettingsRepository(context), coordinator, false, library, config);
  }
  public static TranslationService service(String mode, String endpoint) throws AppException {
    if (TranslationConfigStore.MOCK.equals(mode)) return new FakeTranslationService();
    if (!TranslationConfigStore.ONLINE.equals(mode) || endpoint.isEmpty()) throw TranslationConfigStore.configuration();
    try { return new LibreTranslateService(endpoint); }
    catch (java.net.MalformedURLException | IllegalArgumentException invalid) {
      throw TranslationConfigStore.configuration();
    }
>>>>>>> Stashed changes
  }
}
