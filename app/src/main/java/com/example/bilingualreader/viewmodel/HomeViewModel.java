package com.example.bilingualreader.viewmodel;

import android.content.Context;
import android.os.*;
import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.video.*;
import java.util.*;

public final class HomeViewModel extends ViewModel {
  public static final class Summary {
    public final Book book;
    public final String chapter;
    public final VideoProject video;
    public final String warning;

    Summary(Book book, String chapter, VideoProject video, String warning) {
      this.book = book;
      this.chapter = chapter;
      this.video = video;
      this.warning = warning;
    }
  }

  public final MutableLiveData<Summary> summary = new MutableLiveData<>();
  private final AppContainer graph;
  private final VideoProjectStore videos;
  private final Handler main = new Handler(Looper.getMainLooper());
  private List<Book> books = Collections.emptyList();
  private long generation;
  private boolean cleared;

  public HomeViewModel(AppContainer graph, Context context) {
    this.graph = graph;
    videos = new VideoProjectStore(context.getApplicationContext());
  }

  public void books(List<Book> books) {
    this.books = new ArrayList<>(books);
    refresh();
  }

  public void refresh() {
    final long token = ++generation;
    List<Book> snapshot = new ArrayList<>(books);
    graph.io.execute(
        () -> {
          Book latest = null;
          String chapter = "";
          VideoProject video = VideoProject.empty();
          String warning = null;
          for (Book b : snapshot)
            if (b.getLastReadAt() != null
                && (latest == null || b.getLastReadAt() > latest.getLastReadAt())) latest = b;
          if (latest != null)
            try {
              ReadingProgress p = graph.reader.getReadingProgress(latest.getBookId());
              if (p != null) {
                List<Chapter> chapters = graph.books.getChapters(latest.getBookId());
                List<TocEntry> toc = graph.books.getTableOfContents(latest.getBookId());
                for (Chapter c : chapters)
                  if (c.getChapterId().equals(p.getChapterId()))
                    chapter = ReaderSectionTitle.resolve(c, toc);
              }
            } catch (AppException e) {
              chapter = "继续上次阅读";
            }
          try {
            video = videos.load();
          } catch (java.io.IOException e) {
            warning = e.getMessage();
          }
          Summary result = new Summary(latest, chapter, video, warning);
          main.post(
              () -> {
                if (!cleared && token == generation) summary.setValue(result);
              });
        });
  }

  @Override
  protected void onCleared() {
    cleared = true;
    generation++;
  }
}
