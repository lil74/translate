package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.Paragraph;
import java.text.BreakIterator;
import java.util.*;

/** Keeps B's paragraphs unchanged; chunk IDs exist only on the network boundary. */
public final class TranslationBatcher {
  public static final int MAX_ITEMS = 8, MAX_POINTS = 4000;
  private TranslationBatcher() {}
  public static List<List<Paragraph>> batches(List<Paragraph> paragraphs) {
    List<List<Paragraph>> result = new ArrayList<>();
    List<Paragraph> batch = new ArrayList<>(); int size = 0;
    for (Paragraph p : paragraphs) {
      int points = p.getOriginalText().codePointCount(0, p.getOriginalText().length());
      if (!batch.isEmpty() && (batch.size() == MAX_ITEMS || size + points > MAX_POINTS)) {
        result.add(batch); batch = new ArrayList<>(); size = 0;
      }
      batch.add(p); size += points;
      if (points > MAX_POINTS) { result.add(batch); batch = new ArrayList<>(); size = 0; }
    }
    if (!batch.isEmpty()) result.add(batch);
    return result;
  }
  public static List<String> chunks(String text) {
    List<String> chunks = new ArrayList<>();
    BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH);
    sentences.setText(text);
    int start = 0;
    int remaining = text.codePointCount(0, text.length());
    while (start < text.length()) {
      int end = remaining <= MAX_POINTS ? text.length() : text.offsetByCodePoints(start, MAX_POINTS);
      if (end < text.length()) {
        int boundary = sentences.preceding(end + 1);
        if (boundary > start) end = boundary;
      }
      String chunk = text.substring(start, end);
      chunks.add(chunk); remaining -= chunk.codePointCount(0, chunk.length()); start = end;
    }
    return chunks;
  }
}
