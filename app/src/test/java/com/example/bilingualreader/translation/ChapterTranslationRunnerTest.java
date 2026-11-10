package com.example.bilingualreader.translation;

import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.TranslationRuntimeStore;
import com.example.bilingualreader.contract.translation.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ChapterTranslationRunnerTest {
  static Paragraph p(String id, int order, String text) {
    return new Paragraph(id, "chapter", order, BlockType.TEXT, text, TranslationValidation.sha256(text));
  }
  static TranslationBatchResult translated(TranslationBatchRequest r) {
    List<TranslationOutput> out = new ArrayList<>();
    for (TranslationInput i : r.getItems()) out.add(new TranslationOutput(i.getParagraphId(),
        i.getSourceHash(), TranslationStatus.SUCCESS, "译:" + i.getText(), "test", "1", null));
    Collections.reverse(out);
    return new TranslationBatchResult(r.getTaskId(), r.getRequestId(), out);
  }
  static ChapterTranslationRunner runner(Memory store, TranslationService service, AtomicLong time) {
    return new ChapterTranslationRunner(store, service, () -> false, time::get);
  }
  @Test public void batchLimitsAndUnicodeRoundTrip() {
    String text = ("Sentence 😀. ").repeat(1900);
    List<String> chunks = TranslationBatcher.chunks(text);
    assertTrue(chunks.size() > 1); assertEquals(text, String.join("", chunks));
    for (String chunk : chunks) {
      assertTrue(chunk.codePointCount(0, chunk.length()) <= 4000);
      assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
      assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
    }
    String unbroken = "😀".repeat(5001);
    assertEquals(unbroken, String.join("", TranslationBatcher.chunks(unbroken)));
    List<Paragraph> ps = new ArrayList<>();
    for (int i = 0; i < 20; i++) ps.add(p("p"+i, i, "a".repeat(700)));
    for (List<Paragraph> batch : TranslationBatcher.batches(ps)) {
      assertTrue(batch.size() <= 8);
      assertTrue(batch.stream().mapToInt(x -> x.getOriginalText().length()).sum() <= 4000);
    }
  }
  @Test public void reorderedResponsesAndCachedRepeat() throws Exception {
    List<Paragraph> ps = new ArrayList<>();
    for (int i = 0; i < 10; i++) ps.add(p("p"+i, i, "text"+i));
    Memory store = new Memory(ps); AtomicInteger requests = new AtomicInteger();
    TranslationService service = request -> { requests.incrementAndGet(); return translated(request); };
    assertEquals(ChapterTranslationRunner.Outcome.DONE, runner(store, service, new AtomicLong()).run(store.current));
    assertEquals(TaskState.COMPLETED, store.getTask(store.current).getState());
    assertEquals(2, requests.get());
    for (Paragraph paragraph : ps) assertEquals("译:"+paragraph.getOriginalText(), store.outputs.get(paragraph.getParagraphId()).getTranslatedText());
    String cached = store.prepareTask("chapter", "en", "zh-CN").getTaskId();
    runner(store, service, new AtomicLong()).run(cached);
    assertEquals(2, requests.get());
  }
  @Test public void missingItemFailsOnlyItAndUserRetrySkipsSuccess() throws Exception {
    Memory store = new Memory(Arrays.asList(p("a",0,"alpha"),p("b",1,"beta")));
    runner(store, request -> new TranslationBatchResult(request.getTaskId(), request.getRequestId(),
        Collections.singletonList(translated(request).getItems().get(0))), new AtomicLong()).run(store.current);
    assertEquals(TaskState.PARTIAL_FAILED, store.getTask(store.current).getState());
    String next = store.prepareTask("chapter","en","zh-CN").getTaskId();
    AtomicInteger count = new AtomicInteger();
    runner(store, request -> { count.addAndGet(request.getItems().size()); return translated(request); },
        new AtomicLong()).run(next);
    assertEquals(1,count.get()); assertEquals(TaskState.COMPLETED,store.getTask(next).getState());
  }
  @Test public void duplicateResponseNeverWritesSuccess() throws Exception {
    Memory store = new Memory(Collections.singletonList(p("a",0,"alpha")));
    runner(store, request -> {
      TranslationOutput item = translated(request).getItems().get(0);
      return new TranslationBatchResult(request.getTaskId(),request.getRequestId(),Arrays.asList(item,item));
    },new AtomicLong()).run(store.current);
    assertEquals(TaskState.FAILED,store.getTask(store.current).getState());
    assertEquals(TranslationStatus.FAILED,store.outputs.get("a").getStatus());
  }
  @Test public void retryBudgetSurvivesRunnerRecreation() throws Exception {
    Memory store = new Memory(Collections.singletonList(p("a",0,"alpha")));
    AtomicLong time = new AtomicLong(1000); AtomicInteger calls = new AtomicInteger();
    TranslationService unavailable = request -> {
      calls.incrementAndGet(); throw new AppException(ErrorCode.NETWORK_ERROR,"offline",true);
    };
    for (int attempt = 1; attempt <= 3; attempt++) {
      ChapterTranslationRunner.Outcome outcome = runner(store,unavailable,time).run(store.current);
      assertEquals(attempt < 3 ? ChapterTranslationRunner.Outcome.RETRY : ChapterTranslationRunner.Outcome.DONE,outcome);
      time.addAndGet(120_000);
    }
    runner(store,unavailable,time).run(store.current);
    assertEquals(3,calls.get()); assertEquals(TaskState.FAILED,store.getTask(store.current).getState());
  }
  @Test public void retryAfterDoesNotSpendAttemptsWhileWaiting() throws Exception {
    Memory store = new Memory(Collections.singletonList(p("a",0,"alpha")));
    AtomicLong time = new AtomicLong(1000); AtomicInteger calls = new AtomicInteger();
    TranslationService busy = request -> {
      calls.incrementAndGet(); throw new RetryAfterException(ErrorCode.RATE_LIMITED,"busy",500_000);
    };
    assertEquals(ChapterTranslationRunner.Outcome.RETRY,runner(store,busy,time).run(store.current));
    time.set(100_000);
    assertEquals(ChapterTranslationRunner.Outcome.RETRY,runner(store,busy,time).run(store.current));
    assertEquals(1,calls.get());
    time.set(500_001);
    runner(store,ChapterTranslationRunnerTest::translated,time).run(store.current);
    assertEquals(TaskState.COMPLETED,store.getTask(store.current).getState());
    assertEquals(61000,LibreTranslateService.retryAfter("60",1000));
    assertEquals(0,LibreTranslateService.retryAfter("invalid",1000));
    assertEquals(1445412480000L,LibreTranslateService.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT",0));
  }
  @Test public void cancellationRejectsInFlightResultAndCanResume() throws Exception {
    Memory store = new Memory(Collections.singletonList(p("a",0,"alpha")));
    String old = store.current;
    runner(store, request -> { store.cancelTask(old); return translated(request); },new AtomicLong()).run(old);
    assertEquals(TaskState.CANCELLED,store.getTask(old).getState()); assertTrue(store.outputs.isEmpty());
    String next = store.prepareTask("chapter","en","zh-CN").getTaskId();
    runner(store,ChapterTranslationRunnerTest::translated,new AtomicLong()).run(next);
    assertEquals(TaskState.COMPLETED,store.getTask(next).getState());
    assertNotEquals(old,next);
  }
  @Test public void longParagraphReassemblesUnderOriginalId() throws Exception {
    Paragraph original = p("long",0,"😀".repeat(8500));
    Memory store = new Memory(Collections.singletonList(original)); AtomicInteger chunks = new AtomicInteger();
    runner(store, request -> {
      chunks.incrementAndGet(); assertTrue(request.getItems().get(0).getParagraphId().startsWith("long#chunk-"));
      return translated(request);
    },new AtomicLong()).run(store.current);
    assertEquals(3,chunks.get()); assertEquals(1,store.outputs.size());
    assertEquals(original.getSourceHash(),store.outputs.get("long").getSourceHash());
    assertEquals(TaskState.COMPLETED,store.getTask(store.current).getState());
  }
  @Test public void failedLongChunkKeepsOtherParagraphs() throws Exception {
    Memory store = new Memory(Arrays.asList(p("long",0,"😀".repeat(8500)),p("ok",1,"short")));
    runner(store, request -> {
      if (request.getItems().get(0).getParagraphId().endsWith("#chunk-1"))
        throw new AppException(ErrorCode.TRANSLATION_FAILED,"bad chunk",false);
      return translated(request);
    },new AtomicLong()).run(store.current);
    assertEquals(TranslationStatus.FAILED,store.outputs.get("long").getStatus());
    assertEquals(TranslationStatus.SUCCESS,store.outputs.get("ok").getStatus());
    assertEquals(TaskState.PARTIAL_FAILED,store.getTask(store.current).getState());
  }
  @Test public void partialTransientResponseRetriesOnlyFailedInput() throws Exception {
    Memory store = new Memory(Arrays.asList(p("a",0,"alpha"),p("b",1,"beta")));
    AtomicLong clock = new AtomicLong();
    runner(store, request -> {
      List<TranslationOutput> out = new ArrayList<>(translated(request).getItems());
      out.set(0,new TranslationOutput("b",store.paragraphs.get(1).getSourceHash(),
          TranslationStatus.FAILED,null,"test",null,ErrorCode.TIMEOUT.name()));
      return new TranslationBatchResult(request.getTaskId(),request.getRequestId(),out);
    },clock).run(store.current);
    assertEquals(1,store.getTask(store.current).getSuccessCount()); clock.set(60_000);
    runner(store, request -> { assertEquals(1,request.getItems().size()); assertEquals("b",request.getItems().get(0).getParagraphId());
      return translated(request); },clock).run(store.current);
    assertEquals(TaskState.COMPLETED,store.getTask(store.current).getState());
  }
  @Test public void authenticationStopsAutomaticRequests() throws Exception {
    Memory store = new Memory(Collections.singletonList(p("a",0,"alpha")));
    try {
      runner(store, request -> { throw new AppException(ErrorCode.AUTH_FAILED,"bad auth",false); },
          new AtomicLong()).run(store.current);
      fail("authentication must escape to Worker for task-level failure");
    } catch (AppException expected) { assertEquals(ErrorCode.AUTH_FAILED,expected.getCode()); }
  }
  @Test public void fatalPartialResponseStillSavesKnownSuccesses() throws Exception {
    Memory store = new Memory(Arrays.asList(p("a",0,"alpha"),p("b",1,"beta")));
    try {
      runner(store, request -> new TranslationBatchResult(request.getTaskId(), request.getRequestId(),
          Arrays.asList(new TranslationOutput("a",store.paragraphs.get(0).getSourceHash(),
                  TranslationStatus.SUCCESS,"已完成","test",null,null),
              new TranslationOutput("b",store.paragraphs.get(1).getSourceHash(),
                  TranslationStatus.FAILED,null,"test",null,ErrorCode.QUOTA_EXCEEDED.name()))),
          new AtomicLong()).run(store.current);
      fail("quota failure must terminate the task");
    } catch (AppException error) { assertEquals(ErrorCode.QUOTA_EXCEEDED,error.getCode()); }
    assertEquals(TranslationStatus.SUCCESS,store.outputs.get("a").getStatus());
  }
  @Test public void stoppedWorkerDoesNotSaveLateResults() throws Exception {
    Memory store = new Memory(Collections.singletonList(p("a",0,"alpha")));
    AtomicBoolean stopped = new AtomicBoolean();
    new ChapterTranslationRunner(store, request -> { stopped.set(true); return translated(request); },
        stopped::get, () -> 0L).run(store.current);
    assertTrue(store.outputs.isEmpty());
    runner(store,ChapterTranslationRunnerTest::translated,new AtomicLong()).run(store.current);
    assertEquals(TaskState.COMPLETED,store.getTask(store.current).getState());
  }

  /** In-memory test double only; Room's transactions are exercised separately on Android. */
  static final class Memory implements TranslationRuntimeStore {
    final List<Paragraph> paragraphs;
    final Map<String,TranslationOutput> outputs = new HashMap<>();
    final Map<String,TranslationTask> tasks = new HashMap<>();
    final Map<String,Attempt> attempts = new HashMap<>();
    String current;
    Memory(List<Paragraph> paragraphs) { this.paragraphs=paragraphs; create(); }
    void create() {
      outputs.entrySet().removeIf(e -> e.getValue().getStatus()!=TranslationStatus.SUCCESS);
      current=UUID.randomUUID().toString(); update(current,outputs.size()==paragraphs.size()?TaskState.COMPLETED:TaskState.QUEUED);
    }
    void update(String id,TaskState state) {
      int ok=0,bad=0; for(TranslationOutput o:outputs.values()) if(o.getStatus()==TranslationStatus.SUCCESS)ok++;else bad++;
      tasks.put(id,new TranslationTask(id,"chapter","en","zh-CN",state,paragraphs.size(),ok,bad,0,0,null,null));
    }
    boolean active(String id) { TranslationTask t=tasks.get(id); return t!=null&&(t.getState()==TaskState.RUNNING||t.getState()==TaskState.QUEUED); }
    public TranslationTask prepareTask(String c,String s,String t) { if(!active(current))create();return tasks.get(current); }
    public List<Paragraph> getPendingParagraphs(String id) { return getRunnableParagraphs(id); }
    public List<Paragraph> getRunnableParagraphs(String id) {
      List<Paragraph> out=new ArrayList<>();for(Paragraph p:paragraphs)if(!outputs.containsKey(p.getParagraphId()))out.add(p);return out;
    }
    public void markTaskRunning(String id) { if(active(id))update(id,TaskState.RUNNING); }
    public void markBatchRunning(String id,List<String> ids) {}
    public void applyBatchResult(String id,TranslationBatchResult r) {
      if(!active(id))return;for(TranslationOutput o:r.getItems())outputs.put(o.getParagraphId(),o);update(id,TaskState.RUNNING);
    }
    public void markBatchFailed(String id,List<String> ids,String code,String message) {
      for(Paragraph p:paragraphs)if(ids.contains(p.getParagraphId()))outputs.put(p.getParagraphId(),
          new TranslationOutput(p.getParagraphId(),p.getSourceHash(),TranslationStatus.FAILED,null,"test",null,code));
    }
    public void finishTask(String id) {
      int ok=0;for(TranslationOutput o:outputs.values())if(o.getStatus()==TranslationStatus.SUCCESS)ok++;
      update(id,ok==paragraphs.size()?TaskState.COMPLETED:ok>0?TaskState.PARTIAL_FAILED:TaskState.FAILED);
    }
    public void failTask(String id,String code,String message) {update(id,TaskState.FAILED);}
    public void cancelTask(String id) {update(id,TaskState.CANCELLED);}
    public boolean isTaskCancelled(String id) {return !active(id);}
    public LiveData<TranslationTask> observeTask(String id) {return null;}
    public LiveData<TranslationTask> observeLatestTask(String id,String l) {return null;}
    public TranslationTask getTask(String id) {return tasks.get(id);}
    public List<TranslationTask> getActiveTasks() {return Collections.singletonList(tasks.get(current));}
    public List<TranslationTask> getBookTasks(String id) {return new ArrayList<>(tasks.values());}
    public void bindService(String id,String mode,String endpoint) {}
    public String[] getService(String id) {return new String[]{"test",""};}
    public Attempt getAttempt(String id) {return attempts.getOrDefault(id,new Attempt(0,0,null,null));}
    public boolean claimAttempt(String task,String id,long now) {
      Attempt old=getAttempt(id);if(!active(task)||old.count>=3||old.notBefore>now)return false;
      attempts.put(id,new Attempt(old.count+1,old.notBefore,old.errorCode,old.message));return true;
    }
    public void postponeAttempt(String task,String id,long until,String code,String message) {
      attempts.put(id,new Attempt(getAttempt(id).count,until,code,message));
    }
    public void markTaskWaiting(String id,String code,String message) {update(id,TaskState.QUEUED);}
  }
}
