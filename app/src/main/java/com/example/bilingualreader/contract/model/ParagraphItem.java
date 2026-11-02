package com.example.bilingualreader.contract.model;

import androidx.annotation.Nullable;
import java.util.*;

public final class ParagraphItem {
  private final Paragraph paragraph;
  private final @Nullable Translation translation;

  public ParagraphItem(Paragraph paragraph, @Nullable Translation translation) {
    this.paragraph = Objects.requireNonNull(paragraph);
    this.translation = translation;
  }

  public Paragraph getParagraph() {
    return paragraph;
  }

  public @Nullable Translation getTranslation() {
    return translation;
  }
}
