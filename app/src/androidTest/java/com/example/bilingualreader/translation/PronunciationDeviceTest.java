package com.example.bilingualreader.translation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.ui.reader.EnglishPronunciation;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PronunciationDeviceTest {
  @Test public void englishEngineCompletesAndClosedPlayerCannotRestart()throws Exception {
    AtomicReference<EnglishPronunciation> player=new AtomicReference<>();
    List<EnglishPronunciation.State> states=new CopyOnWriteArrayList<>();CountDownLatch ended=new CountDownLatch(1);
    try {
      InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
        player.set(new EnglishPronunciation(InstrumentationRegistry.getInstrumentation().getTargetContext(),state->{
          states.add(state);
          if(state==EnglishPronunciation.State.UNAVAILABLE || state==EnglishPronunciation.State.ERROR
              || state==EnglishPronunciation.State.IDLE&&states.contains(EnglishPronunciation.State.SPEAKING))ended.countDown();
        }));player.get().play("bank");
      });
      assertTrue("Speech callback timed out",ended.await(30,TimeUnit.SECONDS));
      Assume.assumeFalse("English TTS data is not installed on this device",states.contains(EnglishPronunciation.State.UNAVAILABLE));
      assertFalse("Speech engine reported an error",states.contains(EnglishPronunciation.State.ERROR));
      assertTrue(states.contains(EnglishPronunciation.State.SPEAKING));
      assertEquals(EnglishPronunciation.State.IDLE,states.get(states.size()-1));
      InstrumentationRegistry.getInstrumentation().runOnMainSync(()->player.get().close());
      int count=states.size();
      InstrumentationRegistry.getInstrumentation().runOnMainSync(()->player.get().play("closed"));
      assertEquals(count,states.size());
    } finally {if(player.get()!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->player.get().close());}
  }
}
