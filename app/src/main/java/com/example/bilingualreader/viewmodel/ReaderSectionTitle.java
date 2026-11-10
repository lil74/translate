package com.example.bilingualreader.viewmodel;

import com.example.bilingualreader.contract.model.Chapter;
import com.example.bilingualreader.contract.model.TocEntry;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display publication labels, never a spine position disguised as a chapter number. */
public final class ReaderSectionTitle {
  private static final Pattern NUMBER = Pattern.compile("^[0-9]+$");
  private static final Pattern ENGLISH_CHAPTER =
      Pattern.compile("^chapter\\s+([0-9]+)\\s*[.:]?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern CHINESE_CHAPTER = Pattern.compile("^第\\s*([0-9]+)\\s*章$");

  private ReaderSectionTitle() {}

  public static String resolve(Chapter chapter, List<TocEntry> contents) {
    TocEntry root = null;
    TocEntry nested = null;
    for (TocEntry entry : contents) {
      // An internal anchor may name a later subsection, not the resource being opened.
      if (!chapter.getChapterId().equals(entry.getChapterId())
          || entry.getParagraphId() != null
          || clean(entry.getTitle()).isEmpty()) continue;
      if (entry.getParentTocId() == null) {
        if (root == null || entry.getOrderIndex() < root.getOrderIndex()) root = entry;
      } else if (nested == null || entry.getOrderIndex() < nested.getOrderIndex()) nested = entry;
    }
    String title =
        root != null ? root.getTitle() : nested != null ? nested.getTitle() : chapter.getTitle();
    return format(title);
  }

  public static String format(String rawTitle) {
    String title = clean(rawTitle);
    if (title.isEmpty()) return "阅读内容";
    if (NUMBER.matcher(title).matches()) return numbered(title);
    Matcher english = ENGLISH_CHAPTER.matcher(title);
    if (english.matches()) return numbered(english.group(1));
    Matcher chinese = CHINESE_CHAPTER.matcher(title);
    if (chinese.matches()) return numbered(chinese.group(1));
    switch (title.toLowerCase(Locale.ROOT)) {
      case "contents":
      case "table of contents":
        return "目录";
      case "introduction":
        return "引言";
      case "preface":
        return "前言";
      case "foreword":
        return "序言";
      case "prologue":
        return "序章";
      case "epilogue":
        return "尾声";
      default:
        return title;
    }
  }

  private static String numbered(String number) {
    return "第 " + number.replaceFirst("^0+(?!$)", "") + " 章";
  }

  private static String clean(String title) {
    return title == null ? "" : title.replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
  }
}
