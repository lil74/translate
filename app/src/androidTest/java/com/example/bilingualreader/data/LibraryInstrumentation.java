package com.example.bilingualreader.data;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.data.repository.LocalLibraryRepository;
import com.example.bilingualreader.data.repository.PreferencesSettingsRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Runs a unique original EPUB against Room; leaves other books untouched. */
public final class LibraryInstrumentation extends Instrumentation {
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  private static void entry(ZipOutputStream out,String name,String value)throws IOException{
    out.putNextEntry(new ZipEntry(name));out.write(value.getBytes(StandardCharsets.UTF_8));out.closeEntry();
  }
  private static File fixture(Context context)throws IOException{
    File file=File.createTempFile("b-storage-",".epub",context.getCacheDir());
    String unique=UUID.randomUUID().toString();
    try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))){
      entry(out,"mimetype","application/epub+zip");
      entry(out,"META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
      entry(out,"OPS/book.opf","<package xmlns='http://www.idpf.org/2007/opf' version='3.0'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Storage Test "+unique+"</dc:title><dc:language>en</dc:language></metadata><manifest><item id='a' href='chapter.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>");
      entry(out,"OPS/chapter.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>Heading</h1><p id='p'>A short sentence.</p></body></html>");
    }
    return file;
  }
  private static File txtFixture(Context context)throws IOException{
    File file=File.createTempFile("b-text-", ".txt", context.getCacheDir());
    String content="前言的话。\n第一章 出发\n第一段。\n第二段。\n第二章 归来\n结尾。\n"+UUID.randomUUID();
    try(FileOutputStream out=new FileOutputStream(file)){out.write(content.getBytes(StandardCharsets.UTF_8));}
    return file;
  }
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
  @Override public void onStart(){
    super.onStart();Bundle result=new Bundle();
    try{runChecks();result.putString("stream","PASS: EPUB/TXT import, custom chapter regex, duplicate, translation observation/cache, cancellation, progress, settings, deletion, late result, damaged cleanup\n");}
    catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");result.putString("failure",e.toString());}
    finish(result.containsKey("failure")?1:0,result);
  }
  private void runChecks()throws Exception{
    Context context=getTargetContext();File file=fixture(context);
    LocalLibraryRepository repo=new LocalLibraryRepository(context);String bookId=null;
    try{
      checkTxt(context,repo);
      ImportResult imported=repo.importBook(Uri.fromFile(file));bookId=imported.getBook().getBookId();
      check(!imported.isAlreadyExists(),"first import reported duplicate");
      check(new File(imported.getBook().getLocalFilePath()).isFile(),"private EPUB missing");
      check(repo.getChapters(bookId).size()==1,"chapter count");
      Chapter chapter=repo.getChapters(bookId).get(0);
      check(chapter.getParagraphCount()==2,"paragraph count");
      check(repo.getTableOfContents(bookId).size()==1,"fallback TOC");
      check(repo.importBook(Uri.fromFile(file)).isAlreadyExists(),"duplicate import");
      TranslationTask task=repo.prepareTask(chapter.getChapterId(),"en","zh-CN");
      check(task.getTotalCount()==2,"task total");
      Paragraph paragraph=repo.getPendingParagraphs(task.getTaskId()).get(1);
      TranslationOutput output=new TranslationOutput(paragraph.getParagraphId(),paragraph.getSourceHash(),TranslationStatus.SUCCESS,"一句短句。","fixture",null,null);
      TranslationBatchResult batch=new TranslationBatchResult(task.getTaskId(),"batch-1",Collections.singletonList(output));
      repo.markTaskRunning(task.getTaskId());repo.applyBatchResult(task.getTaskId(),batch);
      repo.applyBatchResult(task.getTaskId(),batch);
      check(repo.getPendingParagraphs(task.getTaskId()).size()==1,"successful translation was requested again");
      LiveData<List<ParagraphItem>> observed=repo.observeParagraphs(chapter.getChapterId(),"zh-CN");
      CountDownLatch seen=new CountDownLatch(1);
      Observer<List<ParagraphItem>> observer=rows->{
        if(rows!=null&&rows.size()==2&&rows.get(1).getTranslation()!=null
            &&rows.get(1).getTranslation().getParagraphId().equals(paragraph.getParagraphId())
            &&rows.get(1).getTranslation().getStatus()==TranslationStatus.SUCCESS)seen.countDown();
      };
      runOnMainSync(()->observed.observeForever(observer));
      try{check(seen.await(10,TimeUnit.SECONDS),"translation not visible by paragraph ID");}
      finally{runOnMainSync(()->observed.removeObserver(observer));}
      repo.cancelTask(task.getTaskId());
      try{repo.applyBatchResult(task.getTaskId(),batch);throw new AssertionError("cancelled task accepted result");}
      catch(AppException expected){check(expected.getCode()==ErrorCode.TRANSLATION_FAILED,"wrong cancellation error");}
      TranslationTask resumed=repo.prepareTask(chapter.getChapterId(),"en","zh-CN");
      check(resumed.getSuccessCount()==1,"cached success count");
      ReadingProgress progress=new ReadingProgress(bookId,chapter.getChapterId(),paragraph.getParagraphId(),-12,System.currentTimeMillis());
      repo.saveReadingProgress(progress);
      LocalLibraryRepository reopened=new LocalLibraryRepository(context);
      check(reopened.getReadingProgress(bookId).getParagraphId().equals(paragraph.getParagraphId()),"progress not persisted");
      check(reopened.getReadingProgress(bookId).getOffsetPx()==-12,"offset not persisted");
      reopened.deleteBook(bookId);bookId=null;
      check(!new File(imported.getBook().getLocalFilePath()).exists(),"private EPUB not deleted");
      check(reopened.isTaskCancelled(resumed.getTaskId()),"deleted task was not treated as cancelled");
      try{reopened.applyBatchResult(resumed.getTaskId(),new TranslationBatchResult(resumed.getTaskId(),"late",Collections.singletonList(output)));
        throw new AssertionError("late result was accepted after deletion");}
      catch(AppException expected){check(expected.getCode()==ErrorCode.TRANSLATION_FAILED,"wrong late result error");}
      try{reopened.getChapters(imported.getBook().getBookId());throw new AssertionError("deleted book still readable");}
      catch(AppException expected){check(expected.getCode()==ErrorCode.BOOK_NOT_FOUND,"wrong delete error");}
      File broken=File.createTempFile("b-broken-",".epub",context.getCacheDir());
      try(FileOutputStream out=new FileOutputStream(broken)){out.write("not a zip".getBytes(StandardCharsets.UTF_8));}
      try{repo.importBook(Uri.fromFile(broken));throw new AssertionError("damaged EPUB imported");}
      catch(AppException expected){check(expected.getCode()==ErrorCode.INVALID_EPUB,"wrong damaged EPUB error");}
      finally{broken.delete();}
      File[] leftovers=new File(context.getFilesDir(),"epub").listFiles((dir,name)->name.startsWith("import-"));
      check(leftovers==null||leftovers.length==0,"failed import left a temporary EPUB");
      Context testPrefs=new ContextWrapper(context){
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
          return context.getSharedPreferences("reader_settings_test",mode);
        }
      };
      try{
        PreferencesSettingsRepository settings=new PreferencesSettingsRepository(testPrefs);
        settings.saveSettings(new ReaderSettings(20,1.7f,ReaderTheme.NIGHT));
        ReaderSettings loaded=new PreferencesSettingsRepository(testPrefs).observeSettings().getValue();
        check(loaded!=null&&loaded.getFontSizeSp()==20&&loaded.getTheme()==ReaderTheme.NIGHT,
            "reader settings not persisted");
      }finally{context.getSharedPreferences("reader_settings_test",Context.MODE_PRIVATE).edit().clear().commit();}
    }finally{
      if(bookId!=null)try{repo.deleteBook(bookId);}catch(AppException ignored){}
      file.delete();
    }
  }
  private void checkTxt(Context context,LocalLibraryRepository repo)throws Exception{
    File file=txtFixture(context);String id=null;
    try{
      ImportResult result=repo.importBook(Uri.fromFile(file));id=result.getBook().getBookId();
      check(!result.isAlreadyExists(),"TXT first import reported duplicate");
      check(result.getBook().getLocalFilePath().endsWith(".txt"),"TXT source extension");
      List<Chapter> chapters=repo.getChapters(id);
      check(chapters.size()==3,"TXT front matter and two chapters");
      check("第一章 出发".equals(chapters.get(1).getTitle()),"default TXT chapter detection");
      check(repo.getTableOfContents(id).size()==3,"TXT TOC count");
      TranslationTask txtTask=repo.prepareTask(chapters.get(1).getChapterId(),"en","zh-CN");
      String paragraphId=repo.getPendingParagraphs(txtTask.getTaskId()).get(1).getParagraphId();
      ReadingProgress progress=new ReadingProgress(id,chapters.get(1).getChapterId(),paragraphId,-8,System.currentTimeMillis());
      repo.saveReadingProgress(progress);
      check(repo.importBook(Uri.fromFile(file)).isAlreadyExists(),"TXT duplicate import");
      LocalLibraryRepository reopened=new LocalLibraryRepository(context);
      check(reopened.getReadingProgress(id).getChapterId().equals(chapters.get(1).getChapterId()),"TXT chapter progress lost");
      check(reopened.getReadingProgress(id).getOffsetPx()==-8,"TXT offset progress lost");
      reopened.deleteBook(id);id=null;
      ImportResult custom=repo.importBook(Uri.fromFile(file),"^(.+章)\\s+.*$");id=custom.getBook().getBookId();
      check("第一章".equals(repo.getChapters(id).get(1).getTitle()),"custom regex capture group");
      repo.deleteBook(id);id=null;
      try{repo.importBook(Uri.fromFile(file),"[");throw new AssertionError("invalid regex accepted");}
      catch(AppException expected){check(expected.getCode()==ErrorCode.UNSUPPORTED_CONTENT,"invalid regex error code");}
    }finally{if(id!=null)try{repo.deleteBook(id);}catch(AppException ignored){}file.delete();}
  }
}
