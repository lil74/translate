package com.example.bilingualreader.video;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Extracts supported timed text tracks locally; the video file never leaves the phone. */
public final class SoftSubtitleExtractor {
  private static final int MAX_CUES=SubtitleCodec.MAX_CUES;
  private static final int MAX_SAMPLE_BYTES=1024*1024;
  private static final long LAST_CUE_FALLBACK_MS=3000;
  private static final Pattern PAYL=Pattern.compile("payl\\s*(?:\\x00{4})?(.*?)(?=(?:\\w{4}\\s*\\x00{4})|$)",Pattern.DOTALL);
  private SoftSubtitleExtractor() {}

  public static List<SubtitleSegment> extract(Context context,Uri uri)throws Exception {
    MediaExtractor extractor=new MediaExtractor();
    try {
      extractor.setDataSource(context,uri,null);
      int selected=-1;String mime=null;
      for(int i=0;i<extractor.getTrackCount();i++) {
        MediaFormat format=extractor.getTrackFormat(i);
        String candidate=format.containsKey(MediaFormat.KEY_MIME)?format.getString(MediaFormat.KEY_MIME):null;
        if(supported(candidate)){selected=i;mime=candidate;break;}
      }
      if(selected<0)throw new IOException("视频中没有找到设备可读取的软字幕轨道");
      extractor.selectTrack(selected);
      List<RawCue> samples=new ArrayList<>();
      ByteBuffer buffer=ByteBuffer.allocate(MAX_SAMPLE_BYTES);
      while(true) {
        buffer.clear();int size=extractor.readSampleData(buffer,0);
        if(size<0)break;
        if(size>MAX_SAMPLE_BYTES)throw new IOException("软字幕单条数据超过支持范围");
        long time=extractor.getSampleTime();
        if(time<0)break;
        byte[] bytes=new byte[size];buffer.position(0);buffer.get(bytes);
        String text=decode(bytes,mime);
        samples.add(new RawCue(time/1000,text));
        if(samples.size()>MAX_CUES*4)throw new IOException("软字幕采样过多，最多支持 5000 条");
        if(!extractor.advance())break;
      }
      return normalize(samples,mime);
    } finally {extractor.release();}
  }

  private static boolean supported(String mime) {
    if(mime==null)return false;
    return mime.equals("text/3gpp") || mime.equals("application/x-quicktime-tx3g")
        || mime.equals("application/mp4vtt") || mime.equals("text/vtt")
        || mime.equals("application/x-subrip") || mime.equals("application/ttml+xml");
  }

  private static String decode(byte[] bytes,String mime) {
    String value=new String(bytes,StandardCharsets.UTF_8);
    if(mime.contains("tx3g") || mime.equals("text/3gpp")) {
      if(bytes.length>=2) {
        int length=((bytes[0]&255)<<8)|(bytes[1]&255);
        if(length<=bytes.length-2)value=new String(bytes,2,length,StandardCharsets.UTF_8);
      }
    } else if(mime.equals("application/mp4vtt")) {
      Matcher matcher=PAYL.matcher(value);StringBuilder text=new StringBuilder();
      while(matcher.find()){if(text.length()>0)text.append('\n');text.append(matcher.group(1));}
      value=text.toString();
    } else if(mime.equals("application/ttml+xml")) {
      Matcher matcher=Pattern.compile("(?is)<p\\b[^>]*>(.*?)</p>").matcher(value);StringBuilder text=new StringBuilder();
      while(matcher.find()){if(text.length()>0)text.append('\n');text.append(matcher.group(1).replaceAll("(?is)<[^>]+>"," "));}
      value=text.toString();
    }
    return value.replace('\u0000',' ').replaceAll("(?is)<[^>]+>"," ")
        .replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")
        .replace("&quot;","\"").replace("&#39;","'").replaceAll("[\\t\\x0B\\f ]+"," ").trim();
  }

  private static List<SubtitleSegment> normalize(List<RawCue> samples,String mime)throws IOException {
    if(samples.isEmpty())throw new IOException("软字幕轨道为空");
    List<SubtitleSegment> result=new ArrayList<>();String current=null;long start=-1;
    for(RawCue sample:samples) {
      String text=sample.text;
      if(text.isEmpty()) {
        if(current!=null)add(result,start,sample.time,current,mime);
        current=null;start=-1;continue;
      }
      if(current!=null && !current.equals(text))add(result,start,sample.time,current,mime);
      if(!text.equals(current)){current=text;start=sample.time;}
    }
    if(current!=null)add(result,start,Math.max(start+LAST_CUE_FALLBACK_MS,start+1),current,mime);
    if(result.isEmpty())throw new IOException("找到软字幕轨道，但无法解码字幕正文；请改用 SRT/VTT 文件");
    result.sort(Comparator.comparingLong(cue->cue.startMs));
    return Collections.unmodifiableList(result);
  }
  private static void add(List<SubtitleSegment> result,long start,long end,String text,String mime)throws IOException {
    if(end<=start || text.trim().isEmpty())return;
    if(result.size()>=MAX_CUES)throw new IOException("软字幕超过 5000 条支持上限");
    if(text.codePointCount(0,text.length())>40000)throw new IOException("软字幕单条正文过长");
    result.add(new SubtitleSegment("soft:"+result.size(),start,end,text,SubtitleSegment.TextSource.SUBTITLE,null,null,null,null));
  }
  private static final class RawCue {final long time;final String text;RawCue(long time,String text){this.time=time;this.text=text;}}
}
