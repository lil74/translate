package com.example.bilingualreader.contract.translation;

import androidx.annotation.Nullable;
import com.example.bilingualreader.contract.model.*;
import java.util.*;

public final class TranslationOutput {
  private final String paragraphId;
  private final String sourceHash;
  private final TranslationStatus status;
  private final @Nullable String translatedText;
  private final String provider;
  private final @Nullable String engineVersion;
  private final @Nullable String errorCode;

  public TranslationOutput(
      String paragraphId,
      String sourceHash,
      TranslationStatus status,
      @Nullable String translatedText,
      String provider,
      @Nullable String engineVersion,
      @Nullable String errorCode) {
    this.paragraphId = Objects.requireNonNull(paragraphId);
    this.sourceHash = Objects.requireNonNull(sourceHash);
    this.status = Objects.requireNonNull(status);
    this.translatedText = translatedText;
    this.provider = Objects.requireNonNull(provider);
    this.engineVersion = engineVersion;
    this.errorCode = errorCode;
  }

  public String getParagraphId() {
    return paragraphId;
  }

  public String getSourceHash() {
    return sourceHash;
  }

  public TranslationStatus getStatus() {
    return status;
  }

  public @Nullable String getTranslatedText() {
    return translatedText;
  }

  public String getProvider() {
    return provider;
  }

  public @Nullable String getEngineVersion() {
    return engineVersion;
  }

  public @Nullable String getErrorCode() {
    return errorCode;
  }
}
