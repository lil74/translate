package com.example.bilingualreader.viewmodel;

import com.example.bilingualreader.contract.model.Chapter;
import java.util.List;

/** Whole-book estimate weighted by paragraph counts; never uses a chapter's displayed number. */
public final class ReaderProgress {
  private ReaderProgress() {}

  public static int percent(
      List<Chapter> chapters,
      String chapterId,
      int paragraphIndex,
      double fraction,
      boolean chapterEnd) {
    long total = 0, before = 0;
    Chapter current = null;
    for (Chapter chapter : chapters) {
      int count = Math.max(0, chapter.getParagraphCount());
      total += count;
      if (current == null) {
        if (chapter.getChapterId().equals(chapterId)) current = chapter;
        else before += count;
      }
    }
    if (total == 0 || current == null) return 0;
    double position =
        chapterEnd
            ? Math.max(0, current.getParagraphCount())
            : Math.max(
                0,
                Math.min(
                    current.getParagraphCount(),
                    Math.max(0, paragraphIndex)
                        + (Double.isFinite(fraction) ? Math.max(0, Math.min(1, fraction)) : 0)));
    return (int) Math.max(0, Math.min(100, Math.floor((before + position) * 100.0 / total)));
  }
}
