package com.example.bilingualreader.video;

import static org.junit.Assert.*;
import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.*;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.ui.MainActivity;
import com.example.bilingualreader.ui.video.VideoFragment;
import com.example.bilingualreader.viewmodel.VideoViewModel;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Generated local video and controlled services only. No media leaves the device. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class VideoDeviceTest {
  private Context context;
  private AppContainer graph;
  private AppContainer.SelectionBinding original;
  private ActivityScenario<MainActivity> scenario;
  private VideoViewModel vm;
  private final List<File> files=new ArrayList<>();
  private final List<CountDownLatch> releases=new ArrayList<>();
  private final AtomicInteger calls=new AtomicInteger();
  private interface Check { boolean ready() throws Exception; }
  private static void await(Check condition)throws Exception {
    long until=System.currentTimeMillis()+15000;
    while(!condition.ready() && System.currentTimeMillis()<until)Thread.sleep(25);
    assertTrue("Video condition timed out",condition.ready());
  }
  private static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
  private boolean modelReady(boolean translated){AtomicBoolean ready=new AtomicBoolean();main(()->ready.set(!vm.busy && (!translated || vm.project().translatedCount()==2)));return ready.get();}
  @Before public void setup()throws Exception {
    context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    graph=((ReaderApplication)context.getApplicationContext()).getContainer();original=graph.selectionBinding();
    main(()->graph.setSelectionTranslation(request->{calls.incrementAndGet();return success(request,"译文:");},true));
    scenario=ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(activity->{activity.openVideo();activity.getSupportFragmentManager().executePendingTransactions();
      VideoFragment page=(VideoFragment)activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      vm=new ViewModelProvider(page,new ViewModelProvider.Factory(){
        @Override public <T extends ViewModel>T create(Class<T> type){throw new AssertionError("Expected retained model");}
      }).get(VideoViewModel.class);
    });
    await(()->modelReady(false));
    File video=File.createTempFile("video-fixture-",".mp4",context.getCacheDir());files.add(video);encode(video);
    File subtitles=File.createTempFile("video-fixture-",".srt",context.getCacheDir());files.add(subtitles);
    try(Writer writer=new OutputStreamWriter(new FileOutputStream(subtitles),StandardCharsets.UTF_8)){
      writer.write("1\n00:00:00,200 --> 00:00:01,200\nHello, world!\n\n2\n00:00:02,000 --> 00:00:03,200\nLet's read together.\n");
    }
    main(()->{vm.video(Uri.fromFile(video),"Generated test video");vm.importSubtitles(Uri.fromFile(subtitles),"test.srt");});
    await(()->{AtomicBoolean ready=new AtomicBoolean();main(()->ready.set(!vm.busy && vm.project().cues.size()==2));return ready.get();});
  }
  @After public void cleanup(){
    for(CountDownLatch release:releases)release.countDown();
    if(scenario!=null)scenario.close();
    main(()->graph.setSelectionTranslation(original.service,original.demo));
    for(File file:files)file.delete();
  }
  private static TranslationBatchResult success(TranslationBatchRequest request,String prefix){
    TranslationInput input=request.getItems().get(0);
    return new TranslationBatchResult(request.getTaskId(),request.getRequestId(),Collections.singletonList(
        new TranslationOutput(input.getParagraphId(),input.getSourceHash(),TranslationStatus.SUCCESS,
            prefix+input.getText(),"device-test","1",null)));
  }
  private void translate(){main(()->{try{vm.translate(vm.prepare());}catch(Exception error){throw new AssertionError(error);}});}
  @Test public void localPlaybackBilingualOverlaySingleCuePauseAndExport()throws Exception {
    translate();await(()->modelReady(true));assertEquals(2,calls.get());
    await(()->{AtomicBoolean ready=new AtomicBoolean();scenario.onActivity(activity->{
      Player player=((PlayerView)activity.findViewById(R.id.video_player)).getPlayer();
      ready.set(player!=null && player.getPlaybackState()==Player.STATE_READY);
    });return ready.get();});
    scenario.onActivity(activity->{RecyclerView rows=activity.findViewById(R.id.video_cues);
      assertEquals(2,rows.getAdapter().getItemCount());assertNotNull(rows.findViewHolderForAdapterPosition(0));
      rows.findViewHolderForAdapterPosition(0).itemView.performClick();
    });
    await(()->{AtomicBoolean ready=new AtomicBoolean();scenario.onActivity(activity->{
      TextView text=activity.findViewById(R.id.video_caption);
      ready.set(text.getVisibility()==View.VISIBLE && text.getText().toString().contains("Hello, world!")
          && text.getText().toString().contains("译文:"));
    });return ready.get();});
    android.graphics.Bitmap preview=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull(preview);
    try(OutputStream out=new FileOutputStream(new File(context.getCacheDir(),"video-stage1-preview.png"))){
      assertTrue(preview.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
    }finally{preview.recycle();}
    await(()->{AtomicBoolean ready=new AtomicBoolean();scenario.onActivity(activity->{
      Player player=((PlayerView)activity.findViewById(R.id.video_player)).getPlayer();
      ready.set(player!=null && !player.getPlayWhenReady() && player.getCurrentPosition()>=1200 && player.getCurrentPosition()<1550);
    });return ready.get();});
    File exported=File.createTempFile("video-export-",".vtt",context.getCacheDir());files.add(exported);
    main(()->vm.export(Uri.fromFile(exported),true,true));
    await(()->exported.length()>20);
    String result=new String(java.nio.file.Files.readAllBytes(exported.toPath()),StandardCharsets.UTF_8);
    assertTrue(result.startsWith("WEBVTT"));assertTrue(result.contains("模拟译文，非真实翻译"));
    assertTrue(result.contains("00:00:00.200 --> 00:00:01.200"));assertTrue(result.contains("Hello, world!"));
    assertEquals(2,new SubtitleCodec().acquire(result).size());
  }
  @Test public void switchingFromMockToOnlineDoesNotReuseMockAndCheckpointRestores()throws Exception {
    translate();await(()->modelReady(true));assertEquals(2,calls.get());
    main(()->graph.setSelectionTranslation(request->{calls.incrementAndGet();return success(request,"real:");},false));
    translate();await(()->modelReady(true));assertEquals(4,calls.get());
    main(()->{try{vm.translate(vm.prepare(true));}catch(Exception error){throw new AssertionError(error);}});
    await(()->modelReady(true));assertEquals(6,calls.get());
    main(()->{assertFalse(vm.project().demo);for(SubtitleSegment cue:vm.project().cues)assertTrue(cue.translatedText.startsWith("real:"));vm.checkpoint(900);});
    VideoProjectStore store=new VideoProjectStore(context);
    await(()->{VideoProject restored=store.load();return restored.positionMs==900 && restored.translatedCount()==2 && !restored.demo;});
    VideoProject restored=store.load();assertEquals(200,restored.cues.get(0).startMs);
    assertEquals("Hello, world!",restored.cues.get(0).sourceText);
  }
  @Test public void rotationKeepsRunningTaskAndRetainedModel()throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);releases.add(release);
    main(()->graph.setSelectionTranslation(request->{
      calls.incrementAndGet();entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){throw new AppException(ErrorCode.TRANSLATION_FAILED,"cancelled",false);}
      return success(request,"译文:");
    },true));
    translate();assertTrue(entered.await(5,TimeUnit.SECONDS));
    VideoViewModel before=vm;scenario.recreate();
    scenario.onActivity(activity->{VideoFragment page=(VideoFragment)activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      VideoViewModel retained=new ViewModelProvider(page).get(VideoViewModel.class);assertSame(before,retained);assertTrue(retained.translating);
    });
    assertEquals(1,calls.get());release.countDown();await(()->modelReady(true));assertEquals(2,calls.get());
  }
  @Test public void cancelledLateResponseCannotReplaceResumeCheckpoint()throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);releases.add(release);
    main(()->graph.setSelectionTranslation(request->{
      int call=calls.incrementAndGet();
      if(call==2){entered.countDown();boolean ready=false;
        while(!ready)try{ready=release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
      }
      return success(request,"call:"+call+":");
    },true));
    translate();assertTrue(entered.await(5,TimeUnit.SECONDS));
    await(()->{AtomicBoolean ready=new AtomicBoolean();main(()->ready.set(vm.project().translatedCount()==1));return ready.get();});
    main(()->vm.cancel());release.countDown();translate();await(()->modelReady(true));
    assertEquals(3,calls.get());
    main(()->{assertTrue(vm.project().cues.get(0).translatedText.startsWith("call:1:"));
      assertTrue(vm.project().cues.get(1).translatedText.startsWith("call:3:"));});
  }
  @Test public void recognizesEnglishFromActualVideoWithoutSubtitleFile()throws Exception {
    main(()->vm.recognize(.6f,1f));
    await(()->modelReady(false));
    main(()->{
      assertEquals("画面英文 OCR",vm.project().subtitleName);
      assertFalse(vm.project().cues.isEmpty());
      assertEquals(SubtitleSegment.TextSource.OCR,vm.project().cues.get(0).textSource);
      assertTrue(vm.project().cues.get(0).sourceText.toLowerCase(java.util.Locale.ROOT).contains("hello"));
    });
  }
  private static void encode(File output)throws Exception {
    MediaCodec codec=MediaCodec.createEncoderByType("video/avc");
    MediaMuxer muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    boolean muxing=false;int track=-1,frame=0;boolean finished=false;
    try{
      MediaFormat format=MediaFormat.createVideoFormat("video/avc",160,160);
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
      format.setInteger(MediaFormat.KEY_BIT_RATE,160000);format.setInteger(MediaFormat.KEY_FRAME_RATE,10);format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
      codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
      MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long deadline=System.currentTimeMillis()+30000;
      while(!finished && System.currentTimeMillis()<deadline){
        if(frame<=40){int index=codec.dequeueInputBuffer(10000);if(index>=0){
          ByteBuffer buffer=codec.getInputBuffer(index);buffer.clear();
          if(frame==40)codec.queueInputBuffer(index,0,0,frame*100000L,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
          else{byte[] pixels=new byte[160*160*3/2];
            android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(160,160,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(image);canvas.drawColor(android.graphics.Color.WHITE);
            android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);paint.setColor(android.graphics.Color.BLACK);paint.setTextSize(20);
            canvas.drawText("Hello world",12,140,paint);
            int[] colors=new int[160*160];image.getPixels(colors,0,160,0,0,160,160);image.recycle();
            for(int pixel=0;pixel<colors.length;pixel++)pixels[pixel]=(byte)(16+android.graphics.Color.red(colors[pixel])*219/255);
            Arrays.fill(pixels,160*160,pixels.length,(byte)128);buffer.put(pixels);codec.queueInputBuffer(index,0,pixels.length,frame*100000L,0);}
          frame++;
        }}
        int index=codec.dequeueOutputBuffer(info,10000);
        if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(codec.getOutputFormat());muxer.start();muxing=true;}
        else if(index>=0){ByteBuffer buffer=codec.getOutputBuffer(index);
          if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0 && info.size>0){assertTrue(muxing);buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer.writeSampleData(track,buffer,info);}
          finished=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);
        }
      }
      assertTrue("Video encoder did not finish",finished);
    }finally{codec.stop();codec.release();if(muxing)muxer.stop();muxer.release();}
  }
}
