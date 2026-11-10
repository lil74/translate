package com.example.bilingualreader.translation;

import java.util.Locale;

/** Cache provenance policy; uses existing provider metadata, with no schema changes. */
public final class TranslationCachePolicy {
  private TranslationCachePolicy() {}

  public static boolean canReuse(String mode, String provider, String text) {
    // Mock mode can retain genuine translations, so testing never overwrites paid results.
    if (!"online".equals(mode)) return true;
    if (provider == null || provider.trim().isEmpty()) return false;
    String name = provider.trim().toLowerCase(Locale.ROOT);
    if (name.equals("fake") || name.equals("mock") || name.equals("demo")
        || name.startsWith("fake:") || name.startsWith("mock:")) return false;
    // Support older debug data whose provider metadata was absent or inconsistent.
    return text == null || !(text.startsWith("【模拟译文") || text.startsWith("[MOCK]"));
  }
}
