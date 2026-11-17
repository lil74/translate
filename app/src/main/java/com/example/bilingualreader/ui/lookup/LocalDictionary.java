package com.example.bilingualreader.ui.lookup;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Shared read-only lexical data. Database work never runs on the UI thread. */
public final class LocalDictionary {
  public static final class Entry {
    public final String word, phonetic, meaning, tags;
    Entry(Cursor row) {word=row.getString(0);phonetic=row.getString(1);meaning=row.getString(2);tags=row.getString(3);}
  }
  private static final ExecutorService IO=Executors.newSingleThreadExecutor();
  private static final Handler MAIN=new Handler(Looper.getMainLooper());
  private static SQLiteDatabase database;
  private LocalDictionary() {}
  private static SQLiteDatabase open(Context context)throws IOException {
    if(database!=null)return database;
    File target=new File(context.getFilesDir(),"dictionary-v1.sqlite");
    if(!target.exists()) {
      File pending=new File(context.getFilesDir(),"dictionary-v1.tmp");
      try(InputStream in=new GZIPInputStream(context.getAssets().open("dictionary/dictionary-v1.sqlite.gzip"));
          FileOutputStream out=new FileOutputStream(pending)) {
        byte[] buffer=new byte[65536];int length;
        while((length=in.read(buffer))!=-1)out.write(buffer,0,length);
        out.getFD().sync();
      }
      if(!pending.renameTo(target))throw new IOException("Dictionary install failed");
    }
    database=SQLiteDatabase.openDatabase(target.getPath(),null,SQLiteDatabase.OPEN_READONLY);
    return database;
  }
  public static Future<?> search(Context context,String text,Consumer<List<Entry>> success,Consumer<String> failure) {
    Context app=context.getApplicationContext();String query=text.trim().toLowerCase(Locale.ROOT);
    return IO.submit(()->{
      try {
        List<Entry> rows=new ArrayList<>();
        if(query.matches("[a-z]+(?:[ '-][a-z]*)*") && query.length()<=100) {
          try(Cursor cursor=open(app).rawQuery("SELECT word,phonetic,meaning,tags FROM entries WHERE word>=? AND word<? ORDER BY CASE WHEN word=? THEN 0 ELSE 1 END,rank,length(word),word LIMIT 40",
              new String[]{query,query+"\uffff",query})) {while(cursor.moveToNext())rows.add(new Entry(cursor));}
        }
        if(!Thread.currentThread().isInterrupted())MAIN.post(()->success.accept(rows));
      }catch(Exception error){if(!Thread.currentThread().isInterrupted())MAIN.post(()->failure.accept("本地词库暂时无法读取，请使用在线查询"));}
    });
  }
  public static Future<?> find(Context context,String text,Consumer<Entry> success,Consumer<String> failure) {
    Context app=context.getApplicationContext();
    return IO.submit(()->{
      try {
        Entry entry=null;
        try(Cursor cursor=open(app).rawQuery("SELECT word,phonetic,meaning,tags FROM entries WHERE word=?",new String[]{text.trim().toLowerCase(Locale.ROOT)})) {
          if(cursor.moveToFirst())entry=new Entry(cursor);
        }
        Entry result=entry;
        if(!Thread.currentThread().isInterrupted())MAIN.post(()->success.accept(result));
      }catch(Exception error){if(!Thread.currentThread().isInterrupted())MAIN.post(()->failure.accept("本地词库暂时无法读取"));}
    });
  }
}
