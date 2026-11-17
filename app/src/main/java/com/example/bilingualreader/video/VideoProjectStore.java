package com.example.bilingualreader.video;

import android.content.Context;
import android.util.AtomicFile;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Atomic application-private checkpoint; deliberately separate from book Room storage. */
public final class VideoProjectStore {
  private static final Object LOCK=new Object();
  public static final java.util.concurrent.ExecutorService IO=java.util.concurrent.Executors.newSingleThreadExecutor();
  private final AtomicFile file;
  private final VideoLibrary library;
  private final Gson gson = new Gson();
  public VideoProjectStore(Context context) {
    file = new AtomicFile(new File(context.getFilesDir(), "video-project.json"));
    library=new VideoLibrary(new File(context.getFilesDir(),"video-library"));
  }
  public VideoProject load() throws IOException { synchronized(LOCK){return read();} }
  private VideoProject read() throws IOException {
    byte[] raw;
    try (InputStream input=file.openRead()) {
      ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int count;
      while ((count=input.read(buffer))!=-1) {
        if (out.size()+count>16*1024*1024) throw new IOException("视频项目文件过大");
        out.write(buffer,0,count);
      }
      raw=out.toByteArray();
    } catch (FileNotFoundException missing) { return VideoProject.empty(); }
    try {
      VideoProject value=gson.fromJson(new String(raw, StandardCharsets.UTF_8), VideoProject.class);
      List<SubtitleSegment> cues=new ArrayList<>();
      if (value==null || value.cues==null) throw new IllegalArgumentException();
      for (SubtitleSegment c:value.cues) cues.add(new SubtitleSegment(c.id,c.startMs,c.endMs,c.sourceText,
          c.textSource,c.confidence,c.translatedText,c.provider,c.engineVersion));
      if (value.translationKey==null && cues.stream().anyMatch(c -> c.translatedText!=null))
        throw new IllegalArgumentException();
      return new VideoProject(value.videoUri,value.videoName,value.subtitleName,value.direction,
          value.translationKey,value.demo,value.positionMs,cues);
    } catch (RuntimeException malformed) { throw new IOException("上次视频项目无法读取，请重新选择视频和字幕", malformed); }
  }
  private void migrate()throws IOException {VideoProject current=read();if(current.videoUri!=null&&!library.contains(current.videoUri))library.save(current);}
  public VideoProject load(String uri)throws IOException {synchronized(LOCK){migrate();return library.load(uri);}}
  public List<VideoLibrary.Entry> list()throws IOException {synchronized(LOCK){migrate();return library.list();}}
  public VideoProject add(String uri,String name)throws IOException {synchronized(LOCK){migrate();return library.add(uri,name);}}
  public void delete(String uri)throws IOException {synchronized(LOCK){library.delete(uri);VideoProject current=read();if(uri.equals(current.videoUri))write(VideoProject.empty());}}
  public void save(VideoProject project) throws IOException { synchronized(LOCK){if(project.videoUri!=null&&!library.save(project))return;write(project);} }
  private void write(VideoProject project) throws IOException {
    byte[] raw=gson.toJson(project).getBytes(StandardCharsets.UTF_8);
    if (raw.length>16*1024*1024) throw new IOException("视频项目超过本地保存限制");
    FileOutputStream output=null;
    try { output=file.startWrite(); output.write(raw); file.finishWrite(output); }
    catch (IOException error) { if(output!=null)file.failWrite(output); throw error; }
  }
}
