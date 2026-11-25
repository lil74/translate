package com.example.bilingualreader.video;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Authenticated upload and resumable polling. Never holds a vendor API key. */
public final class SpeechClient {
  public interface Progress {void update(String message);}
  private final Context context;
  private final String base,token,language;
  private final SharedPreferences prefs;
  public SpeechClient(Context context,String endpoint,String token)throws Exception {
    this(context,endpoint,token,"en");
  }
  public SpeechClient(Context context,String endpoint,String token,String language)throws Exception {
    if (!Arrays.asList("en", "zh", "ko", "ja").contains(language))
      throw new IllegalArgumentException("不支持的语音识别语言");
    this.context=context.getApplicationContext();this.token=token;
    this.language=language;
    URL address=new URL(endpoint);
    if(!"https".equals(address.getProtocol()) || address.getUserInfo()!=null || address.getQuery()!=null || address.getRef()!=null)
      throw new IllegalArgumentException("语音识别需要 HTTPS 团队服务地址");
    if(token==null || token.isEmpty())throw new IllegalArgumentException("请先在翻译服务中填写网关访问令牌");
    base=new URL(address,"/asr/jobs").toString();
    prefs=this.context.getSharedPreferences("video_asr_pending",Context.MODE_PRIVATE);
  }
  public List<SubtitleSegment> recognize(Uri video,Progress progress)throws Exception {
    String key="seg-v2|"+base+"|"+language+"|"+video;
    String job=null;long offset=0;
    if(key.equals(prefs.getString("key",""))){job=prefs.getString("job",null);offset=prefs.getLong("offset",0);}
    if(job==null){
      progress.update("正在提取视频音频…");
      try(VideoAudio.Extracted audio=VideoAudio.extract(context,video)){
        offset=audio.offsetMs;progress.update("正在上传音频（视频画面不上传）…");
        JsonObject submitted=request(base,audio.file);
        job=submitted.get("jobId").getAsString();
        if(!job.matches("[a-f0-9]{32}"))throw new IOException("服务器返回无效语音任务");
        if(!prefs.edit().putString("key",key).putString("job",job).putLong("offset",offset).commit())
          throw new IOException("无法保存语音任务，请检查存储空间");
      }
    }
    long deadline=System.nanoTime()+15L*60*1000000000L;
    while(System.nanoTime()<deadline){
      if(Thread.currentThread().isInterrupted())throw new InterruptedException();
      progress.update("正在云端识别语音，可暂停后继续获取结果…");
      JsonObject response;
      try{response=request(base+"/"+job,null);}
      catch(Expired error){prefs.edit().clear().commit();throw error;}
      String status=response.get("status").getAsString();
      if("SUCCEEDED".equals(status)){
        List<SubtitleSegment> cues=new ArrayList<>();
        for(JsonElement item:response.getAsJsonArray("segments")){
          JsonObject value=item.getAsJsonObject();
          long start=Math.max(0,value.get("startMs").getAsLong()+offset),end=value.get("endMs").getAsLong()+offset;
          if(end<=start)continue;
          cues.add(new SubtitleSegment("asr:"+job+":"+cues.size(),start,
              end,value.get("text").getAsString(),SubtitleSegment.TextSource.ASR,null,null,null,null));
          if(cues.size()>5000)throw new IOException("语音字幕超过支持范围");
        }
        if(cues.isEmpty())throw new IOException("未识别到语音，请改用画面识别");
        cues.sort(Comparator.comparingLong(cue->cue.startMs));
        return cues;
      }
      if("FAILED".equals(status)||"CANCELLED".equals(status)){
        prefs.edit().clear().commit();throw new IOException(response.has("error")?response.get("error").getAsString():"语音识别失败，请重新开始");
      }
      if(!"PENDING".equals(status)&&!"RUNNING".equals(status))throw new IOException("语音任务状态无效");
      Thread.sleep(2500);
    }
    throw new IOException("语音识别等待超时，任务已保留，请稍后继续");
  }
  private static final class Expired extends IOException {Expired(String text){super(text);}}
  private JsonObject request(String url,File audio)throws Exception {
    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
    HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
    try{
      connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(65000);
      connection.setRequestProperty("Authorization","Bearer "+token);
      connection.setRequestProperty("X-Recognition-Language",language);
      if(audio!=null){
        connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","audio/mp4");
        connection.setFixedLengthStreamingMode(audio.length());
        try(InputStream input=new FileInputStream(audio);OutputStream output=connection.getOutputStream()){
          byte[] buffer=new byte[16384];int count;
          while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();output.write(buffer,0,count);}
        }
      }
      int status=connection.getResponseCode();
      InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
      if(stream==null)throw new IOException("语音接口不可用，请更新团队网关");
      ByteArrayOutputStream out=new ByteArrayOutputStream();
      try(InputStream input=stream){byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1){if(out.size()+count>2*1024*1024)throw new IOException("语音响应过大");out.write(buffer,0,count);}}
      JsonObject response;
      try{response=JsonParser.parseString(out.toString("UTF-8")).getAsJsonObject();}
      catch(RuntimeException invalid){throw new IOException("语音接口响应无效，请更新团队网关");}
      if(status<200||status>=300){String message=response.has("error")?response.get("error").getAsString():"语音接口请求失败（"+status+"）";
        if(status==404 || status==410)throw new Expired(message);throw new IOException(message);}
      return response;
    }finally{connection.disconnect();}
  }
}
