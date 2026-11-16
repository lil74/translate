package com.example.bilingualreader.video;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.ByteBuffer;

/** Copies only the first AAC audio track; video pixels never leave the phone. */
public final class VideoAudio {
  public static final class Extracted implements AutoCloseable {
    public final File file;
    public final long offsetMs;
    Extracted(File file,long offsetMs){this.file=file;this.offsetMs=offsetMs;}
    @Override public void close(){file.delete();}
  }
  public static Extracted extract(Context context,Uri video)throws Exception {
    MediaExtractor extractor=new MediaExtractor();MediaMuxer muxer=null;
    File file=File.createTempFile("asr-audio-",".m4a",context.getCacheDir());boolean started=false,success=false;
    try {
      extractor.setDataSource(context,video,null);
      int track=-1;MediaFormat format=null;
      for(int i=0;i<extractor.getTrackCount();i++){
        MediaFormat candidate=extractor.getTrackFormat(i);
        if(candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;format=candidate;break;}
      }
      if(track<0)throw new IllegalArgumentException("视频没有音轨，请改用画面英文识别");
      if(!"audio/mp4a-latm".equals(format.getString(MediaFormat.KEY_MIME)))
        throw new IllegalArgumentException("初版语音识别支持 AAC 音轨，请转换为 MP4/AAC 后重试");
      if(format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION)>600000000L)
        throw new IllegalArgumentException("初版支持 10 分钟内视频，请先截取片段");
      extractor.selectTrack(track);long first=extractor.getSampleTime();
      // AAC encoder priming may legitimately have a negative PTS; only -1 is the EOS sentinel.
      if(first==-1)throw new IllegalArgumentException("视频音轨为空");
      muxer=new MediaMuxer(file.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      int target=muxer.addTrack(format);muxer.start();started=true;
      int size=format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)?format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE):1024*1024;
      if(size<=0 || size>4*1024*1024)throw new IllegalArgumentException("音频帧过大");
      ByteBuffer buffer=ByteBuffer.allocate(Math.max(size,1024*1024));MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
      while(true){
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        buffer.clear();int count=extractor.readSampleData(buffer,0);if(count<0)break;
        long time=extractor.getSampleTime()-first;
        if(time>600000000L || file.length()>20*1024*1024)throw new IllegalArgumentException("音频超过 10 分钟或 20 MB，请截取片段");
        if((extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IllegalArgumentException("不支持加密音轨");
        info.set(0,count,time,0);muxer.writeSampleData(target,buffer,info);extractor.advance();
      }
      muxer.stop();started=false;
      if(file.length()>20*1024*1024)throw new IllegalArgumentException("音频超过 20 MB，请截取片段");
      success=true;return new Extracted(file,first/1000);
    }finally{
      extractor.release();if(muxer!=null){if(started)try{muxer.stop();}catch(RuntimeException ignored){}muxer.release();}
      if(!success)file.delete();
    }
  }
}
