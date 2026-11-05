package com.example.bilingualreader.viewmodel;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.di.AppContainer;
import java.util.*;

public final class ReaderViewModel extends BaseViewModel {
  public static final class Location {
    public final Chapter chapter;
    public final String paragraphId;
    public final int offset;

    Location(Chapter c, String p, int o) {
      chapter = c;
      paragraphId = p;
      offset = o;
    }
  }

  public final MutableLiveData<UiState<Location>> location =
      new MutableLiveData<>(UiState.loading());
  private final MutableLiveData<String> chapterId = new MutableLiveData<>();
  public final LiveData<UiState<List<ParagraphItem>>> paragraphs;
  public final LiveData<TranslationTask> task;
  public final LiveData<ReaderSettings> settings;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private List<Chapter> chapters = Collections.emptyList();
  private String bookId;
  private String requestedChapter;
  private String requestedParagraph;
  private ReadingProgress pending;
  private ReadingProgress viewport;
  private int viewportWidth;
  private ReaderSettings viewportSettings;

  public ReadingProgress getViewport(String id) {
    return viewport != null && viewport.getChapterId().equals(id) ? viewport : null;
  }

  public int getViewportOffset(int width) {
    ReaderSettings now = settings.getValue();
    return width == viewportWidth
            && now != null
            && viewportSettings != null
            && now.getFontSizeSp() == viewportSettings.getFontSizeSp()
            && now.getLineSpacingMultiplier() == viewportSettings.getLineSpacingMultiplier()
        ? viewport.getOffsetPx()
        : 0;
  }

  private long generation;
  private boolean scheduled;
  private final Runnable flush = this::flushProgress;

  public ReaderViewModel(AppContainer graph) {
    super(graph);
    settings = graph.settings.observeSettings();
    paragraphs =
        Transformations.switchMap(
            chapterId,
            id -> {
              MediatorLiveData<UiState<List<ParagraphItem>>> state =
                  new MediatorLiveData<>(UiState.loading());
              state.addSource(
                  graph.reader.observeParagraphs(id, "zh-CN"),
                  rows -> {
                    if (rows != null) state.setValue(UiState.success(rows));
                  });
              return state;
            });
    task =
        Transformations.switchMap(
            chapterId,
            id -> {
              // A new chapter without a task must not inherit the previous chapter's status.
              MediatorLiveData<TranslationTask> state =
                  new MediatorLiveData<>((TranslationTask) null);
              state.addSource(graph.translation.observeLatestTask(id, "zh-CN"), state::setValue);
              return state;
            });
  }

  public void open(String book, String requestedChapter, String requestedParagraph) {
    if (book.equals(bookId) && location.getValue() != null && location.getValue().data != null)
      return;
    bookId = book;
    this.requestedChapter = requestedChapter;
    this.requestedParagraph = requestedParagraph;
    location.setValue(UiState.loading());
    long token = ++generation;
    graph.io.execute(
        () -> {
          try {
            List<Chapter> loaded = graph.books.getChapters(book);
            ReadingProgress saved = graph.reader.getReadingProgress(book);
            handler.post(
                () -> {
                  if (token != generation) return;
                  chapters = loaded;
                  if (loaded.isEmpty()) {
                    location.setValue(UiState.error("这本书没有可阅读的章节"));
                    return;
                  }
                  String desired =
                      requestedChapter != null
                          ? requestedChapter
                          : (saved == null ? null : saved.getChapterId());
                  Chapter selected = loaded.get(0);
                  for (Chapter c : loaded) if (c.getChapterId().equals(desired)) selected = c;
                  boolean restored =
                      requestedChapter == null
                          && saved != null
                          && selected.getChapterId().equals(saved.getChapterId());
                  select(
                      selected,
                      requestedParagraph != null
                          ? requestedParagraph
                          : (restored ? saved.getParagraphId() : null),
                      restored ? saved.getOffsetPx() : 0);
                });
          } catch (AppException e) {
            handler.post(
                () -> {
                  if (token == generation) location.setValue(UiState.error(e.getUserMessage()));
                });
          }
        });
  }

  public void retryOpen() {
    if (bookId == null) return;
    String book = bookId;
    bookId = null;
    open(book, requestedChapter, requestedParagraph);
  }

  private void select(Chapter c, String paragraph, int offset) {
    flushProgress();
    location.setValue(UiState.success(new Location(c, paragraph, offset)));
    chapterId.setValue(c.getChapterId());
  }

  public boolean canMove(int delta) {
    UiState<Location> s = location.getValue();
    if (s == null || s.data == null) return false;
    int index = chapters.indexOf(s.data.chapter) + delta;
    return index >= 0 && index < chapters.size();
  }

  public void move(int delta) {
    if (canMove(delta))
      select(chapters.get(chapters.indexOf(location.getValue().data.chapter) + delta), null, 0);
  }

  public void translate() {
    String id = chapterId.getValue();
    if (id != null)
      execute(
          () -> {
            graph.translation.startOrResumeChapter(id, "en", "zh-CN");
            return graph.demo ? "模拟翻译已开始" : "翻译任务已提交";
          });
  }

  public void cancel() {
    TranslationTask t = task.getValue();
    if (t != null)
      execute(
          () -> {
            graph.translation.cancelTask(t.getTaskId());
            return "已取消，完成的译文会保留";
          });
  }

  public void remember(String paragraph, int offset, int width) {
    String id = chapterId.getValue();
    if (id == null) return;
    pending = new ReadingProgress(bookId, id, paragraph, offset, System.currentTimeMillis());
    viewport = pending;
    viewportWidth = width;
    viewportSettings = settings.getValue();
    if (!scheduled) {
      scheduled = true;
      handler.postDelayed(flush, 1000);
    }
  }

  public void flushProgress() {
    scheduled = false;
    handler.removeCallbacks(flush);
    ReadingProgress snapshot = pending;
    pending = null;
    if (snapshot != null)
      graph.io.execute(
          () -> {
            try {
              graph.reader.saveReadingProgress(snapshot);
            } catch (AppException e) {
              command.postValue(UiState.error(e.getUserMessage()));
            }
          });
  }

  @Override
  protected void onCleared() {
    flushProgress();
    generation++;
    super.onCleared();
  }
}
