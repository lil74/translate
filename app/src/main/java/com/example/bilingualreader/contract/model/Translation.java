package com.example.bilingualreader.contract.model;

import androidx.annotation.Nullable;
import java.util.*;

public final class Translation {
  private final String paragraphId;
  private final String targetLanguage;
  private final String sourceLanguage;
  private final String sourceHash;
  private final @Nullable String translatedText;
  private final TranslationStatus status;
  private final @Nullable String provider;
  private final @Nullable String engineVersion;
  private final @Nullable String errorCode;
  private final long updatedAt;

  public Translation(
      String paragraphId,
      String targetLanguage,
      String sourceLanguage,
      String sourceHash,
      @Nullable String translatedText,
      TranslationStatus status,
      @Nullable String provider,
      @Nullable String engineVersion,
      @Nullable String errorCode,
      long updatedAt) {
    this.paragraphId = Objects.requireNonNull(paragraphId);
    this.targetLanguage = Objects.requireNonNull(targetLanguage);
    this.sourceLanguage = Objects.requireNonNull(sourceLanguage);
    this.sourceHash = Objects.requireNonNull(sourceHash);
    this.translatedText = translatedText;
    this.status = Objects.requireNonNull(status);
    this.provider = provider;
    this.engineVersion = engineVersion;
    this.errorCode = errorCode;
    this.updatedAt = updatedAt;
  }

  public String getParagraphId() {
    return paragraphId;
  }

  public String getTargetLanguage() {
    return targetLanguage;
  }

  public String getSourceLanguage() {
    return sourceLanguage;
  }

  public String getSourceHash() {
    return sourceHash;
  }

  public @Nullable String getTranslatedText() {
    return translatedText;
  }

  public TranslationStatus getStatus() {
    return status;
  }

  public @Nullable String getProvider() {
    return provider;
  }

  public @Nullable String getEngineVersion() {
    return engineVersion;
  }

  public @Nullable String getErrorCode() {
    return errorCode;
  }

  public long getUpdatedAt() {
    return updatedAt;
  }
}
