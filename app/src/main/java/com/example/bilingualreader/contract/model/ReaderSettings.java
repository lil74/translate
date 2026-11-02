package com.example.bilingualreader.contract.model;

import java.util.*;

public final class ReaderSettings {
  private final int fontSizeSp;
  private final float lineSpacingMultiplier;
  private final ReaderTheme theme;

  public ReaderSettings(int fontSizeSp, float lineSpacingMultiplier, ReaderTheme theme) {
    if (fontSizeSp < 14
        || fontSizeSp > 30
        || !Float.isFinite(lineSpacingMultiplier)
        || lineSpacingMultiplier < 1.2f
        || lineSpacingMultiplier > 2f)
      throw new IllegalArgumentException("Invalid reader settings");
    this.fontSizeSp = fontSizeSp;
    this.lineSpacingMultiplier = lineSpacingMultiplier;
    this.theme = Objects.requireNonNull(theme);
  }

  public int getFontSizeSp() {
    return fontSizeSp;
  }

  public float getLineSpacingMultiplier() {
    return lineSpacingMultiplier;
  }

  public ReaderTheme getTheme() {
    return theme;
  }

  public static ReaderSettings defaults() {
    return new ReaderSettings(18, 1.5f, ReaderTheme.DAY);
  }
}
