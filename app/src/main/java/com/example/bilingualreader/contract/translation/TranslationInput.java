package com.example.bilingualreader.contract.translation;

import com.example.bilingualreader.contract.model.*;
import java.util.*;

public final class TranslationInput {
  private final String paragraphId;
  private final String sourceHash;
  private final String text;

  public TranslationInput(String paragraphId, String sourceHash, String text) {
    this.paragraphId = Objects.requireNonNull(paragraphId);
    this.sourceHash = Objects.requireNonNull(sourceHash);
    this.text = Objects.requireNonNull(text);
  }

  public String getParagraphId() {
    return paragraphId;
  }

  public String getSourceHash() {
    return sourceHash;
  }

  public String getText() {
    return text;
  }
}
