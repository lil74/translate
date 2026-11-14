package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.translation.TranslationInput;

/** Keeps the selected occurrence and its nearest paragraph context, bounded without splitting emoji. */
public final class SelectionContext {
  public static final int MAX_POINTS=4000;
  private SelectionContext() {}
  public static TranslationInput.Context fromParagraph(String paragraph,int start,int end) {
    if(paragraph==null || start<0 || end<=start || end>paragraph.length()
        || splitsSurrogate(paragraph,start) || splitsSurrogate(paragraph,end))
      throw new IllegalArgumentException("Invalid selection");
    int selected=paragraph.codePointCount(start,end);
    if(selected>MAX_POINTS)throw new IllegalArgumentException("Selection too long");
    int before=paragraph.codePointCount(0,start),after=paragraph.codePointCount(end,paragraph.length());
    int budget=MAX_POINTS-selected;
    int left=Math.min(before,budget/2),right=Math.min(after,budget-left);
    left=Math.min(before,budget-right);
    int lo=paragraph.offsetByCodePoints(start,-left),hi=paragraph.offsetByCodePoints(end,right);
    return new TranslationInput.Context(paragraph.substring(lo,hi),left,left+selected);
  }
  private static boolean splitsSurrogate(String value,int offset) {
    return offset>0 && offset<value.length() && Character.isHighSurrogate(value.charAt(offset-1))
        && Character.isLowSurrogate(value.charAt(offset));
  }
}
