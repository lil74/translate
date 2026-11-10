import com.example.bilingualreader.contract.model.Chapter;
import com.example.bilingualreader.contract.model.TocEntry;
import com.example.bilingualreader.viewmodel.ReaderSectionTitle;
import java.util.*;

/** Regression checks for front matter being mislabelled as numbered chapters. */
public final class ReaderSectionTitleCheck {
  private static int count;

  private static Chapter chapter(String id, String title, int spinePosition) {
    return new Chapter(id, "book", title, spinePosition, id + ".xhtml", 4);
  }

  private static TocEntry toc(
      String id, String title, String chapter, String parent, String paragraph, int order) {
    return new TocEntry(id, "book", parent, order, title, chapter, paragraph);
  }

  private static void check(String expected, String actual) {
    if (!expected.equals(actual))
      throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
    count++;
  }

  public static void main(String[] args) {
    List<TocEntry> none = Collections.emptyList();
    check(
        "PRAISE FOR THE DEAL",
        ReaderSectionTitle.resolve(chapter("praise", "PRAISE FOR THE DEAL", 0), none));
    check("引言", ReaderSectionTitle.resolve(chapter("intro", "Introduction", 1), none));
    check("目录", ReaderSectionTitle.resolve(chapter("contents", "CONTENTS", 2), none));
    check("第 1 章", ReaderSectionTitle.resolve(chapter("first", "1", 3), none));
    check("第 2 章", ReaderSectionTitle.resolve(chapter("second", "2", 4), none));
    check("第 1 章", ReaderSectionTitle.resolve(chapter("first", "1", 100), none));
    check("前言", ReaderSectionTitle.format("Preface"));
    check("序言", ReaderSectionTitle.format("FOREWORD"));
    check("序章", ReaderSectionTitle.format("Prologue"));
    check("尾声", ReaderSectionTitle.format("Epilogue"));
    check("目录", ReaderSectionTitle.format("Table\nof\u00a0Contents"));
    check("第 1 章", ReaderSectionTitle.format("Chapter 001"));
    check("第 12 章", ReaderSectionTitle.format("chapter 12."));
    check("第 3 章", ReaderSectionTitle.format("第 03 章"));
    check("第一章", ReaderSectionTitle.format("第一章"));
    check("Chapter IV", ReaderSectionTitle.format("Chapter IV"));
    check("A small beginning", ReaderSectionTitle.format(" A  small\nbeginning "));
    check("阅读内容", ReaderSectionTitle.format("\n \t"));

    Chapter first = chapter("first", "HANNAH", 3);
    List<TocEntry> metadata =
        Arrays.asList(
            toc("other", "Contents", "other-resource", null, null, 0),
            toc("child", "A later scene", "first", "root", "middle-paragraph", 2),
            toc("root", "Chapter 1", "first", null, null, 1));
    check("第 1 章", ReaderSectionTitle.resolve(first, metadata));
    check("HANNAH", ReaderSectionTitle.resolve(first, Collections.singletonList(metadata.get(1))));
    check("HANNAH", ReaderSectionTitle.resolve(first, Collections.singletonList(metadata.get(0))));
    check(
        "Nested resource title",
        ReaderSectionTitle.resolve(
            first,
            Collections.singletonList(
                toc("nested", "Nested resource title", "first", "part-one", null, 0))));
    check("Chapter One — HANNAH", ReaderSectionTitle.format("Chapter One — HANNAH"));
    System.out.println(
        "PASS: "
            + count
            + " section-title checks (front matter, real numbers, TOC mapping, anchors and"
            + " fallbacks)");
  }
}
