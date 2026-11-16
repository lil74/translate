package com.example.bilingualreader.video;

import static org.junit.Assert.*;
import android.content.Context;
import android.net.Uri;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.translation.TranslationConfigStore;
import com.example.bilingualreader.viewmodel.VideoViewModel;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Opt-in live test. Only generated speech; config supplied privately at runtime, never in APK. */
@RunWith(AndroidJUnit4.class)
public final class SpeechDeviceTest {
  private static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
  @Test public void subtitleFreeVideoReachesRealChineseTranslation()throws Exception {
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    File credentials=new File(context.getCacheDir(),"asr-test-config.json");
    Assume.assumeTrue("Live ASR requires explicit private runtime configuration",credentials.isFile());
    JsonObject config=JsonParser.parseString(new String(java.nio.file.Files.readAllBytes(credentials.toPath()),StandardCharsets.UTF_8)).getAsJsonObject();
    AppContainer graph=((ReaderApplication)context.getApplicationContext()).getContainer();
    String oldMode=graph.translationConfig.mode(),oldEndpoint=graph.translationConfig.endpoint();
    String oldToken=oldEndpoint.isEmpty()?"":graph.translationConfig.tokenForEndpoint(oldEndpoint);
    File video=File.createTempFile("asr-live-",".mp4",context.getCacheDir());
    ViewModelStore models=new ViewModelStore();AtomicReference<VideoViewModel> model=new AtomicReference<>();
    try{
      try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("asr-sample.mp4");OutputStream out=new FileOutputStream(video)){
        byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1)out.write(bytes,0,count);
      }
      main(()->{try{
        graph.translationConfig.save(TranslationConfigStore.ONLINE,config.get("endpoint").getAsString(),config.get("token").getAsString());
        VideoViewModel vm=new VideoViewModel(context,graph);models.put("speech",vm);model.set(vm);
      }catch(Exception error){throw new AssertionError(error);}});
      long until=System.currentTimeMillis()+10000;
      AtomicBoolean ready=new AtomicBoolean();
      while(System.currentTimeMillis()<until){main(()->ready.set(!model.get().busy));if(ready.get())break;Thread.sleep(30);}
      assertTrue("Project initialization timed out",ready.get());
      main(()->{try{
        model.get().video(Uri.fromFile(video),"Generated ASR test video");
        model.get().recognizeSpeech(model.get().prepareSpeech());
      }catch(Exception error){throw new AssertionError(error);}});
      until=System.currentTimeMillis()+240000;ready.set(false);
      while(System.currentTimeMillis()<until){main(()->ready.set(!model.get().busy));if(ready.get())break;Thread.sleep(100);}
      assertTrue("ASR pipeline timed out",ready.get());
      main(()->{
        VideoViewModel vm=model.get();assertFalse(vm.message,vm.project().cues.isEmpty());
        assertEquals(vm.message,vm.project().cues.size(),vm.project().translatedCount());
        assertFalse(vm.project().demo);
        for(SubtitleSegment cue:vm.project().cues){
          assertEquals(SubtitleSegment.TextSource.ASR,cue.textSource);
          assertTrue(cue.startMs>=0 && cue.endMs>cue.startMs);
          assertEquals("qwen-mt",cue.provider);
          assertTrue(cue.translatedText.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN));
        }
      });
    }finally{
      main(()->{models.clear();try{
        if(!oldEndpoint.equals(config.get("endpoint").getAsString()))
          graph.translationConfig.save(TranslationConfigStore.ONLINE,config.get("endpoint").getAsString(),"");
        graph.translationConfig.save(oldMode,oldEndpoint,oldToken);
      }catch(Exception error){throw new AssertionError(error);}});
      video.delete();credentials.delete();
    }
  }
}
