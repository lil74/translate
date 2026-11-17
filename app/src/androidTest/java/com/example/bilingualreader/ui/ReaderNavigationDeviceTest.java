package com.example.bilingualreader.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.ui.bookshelf.BookshelfFragment;
import com.example.bilingualreader.ui.reader.ReaderFragment;
import com.example.bilingualreader.ui.toc.TocFragment;
import com.example.bilingualreader.viewmodel.ReaderViewModel;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real fragment back-stack checks; run against a disposable debug installation. */
@RunWith(AndroidJUnit4.class)
public class ReaderNavigationDeviceTest {
  private ActivityScenario<MainActivity> scenario;
  private AppContainer graph;
  private Book book;
  private List<Chapter> chapters;

  @Before
  public void setup() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    graph = ((ReaderApplication) context.getApplicationContext()).getContainer();
    File file = File.createTempFile("navigation-", ".txt", context.getCacheDir());
    StringBuilder text = new StringBuilder();
    for (int chapter = 1; chapter <= 2; chapter++) {
      text.append("Chapter ").append(chapter).append('\n');
      for (int p = 0; p < 45; p++)
        text.append("A quiet afternoon in the library. Paragraph ").append(p).append('\n');
    }
    text.append(UUID.randomUUID());
    Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
    try {
      book = graph.books.importBook(Uri.fromFile(file)).getBook();
    } finally {
      file.delete();
    }
    chapters = graph.books.getChapters(book.getBookId());
    assertEquals(2, chapters.size());
    AtomicReference<String> savedParagraph = new AtomicReference<>();
    androidx.lifecycle.Observer<List<ParagraphItem>> observer =
        rows -> {
          if (rows != null && !rows.isEmpty())
            savedParagraph.set(rows.get(0).getParagraph().getParagraphId());
        };
    androidx.lifecycle.LiveData<List<ParagraphItem>> paragraphs =
        graph.reader.observeParagraphs(chapters.get(1).getChapterId(), "zh-CN");
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> paragraphs.observeForever(observer));
    try {
      await(() -> savedParagraph.get() != null);
    } finally {
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> paragraphs.removeObserver(observer));
    }
    graph.reader.saveReadingProgress(
        new ReadingProgress(
            book.getBookId(),
            chapters.get(1).getChapterId(),
            savedParagraph.get(),
            0,
            System.currentTimeMillis()));
    scenario = ActivityScenario.launch(MainActivity.class);
  }

  @After
  public void cleanup() throws Exception {
    if (scenario != null) scenario.close();
    if (book != null) graph.books.deleteBook(book.getBookId());
  }

  private interface Condition {
    boolean check();
  }

  private void await(Condition condition) throws Exception {
    long end = System.currentTimeMillis() + 15000;
    while (!condition.check() && System.currentTimeMillis() < end) Thread.sleep(50);
    assertTrue("UI condition timed out", condition.check());
  }

  private Fragment current(MainActivity activity) {
    return activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
  }

  private void awaitPage(Class<? extends Fragment> type, int minimumRows) throws Exception {
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                if (!type.isInstance(current(activity))) return;
                RecyclerView list = activity.findViewById(R.id.list);
                ready.set(
                    list != null
                        && list.getAdapter() != null
                        && list.getAdapter().getItemCount() >= minimumRows
                        && list.getChildCount() > 0);
              });
          return ready.get();
        });
  }

  @Test
  public void homeTilesHaveIdenticalBoundsAndTextBaselines() {
    scenario.onActivity(
        activity -> {
          View left = activity.findViewById(R.id.home_library);
          View right = activity.findViewById(R.id.home_videos);
          assertEquals(left.getTop(), right.getTop());
          assertTrue(left.getHeight() > 0);
          assertEquals(left.getHeight(), right.getHeight());
          assertTrue(Math.abs(left.getWidth() - right.getWidth()) <= 1);
          TextView a = (TextView) ((android.view.ViewGroup) left).getChildAt(1);
          TextView b = (TextView) ((android.view.ViewGroup) right).getChildAt(1);
          assertEquals(a.getTop() + a.getBaseline(), b.getTop() + b.getBaseline());
        });
  }

  @Test
  public void bookResumesAndTocReturnsToSameReaderWithoutExtraPages() throws Exception {
    scenario.onActivity(activity -> activity.findViewById(R.id.home_library).performClick());
    awaitPage(BookshelfFragment.class, 1);
    scenario.onActivity(
        activity -> {
          RecyclerView shelf = activity.findViewById(R.id.list);
          View cover = shelf.getChildAt(0);
          assertTrue(cover.getContentDescription().toString().contains(book.getTitle()));
          cover.performClick();
        });
    awaitPage(ReaderFragment.class, 40);
    AtomicReference<Fragment> original = new AtomicReference<>();
    scenario.onActivity(
        activity -> {
          original.set(current(activity));
          ReaderViewModel vm = new ViewModelProvider(current(activity)).get(ReaderViewModel.class);
          assertEquals(
              chapters.get(1).getChapterId(), vm.location.getValue().data.chapter.getChapterId());
          RecyclerView list = activity.findViewById(R.id.list);
          ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(20, -12);
        });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    AtomicReference<Integer> position = new AtomicReference<>(), offset = new AtomicReference<>();
    scenario.onActivity(
        activity -> {
          RecyclerView list = activity.findViewById(R.id.list);
          LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
          int first = layout.findFirstVisibleItemPosition();
          position.set(first);
          offset.set(
              layout.getDecoratedTop(layout.findViewByPosition(first)) - list.getPaddingTop());
          activity.findViewById(R.id.toc).performClick();
        });
    awaitPage(TocFragment.class, 2);
    scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
    awaitPage(ReaderFragment.class, 40);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    scenario.onActivity(
        activity -> {
          assertSame(original.get(), current(activity));
          RecyclerView list = activity.findViewById(R.id.list);
          LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
          assertEquals(position.get().intValue(), layout.findFirstVisibleItemPosition());
          assertEquals(
              offset.get().intValue(),
              layout.getDecoratedTop(layout.findViewByPosition(position.get()))
                  - list.getPaddingTop());
          activity.findViewById(R.id.toc).performClick();
        });
    awaitPage(TocFragment.class, 2);
    // Configuration recreation must preserve the TOC's return target and pending chapter selection.
    scenario.recreate();
    awaitPage(TocFragment.class, 2);
    scenario.onActivity(
        activity -> ((RecyclerView) activity.findViewById(R.id.list)).getChildAt(0).performClick());
    awaitPage(ReaderFragment.class, 40);
    scenario.onActivity(
        activity -> {
          ReaderViewModel vm = new ViewModelProvider(current(activity)).get(ReaderViewModel.class);
          assertEquals(
              chapters.get(0).getChapterId(), vm.location.getValue().data.chapter.getChapterId());
          assertEquals(2, activity.getSupportFragmentManager().getBackStackEntryCount());
          activity.getOnBackPressedDispatcher().onBackPressed();
        });
    awaitPage(BookshelfFragment.class, 1);
  }
}
