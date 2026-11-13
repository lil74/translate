package com.example.bilingualreader.viewmodel;

import static org.junit.Assert.*;

import com.example.bilingualreader.contract.model.Chapter;
import java.util.*;
import org.junit.Test;

public class ReaderProgressTest {
  private Chapter chapter(String id, int count, int order) {
    return new Chapter(id, "book", id, order, id + ".xhtml", count);
  }

  private List<Chapter> book() {
    return Arrays.asList(
        chapter("intro", 2, 0), chapter("chapter1", 8, 1), chapter("chapter2", 10, 2));
  }

  @Test
  public void frontMatterAndUnequalChaptersUseParagraphWeights() {
    assertEquals(0, ReaderProgress.percent(book(), "intro", 0, 0, false));
    assertEquals(10, ReaderProgress.percent(book(), "chapter1", 0, 0, false));
    assertEquals(30, ReaderProgress.percent(book(), "chapter1", 4, 0, false));
    assertEquals(50, ReaderProgress.percent(book(), "chapter2", 0, 0, false));
  }

  @Test
  public void scrollingWithinLongParagraphUpdatesProgress() {
    List<Chapter> one = Collections.singletonList(chapter("long", 1, 0));
    assertEquals(25, ReaderProgress.percent(one, "long", 0, .25, false));
    assertEquals(75, ReaderProgress.percent(one, "long", 0, .75, false));
  }

  @Test
  public void chapterBottomAndBookBottomReachCorrectBoundaries() {
    assertEquals(10, ReaderProgress.percent(book(), "intro", 0, 0, true));
    assertEquals(50, ReaderProgress.percent(book(), "chapter1", 4, .1, true));
    assertEquals(100, ReaderProgress.percent(book(), "chapter2", 3, 0, true));
    assertEquals(99, ReaderProgress.percent(book(), "chapter2", 9, .9, false));
  }

  @Test
  public void emptyOrMissingContentHasSafeProgress() {
    assertEquals(0, ReaderProgress.percent(Collections.emptyList(), "missing", 0, 0, false));
    assertEquals(
        0,
        ReaderProgress.percent(
            Collections.singletonList(chapter("empty", 0, 0)), "empty", 0, 0, true));
    assertEquals(0, ReaderProgress.percent(book(), "missing", 0, 0, true));
  }

  @Test
  public void offsetsAndStaleIndicesCannotEscapeRange() {
    List<Chapter> one = Collections.singletonList(chapter("one", 10, 500));
    assertEquals(0, ReaderProgress.percent(one, "one", -1, -.5, false));
    assertEquals(100, ReaderProgress.percent(one, "one", 100, 1.5, false));
    assertEquals(0, ReaderProgress.percent(one, "one", 0, Double.NaN, false));
    assertEquals(20, ReaderProgress.percent(one, "one", 2, Double.POSITIVE_INFINITY, false));
  }
}
