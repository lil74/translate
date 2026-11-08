package com.example.bilingualreader;

import android.app.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.*;
import androidx.recyclerview.widget.RecyclerView;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.di.*;
import com.example.bilingualreader.ui.MainActivity;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Dependency-free device integration checks for A. Run only on a disposable Debug installation. */
public final class AIntegrationRunner extends Instrumentation {
  private final List<String> passed = new ArrayList<>();
  private final List<Runnable> cleanup = new ArrayList<>();
  private MainActivity activity;
  private AppContainer graph;
  private boolean resumeOnly;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    resumeOnly = "true".equals(args.getString("resume"));
    start();
  }

  private interface Check {
    boolean done() throws Exception;
  }

  private void waitFor(Check check, String label) throws Exception {
    long end = System.currentTimeMillis() + 20000;
    while (System.currentTimeMillis() < end) {
      if (check.done()) return;
      Thread.sleep(100);
    }
    throw new AssertionError(label);
  }

  private void main(Runnable r) {
    AtomicReference<Throwable> error = new AtomicReference<>();
    runOnMainSync(
        () -> {
          try {
            r.run();
          } catch (Throwable e) {
            error.set(e);
          }
        });
    if (error.get() != null) throw new AssertionError(error.get());
  }

  private <T> AtomicReference<T> watch(LiveData<T> data) {
    AtomicReference<T> ref = new AtomicReference<>();
    androidx.lifecycle.Observer<T> observer = ref::set;
    main(() -> data.observeForever(observer));
    cleanup.add(() -> data.removeObserver(observer));
    return ref;
  }

  private String text(int id) {
    AtomicReference<String> s = new AtomicReference<>("");
    main(
        () -> {
          TextView v = activity.findViewById(id);
          if (v != null) s.set(v.getText().toString());
        });
    return s.get();
  }

  private void tap(int id) {
    main(
        () -> {
          View v = activity.findViewById(id);
          if (v == null || !v.isEnabled()) throw new AssertionError("Button unavailable: " + id);
          v.performClick();
        });
  }

  private void ok(String label) {
    passed.add(label);
    Bundle b = new Bundle();
    b.putString("stream", "PASS: " + label + "\n");
    sendStatus(0, b);
  }

  private void screenshot(String name) throws Exception {
    Thread.sleep(250);
    Bitmap b = getUiAutomation().takeScreenshot();
    if (b == null) throw new AssertionError("Screenshot unavailable");
    File dir = new File(getTargetContext().getFilesDir(), "a-ui");
    dir.mkdirs();
    try (OutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      b.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    b.recycle();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      ReaderApplication application =
          (ReaderApplication) getTargetContext().getApplicationContext();
      // Unlike AndroidJUnitRunner, this minimal framework runner initializes the target app itself.
      if (application.getContainer() == null) main(() -> callApplicationOnCreate(application));
      graph = application.getContainer();
      AtomicReference<List<Book>> library = watch(graph.books.observeBooks());
      waitFor(() -> library.get() != null, "Book query did not complete");
      Book book;
      if (resumeOnly) {
        book =
            library.get().stream()
                .filter(b -> b.getOriginalFileName().contains("two-chapters"))
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("Imported book missing after process restart"));
        ReadingProgress progress = graph.reader.getReadingProgress(book.getBookId());
        if (progress == null
            || !progress
                .getChapterId()
                .equals(graph.books.getChapters(book.getBookId()).get(1).getChapterId()))
          throw new AssertionError("A did not save second-chapter position");
        ok("Real book and A-saved reading progress survive process restart");
      } else {
        File sample = new File(getTargetContext().getCacheDir(), "two-chapters.epub");
        try (InputStream in = getContext().getAssets().open("two-chapters.epub");
            OutputStream out = new FileOutputStream(sample)) {
          byte[] buf = new byte[8192];
          int n;
          while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        book = graph.books.importBook(Uri.fromFile(sample)).getBook();
        if (!graph.books.importBook(Uri.fromFile(sample)).isAlreadyExists())
          throw new AssertionError("Import deduplication failed");
        // Reset only this test fixture so reruns can exercise failure/retry without old caches.
        graph.books.deleteBook(book.getBookId());
        book=graph.books.importBook(Uri.fromFile(sample)).getBook();
        ok("Real EPUB import, duplicate import, and Room-backed bookshelf");
      }
      List<Chapter> chapters = graph.books.getChapters(book.getBookId());
      List<TocEntry> toc = graph.books.getTableOfContents(book.getBookId());
      if (chapters.size() != 2 || toc.size() != 3)
        throw new AssertionError("Unexpected fixture structure");
      activity =
          (MainActivity)
              startActivitySync(
                  new android.content.Intent(getTargetContext(), MainActivity.class)
                      .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
      waitFor(
          () -> {
            AtomicBoolean ready = new AtomicBoolean();
            main(
                () -> {
                  RecyclerView list = activity.findViewById(R.id.list);
                  ready.set(
                      list != null
                          && list.getAdapter() != null
                          && list.getAdapter().getItemCount() > 0);
                });
            return ready.get();
          },
          "Bookshelf empty after import");
      screenshot("01-bookshelf");
      Book selected = book;
      main(() -> activity.openToc(selected.getBookId(), selected.getTitle()));
      waitFor(() -> text(R.id.status).contains("3 个"), "Real TOC did not load");
      main(
          () -> {
            RecyclerView list = activity.findViewById(R.id.list);
            View row = list.getChildAt(0);
            if (row == null || row.findViewById(R.id.toc_title) == null)
              throw new AssertionError("Simple TOC row not rendered");
          });
      screenshot("02-simple-toc");
      ok("Real TOC uses plain rows with hierarchy and no chapter cards");
      if (resumeOnly) {
        tap(R.id.continue_reading);
        waitFor(
            () -> text(R.id.chapter_title).equals(chapters.get(1).getTitle()),
            "Continue did not restore chapter");
        ok("Continue opens saved chapter after process restart");
      } else {
        // Select the first actual TOC entry through its displayed row.
        main(() -> ((RecyclerView) activity.findViewById(R.id.list)).getChildAt(0).performClick());
        waitFor(
            () -> text(R.id.chapter_title).equals(chapters.get(0).getTitle()),
            "Reader did not open");
        waitFor(
            () -> {
              AtomicBoolean ready = new AtomicBoolean();
              main(
                  () -> {
                    RecyclerView v = activity.findViewById(R.id.list);
                    ready.set(v.getAdapter().getItemCount() == 4);
                  });
              return ready.get();
            },
            "Paragraphs missing");
        screenshot("03-reader");
        exerciseSelection();
        AtomicReference<List<ParagraphItem>> untouched=watch(graph.reader.observeParagraphs(chapters.get(0).getChapterId(),"zh-CN"));
        waitFor(()->untouched.get()!=null,"Could not inspect chapter after selection lookup");
        if(untouched.get().stream().anyMatch(p->p.getTranslation()!=null))throw new AssertionError("Temporary lookup wrote chapter translations");
        ok("Temporary selection lookups do not write chapter translations");
        ok("Real original paragraphs are displayed in chapter order");
        ((TranslationDiagnostics) graph.translation).failNextRequest();
        tap(R.id.translate);
        waitFor(() -> text(R.id.task_status).contains("模拟连接失败"), "Failure state not shown");
        if (!text(R.id.translate).contains("重试")) throw new AssertionError("Retry entry missing");
        screenshot("04-retry");
        ok("Failed task shows reason and a retry entry");
        tap(R.id.translate);
        waitFor(() -> text(R.id.task_status).contains("本章翻译完成"), "Retry did not complete");
        AtomicReference<List<ParagraphItem>> rows =
            watch(graph.reader.observeParagraphs(chapters.get(0).getChapterId(), "zh-CN"));
        waitFor(
            () ->
                rows.get() != null
                    && rows.get().stream()
                        .allMatch(
                            p ->
                                p.getTranslation() != null
                                    && p.getTranslation().getStatus() == TranslationStatus.SUCCESS),
            "Translations not persisted");
        for (ParagraphItem row : rows.get())
          if (!row.getParagraph().getParagraphId().equals(row.getTranslation().getParagraphId())
              || !row.getTranslation().getTranslatedText().contains("模拟译文"))
            throw new AssertionError("Translation ID or labelling mismatch");
        screenshot("05-bilingual");
        ok("Retry uses the same paragraph IDs and persisted labelled fake results");
        tap(R.id.next);
        waitFor(
            () -> text(R.id.chapter_title).equals(chapters.get(1).getTitle()),
            "Next chapter missing");
        waitFor(() -> text(R.id.task_status).contains("未翻译"), "Old task leaked to new chapter");
        tap(R.id.translate);
        waitFor(() -> text(R.id.task_status).contains("正在翻译"), "Task did not run");
        tap(R.id.cancel);
        waitFor(() -> text(R.id.task_status).contains("已取消"), "Cancel state missing");
        ok("Chapter state isolation and explicit cancellation");
        tap(R.id.translate);
        waitFor(() -> text(R.id.task_status).contains("本章翻译完成"), "Resume did not complete");
        tap(R.id.toc);
        waitFor(() -> text(R.id.status).contains("3 个"), "Return to TOC failed");
        waitFor(
            () -> {
              ReadingProgress p = graph.reader.getReadingProgress(selected.getBookId());
              return p != null && p.getChapterId().equals(chapters.get(1).getChapterId());
            },
            "A did not save progress");
        ok("Return saves visible paragraph and second-chapter reading progress");
        main(
            () ->
                activity.onOptionsItemSelected(
                    new PopupMenu(activity, activity.findViewById(R.id.root))
                        .getMenu()
                        .add(0, 1, 0, "阅读设置")));
        waitFor(
            () -> {
              AtomicBoolean ready = new AtomicBoolean();
              main(() -> ready.set(activity.findViewById(R.id.font) != null));
              return ready.get();
            },
            "Settings missing");
        main(
            () -> {
              ((SeekBar) activity.findViewById(R.id.font)).setProgress(10);
              ((SeekBar) activity.findViewById(R.id.spacing)).setProgress(6);
            });
        tap(R.id.save);
        AtomicReference<ReaderSettings> settings = watch(graph.settings.observeSettings());
        waitFor(
            () ->
                settings.get() != null
                    && settings.get().getFontSizeSp() == 24
                    && Math.abs(settings.get().getLineSpacingMultiplier() - 1.8f) < .01f,
            "A did not persist settings");
        screenshot("06-settings");
        ok("Settings UI saves font size and line spacing");
      }
      result.putString("stream", String.join("\n", passed) + "\nALL PASSED\n");
      result.putInt("passed", passed.size());
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      result.putString("stream", "FAILED: " + e + "\n" + android.util.Log.getStackTraceString(e));
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      for (Runnable r : cleanup) runOnMainSync(r);
    }
  }

  private com.example.bilingualreader.contract.translation.TranslationBatchResult reply(
      com.example.bilingualreader.contract.translation.TranslationBatchRequest request,
      String value) {
    com.example.bilingualreader.contract.translation.TranslationInput input =
        request.getItems().get(0);
    return new com.example.bilingualreader.contract.translation.TranslationBatchResult(
        request.getTaskId(),
        request.getRequestId(),
        Collections.singletonList(
            new com.example.bilingualreader.contract.translation.TranslationOutput(
                input.getParagraphId(),
                input.getSourceHash(),
                TranslationStatus.SUCCESS,
                value,
                "test",
                null,
                null)));
  }

  private void exerciseSelection() throws Exception {
    for (String selected : new String[] {"small", "A small beginning"}) {
      AtomicReference<ActionMode> mode = new AtomicReference<>();
      int[] touch=new int[2];
      main(
          () -> {
            TextView original = activity.findViewById(R.id.original);
            ActionMode.Callback delegate = original.getCustomSelectionActionModeCallback();
            original.setCustomSelectionActionModeCallback(
                new ActionMode.Callback() {
                  public boolean onCreateActionMode(ActionMode m, Menu menu) {
                    mode.set(m);
                    return delegate.onCreateActionMode(m, menu);
                  }

                  public boolean onPrepareActionMode(ActionMode m, Menu menu) {
                    return delegate.onPrepareActionMode(m, menu);
                  }

                  public boolean onActionItemClicked(ActionMode m, MenuItem item) {
                    return delegate.onActionItemClicked(m, item);
                  }

                  public void onDestroyActionMode(ActionMode m) {
                    delegate.onDestroyActionMode(m);
                  }
                });
            original.getLocationOnScreen(touch);
            android.text.Layout layout=original.getLayout();
            int offset=Math.max(0,original.getText().toString().indexOf("small"));
            int line=layout.getLineForOffset(offset);
            touch[0]+=original.getTotalPaddingLeft()+Math.round(layout.getPrimaryHorizontal(offset+1));
            touch[1]+=original.getTotalPaddingTop()+(layout.getLineTop(line)+layout.getLineBottom(line))/2;
          });
      // Editor starts text selection from touch coordinates, not View.performLongClick().
      shell("input tap "+touch[0]+" "+touch[1]);
      shell("input swipe "+touch[0]+" "+touch[1]+" "+touch[0]+" "+touch[1]+" 1000");
      waitFor(() -> mode.get() != null, "Native text selection action mode did not open");
      main(
          () -> {
            TextView original = activity.findViewById(R.id.original);
            int start = original.getText().toString().indexOf(selected);
            if (start < 0) throw new AssertionError("Fixture selection missing");
            android.text.Selection.setSelection(
                (android.text.Spannable) original.getText(), start, start + selected.length());
            if (!mode.get().getMenu().performIdentifierAction(R.id.translate_selection, 0))
              throw new AssertionError("Translate selection menu unavailable");
          });
      AtomicReference<android.view.View> sheet = new AtomicReference<>();
      waitFor(
          () -> {
            main(
                () -> {
                  com.example.bilingualreader.ui.reader.ReaderFragment reader =
                      (com.example.bilingualreader.ui.reader.ReaderFragment)
                          activity
                              .getSupportFragmentManager()
                              .findFragmentById(R.id.fragment_container);
                  androidx.fragment.app.Fragment f =
                      reader
                          .getChildFragmentManager()
                          .findFragmentByTag(
                              com.example.bilingualreader.ui.reader.SelectionTranslationSheet.TAG);
                  if (f != null) sheet.set(f.getView());
                });
            return sheet.get() != null;
          },
          "Bottom sheet did not open");
      waitFor(
          () -> {
            AtomicBoolean ready = new AtomicBoolean();
            main(
                () ->
                    ready.set(
                        ((TextView) sheet.get().findViewById(R.id.selection_result))
                            .getText()
                            .toString()
                            .contains("模拟译文")));
            return ready.get();
          },
          "Selection did not translate");
      main(
          () -> {
            if (!selected.contentEquals(
                ((TextView) sheet.get().findViewById(R.id.selection_source)).getText()))
              throw new AssertionError("Selected text mismatch");
          });
      screenshot(selected.contains(" ") ? "08-selection-sentence" : "07-selection-word");
      main(() -> sheet.get().findViewById(R.id.selection_close).performClick());
      waitFor(
          () -> {
            AtomicBoolean closed = new AtomicBoolean();
            main(
                () -> {
                  androidx.fragment.app.Fragment parent =
                      activity
                          .getSupportFragmentManager()
                          .findFragmentById(R.id.fragment_container);
                  closed.set(
                      parent
                              .getChildFragmentManager()
                              .findFragmentByTag(
                                  com.example.bilingualreader.ui.reader.SelectionTranslationSheet
                                      .TAG)
                          == null);
                });
            return closed.get();
          },
          "Bottom sheet did not close");
    }
    ok("Native same-paragraph word and sentence selection opens matching bottom-sheet results");
    AtomicInteger attempts = new AtomicInteger();
    com.example.bilingualreader.viewmodel.SelectionTranslationViewModel retry =
        new com.example.bilingualreader.viewmodel.SelectionTranslationViewModel(
            request -> {
              if (attempts.getAndIncrement() == 0)
                throw new AppException(ErrorCode.NETWORK_ERROR, "test failure", true);
              return reply(request, "recovered");
            });
    AtomicReference<com.example.bilingualreader.viewmodel.UiState<String>> state =
        watch(retry.result);
    main(() -> retry.translate("small"));
    waitFor(
        () -> state.get().status == com.example.bilingualreader.viewmodel.UiState.Status.ERROR,
        "Selection error missing");
    main(() -> retry.translate("small"));
    waitFor(() -> "recovered".equals(state.get().data), "Selection retry failed");
    main(retry::cancel);
    ok("Selection query reports errors and supports a fresh retry");
    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1),
        release = new java.util.concurrent.CountDownLatch(1);
    com.example.bilingualreader.viewmodel.SelectionTranslationViewModel race =
        new com.example.bilingualreader.viewmodel.SelectionTranslationViewModel(
            request -> {
              String value = request.getItems().get(0).getText();
              if (value.equals("old")) {
                started.countDown();
                while (release.getCount() > 0) {
                  try {
                    release.await();
                  } catch (InterruptedException ignored) {
                  }
                }
              }
              return reply(request, value);
            });
    AtomicReference<com.example.bilingualreader.viewmodel.UiState<String>> latest =
        watch(race.result);
    main(() -> race.translate("old"));
    if (!started.await(5, java.util.concurrent.TimeUnit.SECONDS))
      throw new AssertionError("Old query did not start");
    main(() -> race.translate("new"));
    release.countDown();
    waitFor(() -> "new".equals(latest.get().data), "Late old query overwrote latest selection");
    main(race::cancel);
    ok("Late cancelled selection response cannot overwrite a newer query");
  }

  private void shell(String command)throws Exception {
    android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
    try(InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){
      byte[] buffer=new byte[1024];while(input.read(buffer)!=-1){}
    }
  }
}
