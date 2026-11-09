package com.example.bilingualreader.translation;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.data.db.LibraryDatabase;
import com.example.bilingualreader.data.db.LibraryDatabase.*;
import com.example.bilingualreader.data.repository.LocalLibraryRepository;
import com.example.bilingualreader.di.AppContainer;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Uses temporary test databases/books only. Never clears the installed app's library. */
@RunWith(AndroidJUnit4.class)
public class TranslationPersistenceTest {
  private Context context;
  private LibraryDatabase db;
  private LocalLibraryRepository store;
  @Before public void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    db = Room.inMemoryDatabaseBuilder(context,LibraryDatabase.class).build();
    store = new LocalLibraryRepository(context,db); seed(db);
  }
  @After public void teardown() { db.close(); }
  private static void seed(LibraryDatabase database) {
    BookRow b = new BookRow(); b.bookId="test-book"; b.title="C test"; b.author="test";
    b.language="en"; b.originalFileName="test.epub"; b.localFilePath="/nonexistent-c-test.epub";
    b.chapterCount=1; b.parserVersion=1; b.importedAt=1;
    database.dao().insertBook(b);
    ChapterRow c = new ChapterRow(); c.chapterId="test-chapter"; c.bookId=b.bookId;
    c.title="test"; c.resourcePath="c.xhtml"; c.orderIndex=0; c.paragraphCount=3;
    database.dao().insertChapters(Collections.singletonList(c));
    List<ParagraphRow> ps = new ArrayList<>();
    for(int i=0;i<3;i++) {
      ParagraphRow p=new ParagraphRow();p.paragraphId="p"+i;p.chapterId=c.chapterId;
      p.orderIndex=i;p.blockType="TEXT";p.originalText="Test sentence "+i+".";
      p.sourceHash=TranslationValidation.sha256(p.originalText);ps.add(p);
    }
    database.dao().insertParagraphs(ps);
  }
  private TranslationTask prepare() throws Exception {
    return store.prepareConfiguredTask("test-chapter","en","zh-CN","mock","");
  }
  private static TranslationBatchResult success(TranslationBatchRequest r) {
    List<TranslationOutput> out=new ArrayList<>();
    for(TranslationInput p:r.getItems())out.add(new TranslationOutput(p.getParagraphId(),p.getSourceHash(),
        TranslationStatus.SUCCESS,"测试译文 "+p.getParagraphId(),"test","1",null));
    Collections.reverse(out);
    return new TranslationBatchResult(r.getTaskId(),r.getRequestId(),out);
  }
  private static TranslationBatchResult one(String task,ParagraphRow p) {
    return new TranslationBatchResult(task,"request",Collections.singletonList(new TranslationOutput(
        p.paragraphId,p.sourceHash,TranslationStatus.SUCCESS,"译文","test","1",null)));
  }
  @Test public void roomCachesSuccessAndCountsIdempotently() throws Exception {
    TranslationTask t=prepare();
    new ChapterTranslationRunner(store,TranslationPersistenceTest::success,()->false,System::currentTimeMillis).run(t.getTaskId());
    assertEquals(TaskState.COMPLETED,store.getTask(t.getTaskId()).getState());
    assertEquals(3,store.getTask(t.getTaskId()).getSuccessCount());
    TranslationTask cached=prepare();
    assertEquals(TaskState.COMPLETED,cached.getState());assertTrue(store.getPendingParagraphs(cached.getTaskId()).isEmpty());
  }
  @Test public void duplicateClicksHaveOneActiveTaskAcrossRepositoryInstances() throws Exception {
    LocalLibraryRepository second=new LocalLibraryRepository(context,db);
    ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
    try {
      Future<String> a=pool.submit(()->{gate.await();return prepare().getTaskId();});
      Future<String> b=pool.submit(()->{gate.await();return second.prepareTask("test-chapter","en","zh-CN").getTaskId();});
      gate.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
      assertEquals(1,store.getActiveTasks().size());
    } finally {pool.shutdownNow();}
  }
  @Test public void cancelDuringRequestRejectsLateResponseAndResumes() throws Exception {
    TranslationTask t=prepare();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    ExecutorService pool=Executors.newSingleThreadExecutor();
    try {
      Future<?> pending=pool.submit(()->{
        try {new ChapterTranslationRunner(store,r->{entered.countDown();
          try {release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
          return success(r);},()->false,System::currentTimeMillis).run(t.getTaskId());}
        catch(AppException error){throw new RuntimeException(error);}
      });
      assertTrue(entered.await(10,TimeUnit.SECONDS));store.cancelTask(t.getTaskId());release.countDown();pending.get(10,TimeUnit.SECONDS);
      assertEquals(TaskState.CANCELLED,store.getTask(t.getTaskId()).getState());
      for(ParagraphRow p:db.dao().paragraphs("test-chapter"))assertEquals("PENDING",db.dao().translation(p.paragraphId,"zh-CN").status);
      TranslationTask next=prepare();assertNotEquals(t.getTaskId(),next.getTaskId());
      new ChapterTranslationRunner(store,TranslationPersistenceTest::success,()->false,System::currentTimeMillis).run(next.getTaskId());
      assertEquals(3,store.getTask(next.getTaskId()).getSuccessCount());
    } finally {release.countDown();pool.shutdownNow();}
  }
  @Test public void deletingBookRejectsLateWritesAndCascadesAttempts() throws Exception {
    TranslationTask t=prepare();store.claimAttempt(t.getTaskId(),"attempt",0);
    ParagraphRow p=db.dao().paragraphs("test-chapter").get(0);
    store.deleteBook("test-book");store.applyBatchResult(t.getTaskId(),one(t.getTaskId(),p));
    assertNull(store.getTask(t.getTaskId()));assertNull(db.dao().translation(p.paragraphId,"zh-CN"));
    assertNull(db.dao().request("attempt"));
  }
  @Test public void sourceHashMismatchRollsBackWholeBatch() throws Exception {
    TranslationTask t=prepare();ParagraphRow p=db.dao().paragraphs("test-chapter").get(0);
    db.getOpenHelper().getWritableDatabase().execSQL("UPDATE paragraphs SET sourceHash='changed' WHERE paragraphId='p0'");
    try {store.applyBatchResult(t.getTaskId(),one(t.getTaskId(),p));fail("stale source must fail");}
    catch(AppException expected){assertEquals(ErrorCode.INVALID_RESPONSE,expected.getCode());}
    assertEquals("PENDING",db.dao().translation("p0","zh-CN").status);
  }
  @Test public void emptyChapterCompletesWithoutNetwork() throws Exception {
    ChapterRow c=new ChapterRow();c.chapterId="empty";c.bookId="test-book";c.title="empty";
    c.resourcePath="empty.xhtml";c.orderIndex=1;c.paragraphCount=0;db.dao().insertChapters(Collections.singletonList(c));
    TranslationTask task=store.prepareTask(c.chapterId,"en","zh-CN");
    assertEquals(TaskState.COMPLETED,task.getState());assertEquals(0,task.getTotalCount());
  }
  @Test public void durableBudgetAndServiceSnapshotSurviveDatabaseReopen() throws Exception {
    String name="c-retry-"+UUID.randomUUID()+".db";
    LibraryDatabase disk=Room.databaseBuilder(context,LibraryDatabase.class,name).build();
    try {
      seed(disk);LocalLibraryRepository first=new LocalLibraryRepository(context,disk);
      TranslationTask t=first.prepareConfiguredTask("test-chapter","en","zh-CN","mock","");
      assertTrue(first.claimAttempt(t.getTaskId(),"attempt",0));
      first.postponeAttempt(t.getTaskId(),"attempt",100,"TIMEOUT","wait");disk.close();
      disk=Room.databaseBuilder(context,LibraryDatabase.class,name).build();
      LocalLibraryRepository reopened=new LocalLibraryRepository(context,disk);
      assertEquals("mock",reopened.getService(t.getTaskId())[0]);
      assertFalse(reopened.claimAttempt(t.getTaskId(),"attempt",99));
      assertTrue(reopened.claimAttempt(t.getTaskId(),"attempt",100));
      assertTrue(reopened.claimAttempt(t.getTaskId(),"attempt",101));
      assertFalse(reopened.claimAttempt(t.getTaskId(),"attempt",102));
      assertEquals(3,reopened.getAttempt("attempt").count);
    } finally {disk.close();context.deleteDatabase(name);}
  }
  @Test public void schemaOneUpgradePreservesBooksProgressTasksAndTranslations() throws Exception {
    String name="c-migration-"+UUID.randomUUID()+".db";
    LibraryDatabase disk=Room.databaseBuilder(context,LibraryDatabase.class,name).build();
    try {
      seed(disk);LocalLibraryRepository first=new LocalLibraryRepository(context,disk);
      TranslationTask task=first.prepareTask("test-chapter","en","zh-CN");
      first.applyBatchResult(task.getTaskId(),one(task.getTaskId(),disk.dao().paragraphs("test-chapter").get(0)));
      first.saveReadingProgress(new ReadingProgress("test-book","test-chapter","p0",-20,10));disk.close();
      // Reconstruct the previous production task schema; all other v1 tables are unchanged.
      SQLiteDatabase old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).getPath(),null,SQLiteDatabase.OPEN_READWRITE);
      old.execSQL("DROP TABLE translation_requests");old.execSQL("ALTER TABLE tasks RENAME TO tasks_new");
      old.execSQL("CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY, chapterId TEXT NOT NULL, sourceLanguage TEXT NOT NULL, targetLanguage TEXT NOT NULL, state TEXT NOT NULL, totalCount INTEGER NOT NULL, successCount INTEGER NOT NULL, failedCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, errorCode TEXT, errorMessage TEXT, FOREIGN KEY(chapterId) REFERENCES chapters(chapterId) ON UPDATE NO ACTION ON DELETE CASCADE)");
      old.execSQL("INSERT INTO tasks SELECT taskId,chapterId,sourceLanguage,targetLanguage,state,totalCount,successCount,failedCount,createdAt,updatedAt,errorCode,errorMessage FROM tasks_new");
      old.execSQL("DROP TABLE tasks_new");old.execSQL("CREATE INDEX index_tasks_chapterId ON tasks(chapterId)");
      old.execSQL("DROP TABLE room_master_table");old.setVersion(1);old.close();
      disk=Room.databaseBuilder(context,LibraryDatabase.class,name).addMigrations(LibraryDatabase.MIGRATION_1_2).build();
      LocalLibraryRepository upgraded=new LocalLibraryRepository(context,disk);
      assertEquals("C test",disk.dao().book("test-book").title);
      assertEquals(-20,upgraded.getReadingProgress("test-book").getOffsetPx());
      assertEquals("SUCCESS",disk.dao().translation("p0","zh-CN").status);
      assertEquals(task.getTaskId(),upgraded.getActiveTasks().get(0).getTaskId());
      assertEquals("",upgraded.getService(task.getTaskId())[0]);
    } finally {disk.close();context.deleteDatabase(name);}
  }
  @Test public void workManagerTranslatesAnImportedBookAndCacheAvoidsSecondRequest() throws Exception {
    AppContainer graph=((ReaderApplication)context.getApplicationContext()).getContainer();
    String oldMode=graph.translationConfig.mode(),oldEndpoint=graph.translationConfig.endpoint();
    File epub=File.createTempFile("c-worker-test-",".epub",context.getCacheDir());
    String bookId=null;
    try {
      String id=UUID.randomUUID().toString();
      try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(epub))) {
        entry(zip,"META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='content.opf'/></rootfiles></container>");
        entry(zip,"content.opf","<package xmlns='http://www.idpf.org/2007/opf' xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>C Worker Test "+id+"</dc:title><dc:language>en</dc:language></metadata><manifest><item id='c' href='c.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='c'/></spine></package>");
        entry(zip,"c.xhtml","<html><body><h1>Test</h1><p>This is a test sentence.</p><p>Another test paragraph.</p></body></html>");
      }
      bookId=graph.books.importBook(android.net.Uri.fromFile(epub)).getBook().getBookId();
      Chapter chapter=graph.books.getChapters(bookId).get(0);
      graph.translationConfig.save("mock","");
      String task=graph.translation.startOrResumeChapter(chapter.getChapterId(),"en","zh-CN");
      long until=System.currentTimeMillis()+30_000;
      TranslationTask snapshot;
      do {snapshot=graph.translationStore.getTask(task);
        if(snapshot.getState()==TaskState.COMPLETED)break;Thread.sleep(100);
      }while(System.currentTimeMillis()<until);
      assertEquals(TaskState.COMPLETED,snapshot.getState());assertEquals(3,snapshot.getSuccessCount());
      String cached=graph.translation.startOrResumeChapter(chapter.getChapterId(),"en","zh-CN");
      assertEquals(TaskState.COMPLETED,graph.translationStore.getTask(cached).getState());
      assertTrue(graph.translationStore.getPendingParagraphs(cached).isEmpty());
    } finally {
      if(bookId!=null)graph.books.deleteBook(bookId);
      // Release-like unconfigured online settings are allowed before a URL has been supplied.
      if("online".equals(oldMode)&&oldEndpoint.isEmpty()) {
        context.getSharedPreferences("translation_settings",Context.MODE_PRIVATE).edit()
            .putString("mode",oldMode).putString("endpoint",oldEndpoint).commit();
      } else graph.translationConfig.save(oldMode,oldEndpoint);
      epub.delete();
    }
  }
  private static void entry(ZipOutputStream zip,String name,String value)throws IOException {
    zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
  }
}
