package com.example.bilingualreader.ui.settings;

import android.content.*;
import com.example.bilingualreader.R;

public final class AppAppearance {
  private AppAppearance() {}

  public static String palette(Context context) {
    String value = context.getSharedPreferences("app_appearance", 0).getString("palette", "green");
    return "blue".equals(value) || "brown".equals(value) ? value : "green";
  }

  public static int theme(Context context) {
    switch (palette(context)) {
      case "blue":
        return R.style.Theme_BilingualReader_Blue;
      case "brown":
        return R.style.Theme_BilingualReader_Brown;
      default:
        return R.style.Theme_BilingualReader;
    }
  }

  public static String label(Context context) {
    switch (palette(context)) {
      case "blue":
        return "雾蓝";
      case "brown":
        return "暖棕";
      default:
        return "苔绿";
    }
  }

  public static void save(Context context, String value) {
    if (!"green".equals(value) && !"blue".equals(value) && !"brown".equals(value))
      throw new IllegalArgumentException("Unknown palette");
    context.getSharedPreferences("app_appearance", 0).edit().putString("palette", value).apply();
  }
}
