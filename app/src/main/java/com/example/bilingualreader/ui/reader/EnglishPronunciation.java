package com.example.bilingualreader.ui.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.*;
import java.util.*;

/** User-triggered English speech. Only selected text is spoken; no paragraph or vendor key is sent. */
public final class EnglishPronunciation implements AutoCloseable {
  public enum State { IDLE, PREPARING, SPEAKING, UNAVAILABLE, ERROR }
  public interface Listener {void changed(State state);}
  private final Context context;
  private final Handler main=new Handler(Looper.getMainLooper());
  private Listener listener;
  private TextToSpeech engine;
  private String pending,current;
  private boolean ready,closed;
  private State state=State.IDLE;
  private int engineGeneration;
  public EnglishPronunciation(Context context,Listener listener) {
    this.context=context.getApplicationContext();this.listener=listener;
  }
  public State state(){return state;}
  public void play(String text) {
    if(closed)return;
    if(text==null || text.trim().isEmpty() || text.length()>TextToSpeech.getMaxSpeechInputLength()) {
      publish(State.ERROR);return;
    }
    if(state==State.SPEAKING || state==State.PREPARING){stop();return;}
    pending=text;
    if(ready){speak();return;}
    publish(State.PREPARING);
    if(engine!=null){engine.shutdown();engine=null;}
    int generation=++engineGeneration;
    try {engine=new TextToSpeech(context,status->main.post(()->initialize(status,generation)));}
    catch(RuntimeException unavailable){pending=null;publish(State.UNAVAILABLE);}
  }
  private void initialize(int status,int generation) {
    if(closed || engine==null || generation!=engineGeneration)return;
    if(status!=TextToSpeech.SUCCESS){pending=null;publish(State.UNAVAILABLE);return;}
    try {
      int language=engine.setLanguage(Locale.US);
      if(language<0)language=engine.setLanguage(Locale.UK);
      if(language<0)language=engine.setLanguage(Locale.ENGLISH);
      if(language<0){pending=null;publish(State.UNAVAILABLE);return;}
      Set<Voice> available=engine.getVoices();
      if(available!=null) {
        List<Voice> english=new ArrayList<>();
        for(Voice voice:available)if("en".equals(voice.getLocale().getLanguage()))english.add(voice);
        english.sort(Comparator.comparing(Voice::isNetworkConnectionRequired)
            .thenComparing(v->!"US".equals(v.getLocale().getCountry())));
        if(!english.isEmpty())engine.setVoice(english.get(0));
      }
      engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
      engine.setSpeechRate(.85f);
      engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        public void onStart(String id){deliver(id,State.SPEAKING);}
        public void onDone(String id){deliver(id,State.IDLE);}
        @SuppressWarnings("deprecation") public void onError(String id){deliver(id,State.ERROR);}
        public void onError(String id,int code){deliver(id,State.ERROR);}
      });
      ready=true;
      if(pending!=null)speak();else publish(State.IDLE);
    }catch(RuntimeException unavailable){pending=null;publish(State.UNAVAILABLE);}
  }
  private void speak() {
    String text=pending;pending=null;
    if(text==null || engine==null)return;
    current=UUID.randomUUID().toString();publish(State.SPEAKING);
    try {
      if(engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,current)==TextToSpeech.ERROR) {
        current=null;publish(State.ERROR);
      }
    }catch(RuntimeException unavailable){current=null;publish(State.ERROR);}
  }
  private void deliver(String id,State next) {
    main.post(()->{if(!closed && id.equals(current)){if(next!=State.SPEAKING)current=null;publish(next);}});
  }
  private void publish(State next){state=next;if(!closed && listener!=null)listener.changed(next);}
  public void stop() {
    current=null;pending=null;
    if(engine!=null)try{engine.stop();}catch(RuntimeException ignored){}
    if(!closed)publish(State.IDLE);
  }
  public String enginePackage(){return engine==null?null:engine.getDefaultEngine();}
  @Override public void close() {
    if(closed)return;
    stop();closed=true;listener=null;engineGeneration++;
    if(engine!=null){engine.shutdown();engine=null;}
  }
}
