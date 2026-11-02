package com.example.bilingualreader.debug;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.*;
import com.example.bilingualreader.contract.translation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 仅 Debug UI 演示。内存数据不会跨进程持久化，不是 B/C 的生产实现。 */
public final class DemoRepository
    implements BookRepository, ReaderRepository, TranslationCoordinator {
  private static final String BOOK = "demo-book";
  private final MutableLiveData<List<Book>> books = new MutableLiveData<>();
  private final Map<String, MutableLiveData<List<ParagraphItem>>> paragraphs = new HashMap<>();
  private final Map<String, MutableLiveData<TranslationTask>> latest = new HashMap<>();
  private final Map<String, MutableLiveData<TranslationTask>> tasks = new HashMap<>();
  private final List<Chapter> chapters = new ArrayList<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private ReadingProgress progress;
  private boolean deleted;

  public DemoRepository() {
    Book b =
        new Book(
            BOOK,
            "The Little Journey",
            "轻阅原创演示故事",
            "en",
            "demo.epub",
            "",
            null,
            2,
            1,
            System.currentTimeMillis(),
            null);
    books.setValue(Collections.singletonList(b));
    String[][] texts = {
      {
        "Chapter One · A small beginning",
        "Every morning, Anna walked past the little bookshop on her way to work.",
        "One rainy afternoon, she finally opened the door. A warm light fell across the wooden"
            + " floor.",
        "“You can start anywhere,” the owner said, handing her a book. “A page is already a"
            + " beginning.”"
      },
      {
        "Chapter Two · Beyond the window",
        "On Saturday, Anna took her new book to a quiet café near the station.",
        "Outside the window, people hurried through the rain. Inside, she read slowly and let each"
            + " sentence settle.",
        "She did not understand every word, but she wanted to know what happened next."
      }
    };
    for (int c = 0; c < 2; c++) {
      String id = "demo-chapter-" + c;
      chapters.add(
          new Chapter(
              id,
              BOOK,
              c == 0 ? "01  A small beginning" : "02  Beyond the window",
              c,
              "demo/" + c + ".xhtml",
              4));
      List<ParagraphItem> list = new ArrayList<>();
      for (int i = 0; i < 4; i++)
        list.add(
            new ParagraphItem(
                new Paragraph(
                    id + "-p" + i,
                    id,
                    i,
                    i == 0 ? BlockType.HEADING : BlockType.TEXT,
                    texts[c][i],
                    hash(texts[c][i])),
                null));
      paragraphs.put(id, new MutableLiveData<>(list));
      latest.put(id, new MutableLiveData<>());
    }
  }

  private static String hash(String s) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void requireBook(String id) throws AppException {
    if (deleted || !BOOK.equals(id))
      throw new AppException(ErrorCode.BOOK_NOT_FOUND, "书籍不存在", false);
  }

  public ImportResult importBook(Uri uri) throws AppException {
    throw new AppException(
        ErrorCode.UNSUPPORTED_CONTENT, "当前为界面演示版：真实 EPUB 导入由 B 接入，不会把所选文件伪装成已导入", false);
  }

  public LiveData<List<Book>> observeBooks() {
    return books;
  }

  public synchronized List<Chapter> getChapters(String id) throws AppException {
    requireBook(id);
    return new ArrayList<>(chapters);
  }

  public synchronized List<TocEntry> getTableOfContents(String id) throws AppException {
    requireBook(id);
    List<TocEntry> out = new ArrayList<>();
    for (Chapter c : chapters)
      out.add(
          new TocEntry(
              "toc-" + c.getChapterId(),
              id,
              null,
              c.getOrderIndex(),
              c.getTitle(),
              c.getChapterId(),
              null));
    return out;
  }

  public synchronized void deleteBook(String id) throws AppException {
    requireBook(id);
    deleted = true;
    progress = null;
    main.post(
        () -> {
          books.setValue(Collections.emptyList());
          for (MutableLiveData<TranslationTask> t : tasks.values()) t.setValue(null);
          for (MutableLiveData<TranslationTask> t : latest.values()) t.setValue(null);
          for (MutableLiveData<List<ParagraphItem>> p : paragraphs.values())
            p.setValue(Collections.emptyList());
        });
  }

  public LiveData<List<ParagraphItem>> observeParagraphs(String id, String lang) {
    return paragraphs.containsKey(id)
        ? paragraphs.get(id)
        : new MutableLiveData<>(Collections.emptyList());
  }

  public synchronized ReadingProgress getReadingProgress(String id) throws AppException {
    requireBook(id);
    return progress;
  }

  public synchronized void saveReadingProgress(ReadingProgress p) throws AppException {
    requireBook(p.getBookId());
    progress = p;
  }

  public String startOrResumeChapter(String id, String source, String target) throws AppException {
    if (!"en".equals(source) || !"zh-CN".equals(target))
      throw new AppException(ErrorCode.UNSUPPORTED_LANGUAGE, "第一版仅支持英语 → 简体中文", false);
    synchronized (this) {
      if (deleted || !paragraphs.containsKey(id))
        throw new AppException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在", false);
    }
    java.util.concurrent.FutureTask<String> command =
        new java.util.concurrent.FutureTask<>(
            () -> {
              TranslationTask existing = latest.get(id).getValue();
              if (existing != null
                  && (existing.getState() == TaskState.RUNNING
                      || existing.getState() == TaskState.COMPLETED)) return existing.getTaskId();
              String taskId = UUID.randomUUID().toString();
              begin(taskId, id);
              return taskId;
            });
    main.post(command);
    try {
      return command.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AppException(ErrorCode.UNKNOWN, "模拟任务被中断", true, e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new AppException(ErrorCode.UNKNOWN, "模拟任务创建失败", true, e);
    }
  }

  private void begin(String taskId, String chapter) {
    if (deleted) return;
    TranslationTask old = latest.get(chapter).getValue();
    if (old != null
        && (old.getState() == TaskState.RUNNING || old.getState() == TaskState.COMPLETED)) return;
    MutableLiveData<TranslationTask> data =
        tasks.computeIfAbsent(taskId, k -> new MutableLiveData<>());
    int cached = 0;
    for (ParagraphItem row : paragraphs.get(chapter).getValue())
      if (row.getTranslation() != null
          && row.getTranslation().getStatus() == TranslationStatus.SUCCESS) cached++;
    publish(data, taskId, chapter, cached == 4 ? TaskState.COMPLETED : TaskState.RUNNING, cached);
    for (int i = 0; i < 4; i++) {
      final int index = i;
      main.postDelayed(() -> advance(data, taskId, chapter, index), 650L * (i + 1));
    }
  }

  private void advance(
      MutableLiveData<TranslationTask> data, String id, String chapter, int index) {
    TranslationTask t = data.getValue();
    if (deleted || t == null || t.getState() != TaskState.RUNNING) return;
    String[][] translated = {
      {
        "第一章 · 小小的开始",
        "每天早上，安娜上班时都会经过那家小书店。",
        "一个下雨的下午，她终于推开了门。温暖的光洒在木地板上。",
        "店主递给她一本书，说：‘从哪里开始都可以，读一页就是开始。’"
      },
      {
        "第二章 · 窗外",
        "周六，安娜带着新书来到车站附近一家安静的咖啡馆。",
        "窗外，人们在雨中匆匆走过。屋内，她慢慢阅读，细细体会每句话。",
        "她并非每个单词都懂，却很想知道接下来发生的故事。"
      }
    };
    List<ParagraphItem> rows = new ArrayList<>(paragraphs.get(chapter).getValue());
    if (rows.get(index).getTranslation() != null
        && rows.get(index).getTranslation().getStatus() == TranslationStatus.SUCCESS) return;
    Paragraph p = rows.get(index).getParagraph();
    rows.set(
        index,
        new ParagraphItem(
            p,
            new Translation(
                p.getParagraphId(),
                "zh-CN",
                "en",
                p.getSourceHash(),
                "【模拟译文】" + translated[chapter.endsWith("0") ? 0 : 1][index],
                TranslationStatus.SUCCESS,
                "fake",
                "demo-1",
                null,
                System.currentTimeMillis())));
    paragraphs.get(chapter).setValue(rows);
    int success = 0;
    for (ParagraphItem row : rows) if (row.getTranslation() != null) success++;
    publish(data, id, chapter, success == 4 ? TaskState.COMPLETED : TaskState.RUNNING, success);
  }

  private void publish(
      MutableLiveData<TranslationTask> data,
      String id,
      String chapter,
      TaskState state,
      int count) {
    TranslationTask old = data.getValue();
    long now = System.currentTimeMillis();
    TranslationTask task =
        new TranslationTask(
            id,
            chapter,
            "en",
            "zh-CN",
            state,
            4,
            count,
            0,
            old == null ? now : old.getCreatedAt(),
            now,
            null,
            null);
    data.setValue(task);
    latest.get(chapter).setValue(task);
  }

  public LiveData<TranslationTask> observeTask(String id) {
    return tasks.computeIfAbsent(id, k -> new MutableLiveData<>());
  }

  public LiveData<TranslationTask> observeLatestTask(String id, String lang) {
    return latest.containsKey(id) ? latest.get(id) : new MutableLiveData<>();
  }

  public void cancelTask(String id) {
    main.post(
        () -> {
          MutableLiveData<TranslationTask> data = tasks.get(id);
          TranslationTask t = data == null ? null : data.getValue();
          if (t != null && t.getState() == TaskState.RUNNING)
            publish(data, id, t.getChapterId(), TaskState.CANCELLED, t.getSuccessCount());
        });
  }
}
