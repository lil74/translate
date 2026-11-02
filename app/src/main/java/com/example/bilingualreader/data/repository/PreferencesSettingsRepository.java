package com.example.bilingualreader.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.SettingsRepository;

/** A 提供的可复用设置存储；B 可继续使用，避免重建同一功能。 */
public final class PreferencesSettingsRepository implements SettingsRepository {
  private final SharedPreferences prefs;
  private final MutableLiveData<ReaderSettings> state;

  public PreferencesSettingsRepository(Context context) {
    prefs = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE);
    ReaderTheme theme =
        "NIGHT".equals(prefs.getString("theme", "DAY")) ? ReaderTheme.NIGHT : ReaderTheme.DAY;
    state =
        new MutableLiveData<>(
            new ReaderSettings(
                Math.max(14, Math.min(30, prefs.getInt("font", 18))),
                Math.max(1.2f, Math.min(2f, prefs.getFloat("spacing", 1.5f))),
                theme));
  }

  public LiveData<ReaderSettings> observeSettings() {
    return state;
  }

  public void saveSettings(ReaderSettings s) throws AppException {
    if (!prefs
        .edit()
        .putInt("font", s.getFontSizeSp())
        .putFloat("spacing", s.getLineSpacingMultiplier())
        .putString("theme", s.getTheme().name())
        .commit()) throw new AppException(ErrorCode.DATABASE_ERROR, "设置保存失败，请重试", true);
    state.postValue(s);
  }
}
