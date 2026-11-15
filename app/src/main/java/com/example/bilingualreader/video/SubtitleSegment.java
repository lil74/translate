package com.example.bilingualreader.video;

import com.example.bilingualreader.translation.TranslationValidation;

/** Immutable boundary shared by subtitle extraction and future OCR/ASR adapters. Milliseconds. */
public final class SubtitleSegment {
  public enum TextSource { SUBTITLE, OCR, ASR }
  public final String id;
  public final long startMs, endMs;
  public final String sourceText;
  public final TextSource textSource;
  public final Float confidence;
  public final String translatedText, provider, engineVersion;

  public SubtitleSegment(String id, long startMs, long endMs, String sourceText,
      TextSource textSource, Float confidence, String translatedText, String provider, String engineVersion) {
    if (id == null || id.isEmpty() || startMs < 0 || endMs <= startMs
        || sourceText == null || sourceText.trim().isEmpty() || textSource == null
        || confidence != null && (!Float.isFinite(confidence) || confidence < 0 || confidence > 1)
        || translatedText != null && (translatedText.trim().isEmpty() || provider == null || provider.trim().isEmpty()))
      throw new IllegalArgumentException("字幕片段数据无效");
    this.id = id; this.startMs = startMs; this.endMs = endMs; this.sourceText = sourceText;
    this.textSource = textSource; this.confidence = confidence;
    this.translatedText = translatedText; this.provider = provider; this.engineVersion = engineVersion;
  }
  public String sourceHash() { return TranslationValidation.sha256(sourceText); }
  public SubtitleSegment translated(String text, String provider, String version) {
    return new SubtitleSegment(id, startMs, endMs, sourceText, textSource, confidence, text, provider, version);
  }
  public SubtitleSegment clearTranslation() {
    return new SubtitleSegment(id, startMs, endMs, sourceText, textSource, confidence, null, null, null);
  }
  public SubtitleSegment edit(String text) {
    return new SubtitleSegment(id, startMs, endMs, text, textSource, confidence,
        sourceText.equals(text) ? translatedText : null,
        sourceText.equals(text) ? provider : null, sourceText.equals(text) ? engineVersion : null);
  }
}
