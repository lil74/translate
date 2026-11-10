package com.example.bilingualreader.data.db;

import androidx.room.*;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import java.util.List;

@Database(entities={LibraryDatabase.BookRow.class,LibraryDatabase.ChapterRow.class,
    LibraryDatabase.ParagraphRow.class,LibraryDatabase.TocRow.class,
    LibraryDatabase.ProgressRow.class,LibraryDatabase.TranslationRow.class,
    LibraryDatabase.TaskRow.class,LibraryDatabase.RequestRow.class},version=2,exportSchema=false)
public abstract class LibraryDatabase extends RoomDatabase {
  public abstract DaoAccess dao();

  public static final androidx.room.migration.Migration MIGRATION_1_2 =
      new androidx.room.migration.Migration(1, 2) {
        @Override public void migrate(@NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
          db.execSQL("ALTER TABLE tasks ADD COLUMN executionMode TEXT NOT NULL DEFAULT ''");
          db.execSQL("ALTER TABLE tasks ADD COLUMN endpoint TEXT NOT NULL DEFAULT ''");
          db.execSQL("CREATE TABLE IF NOT EXISTS translation_requests (requestId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, attempts INTEGER NOT NULL, notBefore INTEGER NOT NULL, errorCode TEXT, message TEXT, FOREIGN KEY(taskId) REFERENCES tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE)");
          db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_requests_taskId ON translation_requests(taskId)");
        }
      };

  @Entity(tableName="books") public static class BookRow {
    @PrimaryKey @NonNull public String bookId;
    @NonNull public String title,author,language,originalFileName,localFilePath;
    public String coverPath; public int chapterCount,parserVersion;
    public long importedAt; public Long lastReadAt;
  }
  @Entity(tableName="chapters",foreignKeys=@ForeignKey(entity=BookRow.class,parentColumns="bookId",childColumns="bookId",onDelete=ForeignKey.CASCADE),indices={@Index("bookId"),@Index(value={"bookId","orderIndex"},unique=true)})
  public static class ChapterRow {@PrimaryKey @NonNull public String chapterId; @NonNull public String bookId,title,resourcePath; public int orderIndex,paragraphCount;}
  @Entity(tableName="paragraphs",foreignKeys=@ForeignKey(entity=ChapterRow.class,parentColumns="chapterId",childColumns="chapterId",onDelete=ForeignKey.CASCADE),indices={@Index("chapterId"),@Index(value={"chapterId","orderIndex"},unique=true)})
  public static class ParagraphRow {@PrimaryKey @NonNull public String paragraphId; @NonNull public String chapterId,blockType,originalText,sourceHash; public int orderIndex;}
  @Entity(tableName="toc",foreignKeys=@ForeignKey(entity=BookRow.class,parentColumns="bookId",childColumns="bookId",onDelete=ForeignKey.CASCADE),indices={@Index("bookId"),@Index("chapterId")})
  public static class TocRow {@PrimaryKey @NonNull public String tocId; @NonNull public String bookId,title,chapterId; public String parentTocId,paragraphId; public int orderIndex;}
  @Entity(tableName="progress",foreignKeys=@ForeignKey(entity=BookRow.class,parentColumns="bookId",childColumns="bookId",onDelete=ForeignKey.CASCADE),indices={@Index("chapterId"),@Index("paragraphId")})
  public static class ProgressRow {@PrimaryKey @NonNull public String bookId; @NonNull public String chapterId,paragraphId; public int offsetPx; public long updatedAt;}
  @Entity(tableName="translations",primaryKeys={"paragraphId","targetLanguage"},foreignKeys=@ForeignKey(entity=ParagraphRow.class,parentColumns="paragraphId",childColumns="paragraphId",onDelete=ForeignKey.CASCADE),indices=@Index("paragraphId"))
  public static class TranslationRow {@NonNull public String paragraphId,targetLanguage,sourceLanguage,sourceHash,status; public String translatedText,provider,engineVersion,errorCode; public long updatedAt;}
  @Entity(tableName="tasks",foreignKeys=@ForeignKey(entity=ChapterRow.class,parentColumns="chapterId",childColumns="chapterId",onDelete=ForeignKey.CASCADE),indices=@Index("chapterId"))
  public static class TaskRow {
    @PrimaryKey @NonNull public String taskId;
    @NonNull public String chapterId,sourceLanguage,targetLanguage,state;
    public int totalCount,successCount,failedCount;
    public long createdAt,updatedAt;
    public String errorCode,errorMessage;
    @NonNull @ColumnInfo(defaultValue="''") public String executionMode = "";
    @NonNull @ColumnInfo(defaultValue="''") public String endpoint = "";
  }
  @Entity(tableName="translation_requests",foreignKeys=@ForeignKey(entity=TaskRow.class,
      parentColumns="taskId",childColumns="taskId",onDelete=ForeignKey.CASCADE),indices=@Index("taskId"))
  public static class RequestRow {
    @PrimaryKey @NonNull public String requestId;
    @NonNull public String taskId;
    public int attempts;
    public long notBefore;
    public String errorCode,message;
  }

