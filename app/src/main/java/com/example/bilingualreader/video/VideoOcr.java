package com.example.bilingualreader.video;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognizer;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bundled Latin OCR, entirely on device. Sampling timestamps are approximate to 500 ms. */
public final class VideoOcr {
  public interface Progress { void update(int percent); }
  public List<SubtitleSegment> scan(Context context,Uri video,float top,float bottom,Progress progress)throws Exception {
    if(top<0 || bottom>1 || bottom<=top)throw new IllegalArgumentException("识别区域无效");
    MediaMetadataRetriever media=new MediaMetadataRetriever();
    TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    List<SubtitleSegment> cues=new ArrayList<>();
    try {
      media.setDataSource(context,video);
      long duration=Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      if(duration<=0 || duration>10*60*1000)throw new IllegalArgumentException("初版支持 10 分钟以内的视频，请先截取片段");
      String last="";long start=0,end=0;String prefix="ocr:"+UUID.randomUUID()+":";
      for(long time=0;time<duration;time+=500){
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        Bitmap frame=media.getFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST);
        if(frame==null)throw new IllegalArgumentException("无法读取视频画面，请改用兼容的 MP4 视频");
        Bitmap crop=null,scaled=null;
        String text;
        try {
          int y=Math.min(frame.getHeight()-1,(int)(frame.getHeight()*top));
          int height=Math.max(1,Math.min(frame.getHeight()-y,(int)(frame.getHeight()*(bottom-top))));
          crop=Bitmap.createBitmap(frame,0,y,frame.getWidth(),height);
          scaled=crop.getWidth()>1280?Bitmap.createScaledBitmap(crop,1280,Math.max(1,crop.getHeight()*1280/crop.getWidth()),true):crop;
          text=Tasks.await(recognizer.process(InputImage.fromBitmap(scaled,0)),30,TimeUnit.SECONDS).getText().trim();
          if(!text.matches("(?s).*[A-Za-z].*"))text="";
        }finally{
          if(scaled!=null && scaled!=crop && scaled!=frame)scaled.recycle();
          if(crop!=null && crop!=frame)crop.recycle();frame.recycle();
        }
        if(!text.equals(last)){
          if(!last.isEmpty())cues.add(new SubtitleSegment(prefix+cues.size(),start,end,last,SubtitleSegment.TextSource.OCR,null,null,null,null));
          last=text;start=time;
        }
        end=Math.min(duration,time+500);
        progress.update((int)(end*100/duration));
      }
      if(!last.isEmpty())cues.add(new SubtitleSegment(prefix+cues.size(),start,end,last,SubtitleSegment.TextSource.OCR,null,null,null,null));
      if(cues.isEmpty())throw new IllegalArgumentException("没有识别到英文，请调整识别区域或使用更清晰的视频");
      return cues;
    }finally{recognizer.close();media.release();}
  }
}
