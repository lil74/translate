package com.example.bilingualreader.contract.model;

import java.util.*;

public final class Paragraph {
  private final String paragraphId;
  private final String chapterId;
  private final int orderIndex;
  private final BlockType blockType;
  private final String originalText;
  private final String sourceHash;

  public Paragraph(
      String paragraphId,
      String chapterId,
      int orderIndex,
      BlockType blockType,
      String originalText,
      String sourceHash) {
    this.paragraphId = Objects.requireNonNull(paragraphId);
    this.chapterId = Objects.requireNonNull(chapterId);
    this.orderIndex = orderIndex;
    this.blockType = Objects.requireNonNull(blockType);
    this.originalText = Objects.requireNonNull(originalText);
    this.sourceHash = Objects.requireNonNull(sourceHash);
  }

  public String getParagraphId() {
    return paragraphId;
  }

  public String getChapterId() {
    return chapterId;
  }

  public int getOrderIndex() {
    return orderIndex;
  }

  public BlockType getBlockType() {
    return blockType;
  }

  public String getOriginalText() {
    return originalText;
  }

  public String getSourceHash() {
    return sourceHash;
  }
}