  @androidx.room.Dao public interface DaoAccess {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastReadAt,0) DESC, importedAt DESC, bookId ASC") LiveData<List<BookRow>> observeBooks();
    @Query("SELECT * FROM books WHERE bookId=:id") BookRow book(String id);
    @Query("SELECT * FROM chapters WHERE chapterId=:id") ChapterRow chapter(String id);
    @Query("SELECT localFilePath FROM books") List<String> bookPaths();
    @Query("SELECT coverPath FROM books WHERE coverPath IS NOT NULL") List<String> coverPaths();
    @Insert void insertBook(BookRow row);
    @Query("DELETE FROM books WHERE bookId=:id") void deleteBook(String id);
    @Insert void insertChapters(List<ChapterRow> rows);
    @Insert void insertParagraphs(List<ParagraphRow> rows);
    @Insert void insertToc(List<TocRow> rows);
    @Query("SELECT * FROM chapters WHERE bookId=:id ORDER BY orderIndex") List<ChapterRow> chapters(String id);
    @Query("SELECT * FROM chapters WHERE chapterId=:id") ChapterRow chapter(String id);
    @Query("SELECT * FROM toc WHERE bookId=:id ORDER BY orderIndex") List<TocRow> toc(String id);
    @Query("SELECT * FROM paragraphs WHERE chapterId=:id ORDER BY orderIndex") List<ParagraphRow> paragraphs(String id);
    @Query("SELECT * FROM paragraphs WHERE paragraphId=:id") ParagraphRow paragraph(String id);
    @Query("SELECT * FROM paragraphs WHERE chapterId=:id ORDER BY orderIndex") LiveData<List<ParagraphRow>> observeParagraphRows(String id);
    @Query("SELECT t.* FROM translations t JOIN paragraphs p ON p.paragraphId=t.paragraphId WHERE p.chapterId=:id AND t.targetLanguage=:lang") LiveData<List<TranslationRow>> observeTranslationRows(String id,String lang);
    @Query("SELECT p.* FROM paragraphs p JOIN chapters c ON p.chapterId=c.chapterId WHERE c.bookId=:id ORDER BY c.orderIndex,p.orderIndex") List<ParagraphRow> bookParagraphs(String id);
    @Query("SELECT * FROM translations WHERE paragraphId=:id AND targetLanguage=:lang") TranslationRow translation(String id,String lang);
    @Insert(onConflict=OnConflictStrategy.REPLACE) void putTranslation(TranslationRow row);
    @Query("SELECT * FROM progress WHERE bookId=:id") ProgressRow progress(String id);
    @Insert(onConflict=OnConflictStrategy.REPLACE) void putProgress(ProgressRow row);
    @Query("UPDATE books SET lastReadAt=:at WHERE bookId=:id") void touchBook(String id,long at);
    @Query("SELECT * FROM tasks WHERE taskId=:id") TaskRow task(String id);
    @Query("SELECT * FROM tasks WHERE state IN ('QUEUED','RUNNING') ORDER BY createdAt,taskId") List<TaskRow> activeTasks();
    @Query("SELECT t.* FROM tasks t JOIN chapters c ON c.chapterId=t.chapterId WHERE c.bookId=:bookId ORDER BY t.createdAt,t.taskId") List<TaskRow> bookTasks(String bookId);
    @Query("SELECT * FROM translation_requests WHERE requestId=:id") RequestRow request(String id);
    @Insert(onConflict=OnConflictStrategy.REPLACE) void putRequest(RequestRow row);
    @Query("SELECT * FROM tasks WHERE chapterId=:id AND sourceLanguage=:src AND targetLanguage=:lang AND state IN ('QUEUED','RUNNING') ORDER BY createdAt DESC LIMIT 1") TaskRow activeTask(String id,String src,String lang);
    @Insert void insertTask(TaskRow row);
    @Update void updateTask(TaskRow row);
    @Query("SELECT * FROM tasks WHERE taskId=:id") LiveData<TaskRow> observeTask(String id);
    @Query("SELECT * FROM tasks WHERE chapterId=:id AND targetLanguage=:lang ORDER BY createdAt DESC LIMIT 1") LiveData<TaskRow> observeLatestTask(String id,String lang);
  }
}
