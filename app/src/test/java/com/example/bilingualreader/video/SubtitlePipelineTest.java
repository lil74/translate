package com.example.bilingualreader.video;

import static org.junit.Assert.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.translation.TranslationValidation;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class SubtitlePipelineTest {
  private final SubtitleCodec codec=new SubtitleCodec();
  private SubtitleSegment cue(String id,String text){return new SubtitleSegment(id,1000,2500,text,
      SubtitleSegment.TextSource.SUBTITLE,null,null,null,null);}
  private TranslationBatchResult response(TranslationBatchRequest request,String text){
    TranslationInput input=request.getItems().get(0);
    return new TranslationBatchResult(request.getTaskId(),request.getRequestId(),Collections.singletonList(
        new TranslationOutput(input.getParagraphId(),input.getSourceHash(),TranslationStatus.SUCCESS,text,"test","1",null)));
  }
  @Test public void srtBomMultilineOverlapsAndStableIds()throws Exception{
    String file="\uFEFF2\r\n00:00:02,000 --> 00:00:04,000\r\n<i>second</i>\r\n\r\n1\r\n00:00:01,000 --> 00:00:03,000\r\nHello\r\nworld &amp; friends\r\n";
    List<SubtitleSegment> parsed=codec.acquire(file),again=codec.acquire(file);
    assertEquals(2,parsed.size());assertEquals(1000,parsed.get(0).startMs);
    assertEquals("Hello\nworld & friends\n",parsed.get(0).sourceText);
    assertEquals(parsed.get(0).id,again.get(0).id);assertNotEquals(parsed.get(0).id,parsed.get(1).id);
    assertEquals(TranslationValidation.sha256(parsed.get(0).sourceText),parsed.get(0).sourceHash());
    assertEquals("second",parsed.get(1).sourceText);
  }
  @Test public void vttHeadersNotesSettingsAndVoiceMarkup()throws Exception{
    List<SubtitleSegment> parsed=codec.acquire("WEBVTT\n\nNOTE note\nnot a cue\n\nSTYLE\n::cue { color:white; }\n\nintro\n00:01.250 --> 00:02.750 align:start\n<v Alice>Hello &lt;world&gt;</v>");
    assertEquals(1,parsed.size());assertEquals(1250,parsed.get(0).startMs);
    assertEquals("Hello <world>",parsed.get(0).sourceText);
  }
  @Test public void malformedOrUntimedContentNeverGetsInventedTimestamps()throws Exception{
    for(String file:new String[]{"hello world","1\n00:00:03,000 --> 00:00:02,000\nHi",
        "1\n00:61:00,000 --> 00:62:00,000\nHi","1\n00:00:01,000 --> 00:00:02,000\n ",
        "WEBVTT\n00:01.000 --> 00:02.000\nHi\n\n00:03.000 --> 00:04.000\nBye",
        "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:100\n\n00:01.000 --> 00:02.000\nHi"}){
      try{codec.acquire(file);fail(file);}catch(AppException expected){assertNotNull(expected.getUserMessage());}
    }
  }
  @Test public void importLimitRejectsWithoutTruncation()throws Exception{
    StringBuilder input=new StringBuilder();
    for(int i=0;i<=SubtitleCodec.MAX_CUES;i++)input.append(i).append("\n00:00:01,000 --> 00:00:02,000\nHi\n\n");
    try{codec.acquire(input.toString());fail();}catch(AppException expected){assertTrue(expected.getUserMessage().contains("5000"));}
  }
  @Test public void bilingualExportPreservesTimesAndRoundTripsEscapedText()throws Exception{
    SubtitleSegment input=cue("a","Hello <world>\n").translated("你好 & 世界\n","test","1");
    for(boolean vtt:new boolean[]{false,true}){
      String text=SubtitleCodec.export(Collections.singletonList(input),vtt,true);
      SubtitleSegment roundtrip=codec.acquire(text).get(0);
      assertEquals(input.startMs,roundtrip.startMs);assertEquals(input.endMs,roundtrip.endMs);
      assertEquals("Hello <world>\n你好 & 世界",roundtrip.sourceText.trim());
    }
  }
  @Test public void unfinishedExportFailsInsteadOfWritingMissingTranslations()throws Exception{
    try{SubtitleCodec.export(Collections.singletonList(cue("a","Hello")),false,true);fail();}
    catch(AppException expected){assertTrue(expected.getUserMessage().contains("未翻译"));}
  }
  @Test public void editingOnlyInvalidatesChangedCue()throws Exception{
    SubtitleSegment input=cue("a","Hello").translated("你好","test","1");
    assertEquals("你好",input.edit("Hello").translatedText);
    SubtitleSegment changed=input.edit("Goodbye");assertNull(changed.translatedText);
    assertEquals(input.id,changed.id);assertEquals(input.startMs,changed.startMs);
    assertNotEquals(input.sourceHash(),changed.sourceHash());
  }
  @Test public void exactUnicodeSplittingNeverDropsWhitespaceOrSplitsSurrogates(){
    String text="Hello "+"😀".repeat(4500)+" ".repeat(4500);
    List<String> parts=SubtitleTranslator.split(text);assertEquals(text,String.join("",parts));
    for(String part:parts){assertTrue(part.codePointCount(0,part.length())<=4000);
      assertFalse(Character.isLowSurrogate(part.charAt(0)));assertFalse(Character.isHighSurrogate(part.charAt(part.length()-1)));}
  }
  @Test public void translationUsesStableCueBindingAndIndependentRequests()throws Exception{
    Set<String> requests=new HashSet<>();List<SubtitleSegment> done=new ArrayList<>();
    new SubtitleTranslator().translate(Arrays.asList(cue("a","Hello"),cue("b","Bye")),request->{
      TranslationValidation.validateRequest(request);assertEquals("en",request.getSourceLanguage());
      assertEquals(TranslationBatchRequest.Purpose.SUBTITLE, request.getPurpose());
      assertTrue(request.getTaskId().startsWith("video:"));assertTrue(requests.add(request.getRequestId()));
      return response(request,"translated:"+request.getItems().get(0).getText());
    },"en","zh-CN",done::add);
    assertEquals(2,done.size());assertEquals("a",done.get(0).id);assertEquals(2500,done.get(0).endMs);
    assertEquals("translated:Hello",done.get(0).translatedText);
  }
  @Test public void completedCuesAreSkippedOnResumeAndReverseDirectionIsPassedThrough()throws Exception{
    AtomicInteger calls=new AtomicInteger();List<SubtitleSegment> done=new ArrayList<>();
    new SubtitleTranslator().translate(Arrays.asList(cue("a","你好").translated("Hello","test","1"),cue("b","再见")),request->{
      calls.incrementAndGet();assertEquals("zh-CN",request.getSourceLanguage());assertEquals("en",request.getTargetLanguage());
      return response(request,"Goodbye");
    },"zh-CN","en",done::add);
    assertEquals(1,calls.get());assertEquals("b",done.get(0).id);
  }
  @Test public void malformedHashStopsBeforeDeliveringAnyCue()throws Exception{
    List<SubtitleSegment> done=new ArrayList<>();
    try{new SubtitleTranslator().translate(Collections.singletonList(cue("a","hello")),request->new TranslationBatchResult(
        request.getTaskId(),request.getRequestId(),Collections.singletonList(new TranslationOutput(
        request.getItems().get(0).getParagraphId(),"wrong",TranslationStatus.SUCCESS,"fake","test","1",null))),"en","zh-CN",done::add);fail();}
    catch(AppException expected){assertTrue(done.isEmpty());}
  }
  @Test public void partialFailureKeepsCompletedCuesForRetry()throws Exception{
    List<SubtitleSegment> done=new ArrayList<>();AtomicInteger calls=new AtomicInteger();
    try{new SubtitleTranslator().translate(Arrays.asList(cue("a","Hello"),cue("b","Bye")),request->{
      if(calls.incrementAndGet()==2)throw new AppException(ErrorCode.NETWORK_ERROR,"offline",true);
      return response(request,"你好");
    },"en","zh-CN",done::add);fail();}catch(AppException expected){assertEquals("offline",expected.getUserMessage());}
    assertEquals(1,done.size());assertEquals("a",done.get(0).id);
  }
  @Test public void cancellationNeverCallsAnotherCue()throws Exception{
    AtomicInteger calls=new AtomicInteger();
    try{new SubtitleTranslator().translate(Arrays.asList(cue("a","Hello"),cue("b","Bye")),request->{
      calls.incrementAndGet();return response(request,"你好");
    },"en","zh-CN",cue->Thread.currentThread().interrupt());fail();}
    catch(AppException expected){assertEquals(1,calls.get());}finally{Thread.interrupted();}
  }
}
