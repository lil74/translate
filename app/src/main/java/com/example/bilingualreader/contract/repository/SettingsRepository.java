package com.example.bilingualreader.contract.repository;

import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;

/** observe 方法可从主线程调用；其他方法只在后台线程调用。详见 docs/PROJECT_SPEC.md。 */
public interface SettingsRepository {
  LiveData<ReaderSettings> observeSettings();

  void saveSettings(ReaderSettings settings) throws AppException;
}
