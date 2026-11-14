package com.example.bilingualreader.contract.translation;

import com.example.bilingualreader.contract.model.*;
import java.util.*;

public final class TranslationInput {
  private final String paragraphId;
  private final String sourceHash;
  private final String text;
  private final Context context;

  /** Optional, transient selection context. Offsets count Unicode code points, not UTF-16 units. */
  public static final class Context {
    private final String text;
    private final int start,end;
    public Context(String text,int start,int end) {
      this.text=Objects.requireNonNull(text);this.start=start;this.end=end;
    }
    public String getText(){return text;}
    public int getStart(){return start;}
    public int getEnd(){return end;}
  }

  public TranslationInput(String paragraphId, String sourceHash, String text) {
    this(paragraphId,sourceHash,text,null);
  }
  public TranslationInput(String paragraphId,String sourceHash,String text,Context context) {
    this.paragraphId = Objects.requireNonNull(paragraphId);
    this.sourceHash = Objects.requireNonNull(sourceHash);
    this.text = Objects.requireNonNull(text);
    this.context = context;
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
  public Context getContext(){return context;}
}
