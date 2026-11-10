package com.example.bilingualreader.translation;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.data.db.LibraryDatabase;
import com.example.bilingualreader.di.AppContainer;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Opt-in real localhost engine smoke test. Leaves a tiny original test book on success. */
@RunWith(AndroidJUnit4.class)
public class RealTranslationSmokeTest {
  @Test public void importTranslatePersistAndReadFromFreshDatabase() throws Exception {
    Assume.assumeTrue("Explicit opt-in required for real engine requests",
        "true".equals(InstrumentationRegistry.getArguments().getString("runRealTranslation")));
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    AppContainer graph=((ReaderApplication)context.getApplicationContext()).getContainer();
    String endpoint="http://127.0.0.1:5000/translate";
    String oldMode=graph.translationConfig.mode(), oldEndpoint=graph.translationConfig.endpoint();
    SharedPreferences prefs=context.getSharedPreferences("translation_settings",Context.MODE_PRIVATE);
    String oldConsent=prefs.getString("consentedEndpoint",null);
    File epub=File.createTempFile("real-translation-",".epub",context.getCacheDir());
    String bookId=null,taskId=null;boolean passed=false;
    try {
      String title="真实翻译联通测试 "+UUID.randomUUID().toString().substring(0,8);
      try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(epub))) {
        entry(zip,"META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='content.opf'/></rootfiles></container>");
        entry(zip,"content.opf","<package xmlns='http://www.idpf.org/2007/opf' xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>"+title+"</dc:title><dc:language>en</dc:language></metadata><manifest><item id='c' href='c.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='c'/></spine></package>");
        entry(zip,"c.xhtml","<html><body><h1>A new day</h1><p>Hello, world!</p><p>I enjoy reading books.</p></body></html>");
      }
      bookId=graph.books.importBook(Uri.fromFile(epub)).getBook().getBookId();
      Chapter chapter=graph.books.getChapters(bookId).get(0);
      graph.translationConfig.save("online",endpoint);
      // Consent is solely for this original synthetic fixture, restored below for user books.
      graph.translationConfig.consent(endpoint);
      taskId=graph.translation.startOrResumeChapter(chapter.getChapterId(),"en","zh-CN");
      long until=System.currentTimeMillis()+180000;
      TranslationTask task;
      do {
        task=graph.translationStore.getTask(taskId);
        if(task.getState()==TaskState.COMPLETED || task.getState()==TaskState.FAILED
            || task.getState()==TaskState.PARTIAL_FAILED)break;
        Thread.sleep(100);
      }while(System.currentTimeMillis()<until);
      assertEquals(task.getErrorMessage(),TaskState.COMPLETED,task.getState());
      assertEquals(3,task.getSuccessCount());
      // Independent disk-backed Room handle proves results are not only in the UI/in memory.
      LibraryDatabase reopened=Room.databaseBuilder(context,LibraryDatabase.class,"library.db")
          .addMigrations(LibraryDatabase.MIGRATION_1_2).build();
      try {
        for(LibraryDatabase.ParagraphRow p:reopened.dao().paragraphs(chapter.getChapterId())) {
          LibraryDatabase.TranslationRow t=reopened.dao().translation(p.paragraphId,"zh-CN");
          assertNotNull(t);assertEquals("SUCCESS",t.status);assertEquals("libretranslate",t.provider);
          assertEquals(p.sourceHash,t.sourceHash);assertNotNull(t.translatedText);
          assertFalse(t.translatedText.contains("模拟"));
          assertTrue("Expected real Chinese output",t.translatedText.matches("(?s).*[\\p{IsHan}].*"));
        }
      } finally {reopened.close();}
      String cached=graph.translation.startOrResumeChapter(chapter.getChapterId(),"en","zh-CN");
      assertEquals(TaskState.COMPLETED,graph.translationStore.getTask(cached).getState());
      assertTrue(graph.translationStore.getPendingParagraphs(cached).isEmpty());
      Bundle evidence=new Bundle();evidence.putString("bookId",bookId);
      evidence.putString("chapterId",chapter.getChapterId());evidence.putString("taskId",taskId);
      evidence.putString("endpoint",endpoint);
      InstrumentationRegistry.getInstrumentation().sendStatus(2,evidence);
      passed=true;
    } finally {
      if(!passed) {
        if(taskId!=null)graph.translation.cancelTask(taskId);
        if(bookId!=null)graph.books.deleteBook(bookId);
        prefs.edit().putString("mode",oldMode).putString("endpoint",oldEndpoint).commit();
      }
      SharedPreferences.Editor edit=prefs.edit();
      if(oldConsent==null)edit.remove("consentedEndpoint");else edit.putString("consentedEndpoint",oldConsent);
      edit.commit();epub.delete();
    }
  }
  private static void entry(ZipOutputStream zip,String name,String value)throws IOException {
    zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
  }
}
