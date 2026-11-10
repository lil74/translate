package com.example.bilingualreader.data.repository;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import androidx.lifecycle.*;
import androidx.room.Room;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.data.db.LibraryDatabase;
import com.example.bilingualreader.data.db.LibraryDatabase.*;
import com.example.bilingualreader.epub.EpubParser;
import com.example.bilingualreader.translation.TranslationCachePolicy;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Blocking commands are called from the ViewModel executor or Worker. */
public final class LocalLibraryRepository implements BookRepository,ReaderRepository,TranslationRuntimeStore {
  private final Context context; private final LibraryDatabase db; private final DaoAccess dao;
  public LocalLibraryRepository(Context context) {
    this.context=context.getApplicationContext();
    db=Room.databaseBuilder(this.context,LibraryDatabase.class,"library.db")
        .addMigrations(LibraryDatabase.MIGRATION_1_2).build();dao=db.dao();
  }
  public LocalLibraryRepository(Context context, LibraryDatabase database) {
    this.context=context.getApplicationContext(); this.db=database; this.dao=db.dao();
  }
  private static AppException err(ErrorCode code,String message){return new AppException(code,message,false);}
  private String name(Uri uri) {try(Cursor c=context.getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int col=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(col>=0)return c.getString(col);}}catch(Exception ignored){}String s=uri.getLastPathSegment();return s==null?"Imported book.epub":s;}
  public ImportResult importBook(Uri uri) throws AppException {
    File dir=new File(context.getFilesDir(),"epub");if(!dir.exists()&&!dir.mkdirs())throw err(ErrorCode.IMPORT_IO_ERROR,"无法创建书籍目录");
    File temp;try{temp=File.createTempFile("import-",".epub",dir);}catch(IOException e){throw new AppException(ErrorCode.IMPORT_IO_ERROR,"无法创建导入文件",true,e);}
    try {
      MessageDigest md=MessageDigest.getInstance("SHA-256");long size=0;
      try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)) {
        if(in==null)throw err(ErrorCode.IMPORT_PERMISSION_DENIED,"无法读取所选文件");byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1){size+=n;if(size>50L*1024*1024)throw err(ErrorCode.EPUB_TOO_LARGE,"EPUB 文件超过 50 MiB");md.update(buf,0,n);out.write(buf,0,n);}
      }
      StringBuilder h=new StringBuilder();for(byte b:md.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));String id=h.toString();
      BookRow existing=dao.book(id);if(existing!=null)return new ImportResult(book(existing),true,Collections.emptyList());
      String filename=name(uri);String fallback=filename.replaceFirst("(?i)\\.epub$","");
      EpubParser.Result parsed=new EpubParser().parse(temp,id,fallback);
      File dest=new File(dir,id+".epub");if(!temp.renameTo(dest))throw err(ErrorCode.IMPORT_IO_ERROR,"无法保存 EPUB 文件");
      BookRow row=new BookRow();row.bookId=id;row.title=parsed.title;row.author=parsed.author;row.language=parsed.language;row.originalFileName=filename;row.localFilePath=dest.getAbsolutePath();row.chapterCount=parsed.chapters.size();row.parserVersion=1;row.importedAt=System.currentTimeMillis();
      try {db.runInTransaction(()->{dao.insertBook(row);List<ChapterRow> cr=new ArrayList<>();for(Chapter c:parsed.chapters){ChapterRow x=new ChapterRow();x.chapterId=c.getChapterId();x.bookId=id;x.title=c.getTitle();x.resourcePath=c.getResourcePath();x.orderIndex=c.getOrderIndex();x.paragraphCount=c.getParagraphCount();cr.add(x);}dao.insertChapters(cr);List<ParagraphRow> pr=new ArrayList<>();for(Paragraph p:parsed.paragraphs){ParagraphRow x=new ParagraphRow();x.paragraphId=p.getParagraphId();x.chapterId=p.getChapterId();x.orderIndex=p.getOrderIndex();x.blockType=p.getBlockType().name();x.originalText=p.getOriginalText();x.sourceHash=p.getSourceHash();pr.add(x);}dao.insertParagraphs(pr);List<TocRow> tr=new ArrayList<>();for(TocEntry t:parsed.toc){TocRow x=new TocRow();x.tocId=t.getTocId();x.bookId=id;x.parentTocId=t.getParentTocId();x.orderIndex=t.getOrderIndex();x.title=t.getTitle();x.chapterId=t.getChapterId();x.paragraphId=t.getParagraphId();tr.add(x);}dao.insertToc(tr);});}
      catch(RuntimeException e){dest.delete();throw new AppException(ErrorCode.DATABASE_ERROR,"保存书籍数据失败",false,e);}
      return new ImportResult(book(row),false,parsed.warnings);
    }catch(AppException e){Log.e("EpubImport", "Import failed: " + e.getCode(), e);throw e;}catch(SecurityException e){Log.e("EpubImport", "Import permission denied", e);throw new AppException(ErrorCode.IMPORT_PERMISSION_DENIED,"没有读取文件的权限",false,e);}catch(Exception e){Log.e("EpubImport", "Import I/O failed", e);throw new AppException(ErrorCode.IMPORT_IO_ERROR,"导入 EPUB 失败",true,e);}finally{temp.delete();}
  }
  private static Book book(BookRow r){return new Book(r.bookId,r.title,r.author,r.language,r.originalFileName,r.localFilePath,r.coverPath,r.chapterCount,r.parserVersion,r.importedAt,r.lastReadAt);}
  public LiveData<List<Book>> observeBooks(){return Transformations.map(dao.observeBooks(),rows->{List<Book> out=new ArrayList<>();for(BookRow r:rows)out.add(book(r));return out;});}
  public List<Chapter> getChapters(String id)throws AppException{if(dao.book(id)==null)throw err(ErrorCode.BOOK_NOT_FOUND,"书籍不存在");List<Chapter> out=new ArrayList<>();for(ChapterRow r:dao.chapters(id))out.add(new Chapter(r.chapterId,r.bookId,r.title,r.orderIndex,r.resourcePath,r.paragraphCount));return out;}
  public List<TocEntry> getTableOfContents(String id)throws AppException{if(dao.book(id)==null)throw err(ErrorCode.BOOK_NOT_FOUND,"书籍不存在");List<TocEntry> out=new ArrayList<>();for(TocRow r:dao.toc(id))out.add(new TocEntry(r.tocId,r.bookId,r.parentTocId,r.orderIndex,r.title,r.chapterId,r.paragraphId));return out;}
  public void deleteBook(String id)throws AppException{BookRow row=dao.book(id);if(row==null)throw err(ErrorCode.BOOK_NOT_FOUND,"书籍不存在");db.runInTransaction(()->dao.deleteBook(id));new File(row.localFilePath).delete();}
  public LiveData<List<ParagraphItem>> observeParagraphs(String chapterId,String lang){
    LiveData<List<ParagraphRow>> paragraphs=dao.observeParagraphRows(chapterId);
    LiveData<List<TranslationRow>> translations=dao.observeTranslationRows(chapterId,lang);
    MediatorLiveData<List<ParagraphItem>> result=new MediatorLiveData<>();
    Runnable refresh=()->{
      List<ParagraphRow> rows=paragraphs.getValue();
      List<TranslationRow> translated=translations.getValue();
      if(rows==null||translated==null)return;
      Map<String,TranslationRow> byId=new HashMap<>();
      for(TranslationRow t:translated)byId.put(t.paragraphId,t);
      List<ParagraphItem> out=new ArrayList<>();
      for(ParagraphRow p:rows){
        TranslationRow t=byId.get(p.paragraphId);
        Paragraph model=new Paragraph(p.paragraphId,p.chapterId,p.orderIndex,BlockType.valueOf(p.blockType),p.originalText,p.sourceHash);
        Translation translation=t==null?null:new Translation(t.paragraphId,t.targetLanguage,t.sourceLanguage,t.sourceHash,t.translatedText,TranslationStatus.valueOf(t.status),t.provider,t.engineVersion,t.errorCode,t.updatedAt);
        out.add(new ParagraphItem(model,translation));
      }
      result.setValue(out);
    };
    result.addSource(paragraphs,rows->refresh.run());
    result.addSource(translations,rows->refresh.run());
    return result;
  }
  public ReadingProgress getReadingProgress(String id)throws AppException{if(dao.book(id)==null)throw err(ErrorCode.BOOK_NOT_FOUND,"书籍不存在");ProgressRow p=dao.progress(id);return p==null?null:new ReadingProgress(p.bookId,p.chapterId,p.paragraphId,p.offsetPx,p.updatedAt);}
  public void saveReadingProgress(ReadingProgress p)throws AppException{if(dao.book(p.getBookId())==null)throw err(ErrorCode.BOOK_NOT_FOUND,"书籍不存在");ProgressRow r=new ProgressRow();r.bookId=p.getBookId();r.chapterId=p.getChapterId();r.paragraphId=p.getParagraphId();r.offsetPx=p.getOffsetPx();r.updatedAt=p.getUpdatedAt();db.runInTransaction(()->{dao.putProgress(r);dao.touchBook(r.bookId,r.updatedAt);});}

  private interface Transaction<T> { T run() throws AppException; }
  private <T> T transaction(Transaction<T> action) throws AppException {
    db.beginTransaction();
    try {
      T value = action.run(); db.setTransactionSuccessful(); return value;
    } catch (AppException error) { throw error;
    } catch (RuntimeException error) {
      throw new AppException(ErrorCode.DATABASE_ERROR, "翻译数据保存失败", false, error);
    } finally { db.endTransaction(); }
  }
  private boolean success(ParagraphRow p, String lang, String src, String mode) {
    TranslationRow t = dao.translation(p.paragraphId, lang);
    return t != null && "SUCCESS".equals(t.status) && src.equals(t.sourceLanguage)
        && p.sourceHash.equals(t.sourceHash) && t.translatedText != null && !t.translatedText.trim().isEmpty()
        && TranslationCachePolicy.canReuse(mode, t.provider, t.translatedText);
  }
  private static TranslationTask task(TaskRow t) {
    return t == null ? null : new TranslationTask(t.taskId, t.chapterId, t.sourceLanguage,
        t.targetLanguage, TaskState.valueOf(t.state), t.totalCount, t.successCount,
        t.failedCount, t.createdAt, t.updatedAt, t.errorCode, t.errorMessage);
  }
  private static boolean active(TaskRow t) {
    return t != null && ("QUEUED".equals(t.state) || "RUNNING".equals(t.state));
  }
  private TaskRow requireTask(String id) throws AppException {
    TaskRow t = dao.task(id);
    if (t == null) throw err(ErrorCode.CHAPTER_NOT_FOUND, "翻译任务不存在或书籍已删除");
    return t;
  }
  private void recount(TaskRow t) {
    int ok = 0, failed = 0;
    List<ParagraphRow> paragraphs = dao.paragraphs(t.chapterId);
    for (ParagraphRow p : paragraphs) {
      if (success(p, t.targetLanguage, t.sourceLanguage, t.executionMode)) ok++;
      else {
        TranslationRow r = dao.translation(p.paragraphId, t.targetLanguage);
        if (r != null && p.sourceHash.equals(r.sourceHash) && "FAILED".equals(r.status)) failed++;
      }
    }
    t.totalCount = paragraphs.size(); t.successCount = ok; t.failedCount = failed;
    t.updatedAt = System.currentTimeMillis(); dao.updateTask(t);
  }
  private TranslationRow row(ParagraphRow p, TaskRow t, String status) {
    TranslationRow r = new TranslationRow(); r.paragraphId = p.paragraphId;
    r.targetLanguage = t.targetLanguage; r.sourceLanguage = t.sourceLanguage;
    r.sourceHash = p.sourceHash; r.status = status; r.updatedAt = System.currentTimeMillis();
    return r;
  }
  private void resetRunning(TaskRow t) {
    for (ParagraphRow p : dao.paragraphs(t.chapterId)) {
      TranslationRow r = dao.translation(p.paragraphId, t.targetLanguage);
      if (r != null && "RUNNING".equals(r.status)) {
        r.status = "PENDING"; r.updatedAt = System.currentTimeMillis(); dao.putTranslation(r);
      }
    }
  }
  @Override public TranslationTask prepareTask(String chapterId, String source, String target)
      throws AppException {
    return prepare(chapterId, source, target, "", "");
  }
  private TranslationTask prepare(String chapterId, String source, String target,
      String mode, String endpoint) throws AppException {
    if (!"en".equals(source) || !"zh-CN".equals(target))
      throw err(ErrorCode.UNSUPPORTED_LANGUAGE, "仅支持英语翻译为简体中文");
    return transaction(() -> {
      if (dao.chapter(chapterId) == null) throw err(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
      TaskRow existing = dao.activeTask(chapterId, source, target);
      if (existing != null) {
        if (mode.isEmpty() || (mode.equals(existing.executionMode) && endpoint.equals(existing.endpoint)))
          return task(existing);
        // Supersede the old service snapshot before it can write late results.
        cancelTask(existing.taskId);
      }
      TaskRow t = new TaskRow(); t.taskId = UUID.randomUUID().toString(); t.chapterId = chapterId;
      t.sourceLanguage = source; t.targetLanguage = target; t.state = "QUEUED";
      t.executionMode = mode; t.endpoint = endpoint;
      t.createdAt = t.updatedAt = System.currentTimeMillis();
      List<ParagraphRow> paragraphs = dao.paragraphs(chapterId); t.totalCount = paragraphs.size();
      for (ParagraphRow p : paragraphs) {
        if (success(p, target, source, mode)) t.successCount++;
        else dao.putTranslation(row(p, t, "PENDING"));
      }
      if (t.successCount == t.totalCount) t.state = "COMPLETED";
      dao.insertTask(t); return task(t);
    });
  }
  @Override public List<Paragraph> getPendingParagraphs(String id) throws AppException {
    return pending(id, false);
  }
  @Override public TranslationTask prepareConfiguredTask(String chapterId, String source, String target,
      String mode, String endpoint) throws AppException {
    return prepare(chapterId, source, target, mode, endpoint);
  }
  @Override public List<Paragraph> getRunnableParagraphs(String id) throws AppException {
    return pending(id, true);
  }
  private List<Paragraph> pending(String id, boolean skipFailed) throws AppException {
    return transaction(() -> {
      TaskRow t = requireTask(id); List<Paragraph> out = new ArrayList<>();
      if (!active(t)) return out;
      for (ParagraphRow p : dao.paragraphs(t.chapterId)) {
        TranslationRow r = dao.translation(p.paragraphId, t.targetLanguage);
        if (!success(p, t.targetLanguage, t.sourceLanguage, t.executionMode)
            && !(skipFailed && r != null && "FAILED".equals(r.status))) {
          out.add(new Paragraph(p.paragraphId, p.chapterId, p.orderIndex,
              BlockType.valueOf(p.blockType), p.originalText, p.sourceHash));
        }
      }
      return out;
    });
  }
  @Override public void markTaskRunning(String id) throws AppException {
    transaction(() -> { TaskRow t = dao.task(id); if (active(t)) {
      t.state = "RUNNING"; t.errorCode = null; t.errorMessage = null;
      t.updatedAt = System.currentTimeMillis(); dao.updateTask(t);
    } return null; });
  }
  @Override public void markBatchRunning(String id, List<String> ids) throws AppException {
    transaction(() -> { TaskRow t = dao.task(id); if (!active(t)) return null;
      Set<String> selected = new HashSet<>(ids);
      for (ParagraphRow p : dao.paragraphs(t.chapterId)) {
        if (selected.contains(p.paragraphId) && !success(p, t.targetLanguage, t.sourceLanguage, t.executionMode))
          dao.putTranslation(row(p, t, "RUNNING"));
      } return null;
    });
  }
  @Override public void applyBatchResult(String id, TranslationBatchResult result) throws AppException {
    transaction(() -> {
      TaskRow t = dao.task(id);
      // Cancellation/deletion and result writes share this transaction boundary.
      if (!active(t)) return null;
      if (!id.equals(result.getTaskId()) || result.getRequestId().trim().isEmpty())
        throw err(ErrorCode.INVALID_RESPONSE, "翻译请求标识不匹配");
      Map<String, ParagraphRow> allowed = new HashMap<>();
      for (ParagraphRow p : dao.paragraphs(t.chapterId)) allowed.put(p.paragraphId, p);
      Set<String> seen = new HashSet<>();
      for (TranslationOutput o : result.getItems()) {
        ParagraphRow p = o == null ? null : allowed.get(o.getParagraphId());
        if (p == null || !seen.add(o.getParagraphId()) || !p.sourceHash.equals(o.getSourceHash())
            || (o.getStatus() != TranslationStatus.SUCCESS && o.getStatus() != TranslationStatus.FAILED)
            || (o.getStatus() == TranslationStatus.SUCCESS
                && (o.getTranslatedText() == null || o.getTranslatedText().trim().isEmpty()
                    || !TranslationCachePolicy.canReuse(t.executionMode, o.getProvider(), o.getTranslatedText()))))
          throw err(ErrorCode.INVALID_RESPONSE, "翻译结果与原文不匹配");
      }
      for (TranslationOutput o : result.getItems()) {
        ParagraphRow p = allowed.get(o.getParagraphId());
        if (success(p, t.targetLanguage, t.sourceLanguage, t.executionMode)) continue;
        TranslationRow r = row(p, t, o.getStatus().name()); r.translatedText = o.getTranslatedText();
        r.provider = o.getProvider(); r.engineVersion = o.getEngineVersion(); r.errorCode = o.getErrorCode();
        if ("mock".equals(t.executionMode)) r.provider = "fake";
        dao.putTranslation(r);
      }
      recount(t); return null;
    });
  }
  @Override public void markBatchFailed(String id, List<String> ids, String code, String message)
      throws AppException {
    transaction(() -> {
      TaskRow t = dao.task(id); if (!active(t)) return null;
      Set<String> selected = new HashSet<>(ids);
      for (ParagraphRow p : dao.paragraphs(t.chapterId)) {
        if (selected.contains(p.paragraphId) && !success(p, t.targetLanguage, t.sourceLanguage, t.executionMode)) {
          TranslationRow r = row(p, t, "FAILED"); r.errorCode = code; dao.putTranslation(r);
        }
      }
      t.errorCode = code; t.errorMessage = message; recount(t); return null;
    });
  }
  @Override public void finishTask(String id) throws AppException {
    transaction(() -> { TaskRow t = dao.task(id); if (!active(t)) return null;
      recount(t); t.state = t.successCount == t.totalCount ? "COMPLETED"
          : t.successCount > 0 ? "PARTIAL_FAILED" : "FAILED";
      if (!"COMPLETED".equals(t.state) && t.errorMessage == null) {
        t.errorCode = ErrorCode.TRANSLATION_FAILED.name(); t.errorMessage = "部分段落未完成，可重试未完成段落";
      }
      if ("COMPLETED".equals(t.state)) { t.errorCode = null; t.errorMessage = null; }
      dao.updateTask(t); return null;
    });
  }
  @Override public void failTask(String id, String code, String message) throws AppException {
    transaction(() -> { TaskRow t = dao.task(id); if (!active(t)) return null;
      resetRunning(t); recount(t); t.state = "FAILED"; t.errorCode = code; t.errorMessage = message;
      dao.updateTask(t); return null;
    });
  }
  @Override public void cancelTask(String id) throws AppException {
    transaction(() -> { TaskRow t = dao.task(id); if (!active(t)) return null;
      resetRunning(t); recount(t); t.state = "CANCELLED"; dao.updateTask(t); return null;
    });
  }
  @Override public boolean isTaskCancelled(String id) throws AppException {
    return transaction(() -> { TaskRow t = dao.task(id); return t == null || "CANCELLED".equals(t.state); });
  }
  @Override public LiveData<TranslationTask> observeTask(String id) {
    return Transformations.map(dao.observeTask(id), LocalLibraryRepository::task);
  }
  @Override public LiveData<TranslationTask> observeLatestTask(String chapterId, String lang) {
    return Transformations.map(dao.observeLatestTask(chapterId, lang), LocalLibraryRepository::task);
  }
  @Override public TranslationTask getTask(String id) throws AppException {
    return transaction(() -> task(dao.task(id)));
  }
  @Override public List<TranslationTask> getActiveTasks() throws AppException {
    return transaction(() -> { List<TranslationTask> out = new ArrayList<>();
      for (TaskRow t : dao.activeTasks()) out.add(task(t)); return out; });
  }
  @Override public List<TranslationTask> getBookTasks(String bookId) throws AppException {
    return transaction(() -> { List<TranslationTask> out = new ArrayList<>();
      for (TaskRow t : dao.bookTasks(bookId)) out.add(task(t)); return out; });
  }
  @Override public void bindService(String id, String mode, String endpoint) throws AppException {
    transaction(() -> { TaskRow t = requireTask(id);
      if (active(t) && t.executionMode.isEmpty()) {
        t.executionMode = mode; t.endpoint = endpoint; dao.updateTask(t);
      } return null;
    });
  }
  @Override public String[] getService(String id) throws AppException {
    return transaction(() -> { TaskRow t = requireTask(id); return new String[]{t.executionMode, t.endpoint}; });
  }
  @Override public Attempt getAttempt(String id) throws AppException {
    return transaction(() -> { RequestRow r = dao.request(id);
      return r == null ? new Attempt(0, 0, null, null)
          : new Attempt(r.attempts, r.notBefore, r.errorCode, r.message);
    });
  }
  @Override public boolean claimAttempt(String taskId, String id, long now) throws AppException {
    return transaction(() -> {
      if (!active(dao.task(taskId))) return false;
      RequestRow r = dao.request(id);
      if (r == null) { r = new RequestRow(); r.requestId = id; r.taskId = taskId; }
      if (!taskId.equals(r.taskId) || r.attempts >= 3 || r.notBefore > now) return false;
      r.attempts++; dao.putRequest(r); return true;
    });
  }
  @Override public void postponeAttempt(String taskId, String id, long until, String code, String message)
      throws AppException {
    transaction(() -> {
      if (!active(dao.task(taskId))) return null;
      RequestRow r = dao.request(id);
      if (r != null && taskId.equals(r.taskId)) {
        r.notBefore = until; r.errorCode = code; r.message = message; dao.putRequest(r);
      } return null;
    });
  }
  @Override public void markTaskWaiting(String id, String code, String message) throws AppException {
    transaction(() -> { TaskRow t = dao.task(id); if (!active(t)) return null;
      resetRunning(t); recount(t); t.state = "QUEUED"; t.errorCode = code; t.errorMessage = message;
      dao.updateTask(t); return null;
    });
  }
}
