package com.example.bilingualreader.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.data.db.LibraryDatabase;
import com.example.bilingualreader.data.repository.LocalLibraryRepository;
import com.example.bilingualreader.di.AppContainer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BookCoverDeviceTest {
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

  private void entry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  private void text(ZipOutputStream zip, String name, String value) throws Exception {
    entry(zip, name, value.getBytes(StandardCharsets.UTF_8));
  }

  private File fixture() throws Exception {
    File file = File.createTempFile("cover-ui-", ".epub", context.getCacheDir());
    Bitmap image = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888);
    image.eraseColor(Color.rgb(40, 100, 145));
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    image.compress(Bitmap.CompressFormat.PNG, 100, png);
    image.recycle();
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
      text(
          zip,
          "META-INF/container.xml",
          "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile"
              + " full-path='OPS/book.opf'/></rootfiles></container>");
      text(
          zip,
          "OPS/book.opf",
          "<package xmlns='http://www.idpf.org/2007/opf'><metadata"
              + " xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Cover test "
              + UUID.randomUUID()
              + "</dc:title></metadata><manifest><item id='text' href='chapter.xhtml'"
              + " media-type='application/xhtml+xml'/></manifest><spine><itemref"
              + " idref='text'/></spine><guide><reference type='cover'"
              + " href='front.xhtml'/></guide></package>");
      text(
          zip,
          "OPS/front.xhtml",
          "<html><body><svg><image xlink:href='art.png'/></svg></body></html>");
      text(
          zip,
          "OPS/chapter.xhtml",
          "<html><body><h1>First chapter</h1><p>Original passage.</p></body></html>");
      entry(zip, "OPS/art.png", png.toByteArray());
    }
    return file;
  }

  @Test
  public void repairExistingBookPreservesChaptersAndProgress() throws Exception {
    LibraryDatabase db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase.class).build();
    LocalLibraryRepository repo = new LocalLibraryRepository(context, db);
    File file = fixture();
    String id = null;
    String repairedPath = null;
    try {
      Book book = repo.importBook(Uri.fromFile(file)).getBook();
      id = book.getBookId();
      Chapter chapter = repo.getChapters(id).get(0);
      LibraryDatabase.ParagraphRow paragraph = db.dao().paragraphs(chapter.getChapterId()).get(1);
      repo.saveReadingProgress(
          new ReadingProgress(
              id, chapter.getChapterId(), paragraph.paragraphId, -12, System.currentTimeMillis()));
      assertNotNull(book.getCoverPath());
      new File(book.getCoverPath()).delete();
      db.dao().updateCover(id, null);
      assertEquals(1, repo.refreshMissingCovers());
      repairedPath = db.dao().book(id).coverPath;
      assertTrue(new File(repairedPath).isFile());
      assertEquals(chapter.getChapterId(), repo.getChapters(id).get(0).getChapterId());
      assertEquals(paragraph.paragraphId, repo.getReadingProgress(id).getParagraphId());
      assertEquals(-12, repo.getReadingProgress(id).getOffsetPx());
      assertEquals(
          "Original passage.", db.dao().paragraphs(chapter.getChapterId()).get(1).originalText);
      assertEquals(0, repo.refreshMissingCovers());
    } finally {
      if (id != null) repo.deleteBook(id);
      if (repairedPath != null) new File(repairedPath).delete();
      file.delete();
      db.close();
    }
  }

  @Test
  public void importedCoverIsRenderedOnBookshelf() throws Exception {
    AppContainer graph = ((ReaderApplication) context.getApplicationContext()).getContainer();
    File file = fixture();
    Book book = graph.books.importBook(Uri.fromFile(file)).getBook();
    ActivityScenario<MainActivity> scenario = null;
    try {
      assertNotNull(book.getCoverPath());
      Chapter chapter = graph.books.getChapters(book.getBookId()).get(0);
      // Most recent reading sorts this test fixture first without changing other books.
      graph.reader.saveReadingProgress(
          new ReadingProgress(
              book.getBookId(), chapter.getChapterId(), "fixture", 0, System.currentTimeMillis()));
      scenario = ActivityScenario.launch(MainActivity.class);
      scenario.onActivity(activity -> activity.openBookshelf(false));
      AtomicBoolean ready = new AtomicBoolean();
      long end = System.currentTimeMillis() + 15000;
      while (!ready.get() && System.currentTimeMillis() < end) {
        scenario.onActivity(
            activity -> {
              RecyclerView shelf = activity.findViewById(R.id.list);
              if (shelf == null || shelf.getChildCount() == 0) return;
              View row = shelf.getChildAt(0);
              if (row.getContentDescription() == null
                  || !row.getContentDescription().toString().contains(book.getTitle())) return;
              ImageView cover = row.findViewById(R.id.media_cover);
              ready.set(
                  cover.getDrawable() != null
                      && row.findViewById(R.id.media_placeholder).getVisibility() == View.GONE);
            });
        Thread.sleep(50);
      }
      assertTrue("The imported cover must replace the title placeholder", ready.get());
    } finally {
      if (scenario != null) scenario.close();
      graph.books.deleteBook(book.getBookId());
      file.delete();
    }
  }
}
