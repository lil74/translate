package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SelectionTranslationServiceTest {
  private static TranslationBatchRequest request(String selected) {
    String id = "selection:" + UUID.randomUUID();
    return new TranslationBatchRequest(id, UUID.randomUUID().toString(), "en", "zh-CN",
        Collections.singletonList(new TranslationInput(id, TranslationValidation.sha256(selected), selected)));
  }
  private static TranslationBatchResult reply(TranslationBatchRequest r, String text) {
    TranslationInput i = r.getItems().get(0);
    return new TranslationBatchResult(r.getTaskId(), r.getRequestId(), Collections.singletonList(
        new TranslationOutput(i.getParagraphId(), i.getSourceHash(), TranslationStatus.SUCCESS,
            text, "test", "1", null)));
  }
  private interface Checked { void run() throws Exception; }
  private static void invalid(Checked action) throws Exception {
    try { action.run(); fail("Expected invalid response"); }
    catch (AppException e) { assertEquals(ErrorCode.INVALID_RESPONSE, e.getCode()); }
  }
  @Test public void selectedTextAndTransientIdsArePreservedWithoutParagraphLookup() throws Exception {
    String selected = "  Don’t stop — now!\n😀  ";
    TranslationBatchRequest request = request(selected);
    IsolatedSelectionTranslationService service = new IsolatedSelectionTranslationService(() -> r -> {
      assertEquals(selected, r.getItems().get(0).getText());
      assertEquals(TranslationValidation.sha256(selected), r.getItems().get(0).getSourceHash());
      assertEquals(r.getTaskId(), r.getItems().get(0).getParagraphId());
      assertTrue(r.getTaskId().startsWith("selection:"));
      return reply(r, "所选内容的译文");
    });
    TranslationBatchResult result = service.translate(request);
    assertEquals(request.getTaskId(), result.getTaskId());
    assertEquals(request.getRequestId(), result.getRequestId());
    assertEquals("所选内容的译文", result.getItems().get(0).getTranslatedText());
  }
  @Test public void eachQueryUsesItsOwnAdapter() throws Exception {
    AtomicInteger adapters = new AtomicInteger();
    IsolatedSelectionTranslationService service = new IsolatedSelectionTranslationService(() -> {
      int instance = adapters.incrementAndGet();
      return r -> reply(r, "adapter-" + instance);
    });
    assertEquals("adapter-1", service.translate(request("word")).getItems().get(0).getTranslatedText());
    assertEquals("adapter-2", service.translate(request("sentence.")).getItems().get(0).getTranslatedText());
  }
  @Test public void cancellationOfOneConcurrentQueryCannotPoisonAnother() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicInteger adapters = new AtomicInteger();
    IsolatedSelectionTranslationService service = new IsolatedSelectionTranslationService(() -> {
      adapters.incrementAndGet();
      return r -> {
        if (r.getItems().get(0).getText().equals("old")) {
          started.countDown();
          try { new CountDownLatch(1).await(); }
          catch (InterruptedException e) { Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.TRANSLATION_FAILED, "cancelled", false); }
        }
        return reply(r, "independent");
      };
    });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> old = pool.submit(() -> { try { service.translate(request("old")); } catch(AppException expected) {} });
      assertTrue(started.await(3, TimeUnit.SECONDS));
      old.cancel(true);
      Future<String> next = pool.submit(() -> service.translate(request("new")).getItems().get(0).getTranslatedText());
      assertEquals("independent", next.get(3, TimeUnit.SECONDS));
      assertEquals(2, adapters.get());
    } finally { pool.shutdownNow(); }
  }
  @Test public void wrongIdsAndHashesNeverBecomeSuccess() throws Exception {
    for (boolean wrongHash : new boolean[]{false, true}) {
      IsolatedSelectionTranslationService service = new IsolatedSelectionTranslationService(() -> r -> {
        TranslationInput input = r.getItems().get(0);
        return new TranslationBatchResult(r.getTaskId(), r.getRequestId(), Collections.singletonList(
            new TranslationOutput(wrongHash ? input.getParagraphId() : "wrong",
                wrongHash ? "wrong" : input.getSourceHash(), TranslationStatus.SUCCESS, "text", "test", null, null)));
      });
      invalid(() -> service.translate(request("word")));
    }
  }
  @Test public void emptyAndMissingResultsAreNotSuccessful() throws Exception {
    IsolatedSelectionTranslationService empty = new IsolatedSelectionTranslationService(() -> r -> reply(r, " "));
    invalid(() -> empty.translate(request("word")));
    IsolatedSelectionTranslationService missing = new IsolatedSelectionTranslationService(() -> r ->
        new TranslationBatchResult(r.getTaskId(), r.getRequestId(), Collections.emptyList()));
    assertEquals(TranslationStatus.FAILED, missing.translate(request("word")).getItems().get(0).getStatus());
  }
  @Test public void multipleItemsAndOversizedSelectionsAreRejectedWithoutCallingProvider() throws Exception {
    AtomicInteger adapters = new AtomicInteger();
    IsolatedSelectionTranslationService service = new IsolatedSelectionTranslationService(() -> {
      adapters.incrementAndGet(); return r -> reply(r, "unexpected");
    });
    TranslationBatchRequest first = request("word");
    List<TranslationInput> items = Arrays.asList(first.getItems().get(0), request("other").getItems().get(0));
    invalid(() -> service.translate(new TranslationBatchRequest(first.getTaskId(), first.getRequestId(), "en", "zh-CN", items)));
    invalid(() -> service.translate(request("😀".repeat(4001))));
    assertEquals(0, adapters.get());
  }
  @Test public void providerUserMessageIsPreservedAndRetryHasFreshIds() throws Exception {
    List<String> ids = new ArrayList<>();
    IsolatedSelectionTranslationService service = new IsolatedSelectionTranslationService(() -> r -> {
      ids.add(r.getTaskId());
      if(ids.size()==1)throw new AppException(ErrorCode.TIMEOUT,"服务读取超时，请重试",true);
      return reply(r,"retry succeeded");
    });
    try { service.translate(request("word")); fail("Expected timeout"); }
    catch(AppException e) { assertEquals("服务读取超时，请重试",e.getUserMessage()); }
    assertEquals("retry succeeded",service.translate(request("word")).getItems().get(0).getTranslatedText());
    assertNotEquals(ids.get(0),ids.get(1));
  }
  @SuppressWarnings("unchecked") private static <T> T stub(Class<T> type) {
    return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
        (object, method, arguments) -> null);
  }
  private static com.example.bilingualreader.di.AppContainer container(boolean chapterDemo,
      TranslationService selection, boolean selectionDemo) {
    return new com.example.bilingualreader.di.AppContainer(
        stub(com.example.bilingualreader.contract.repository.BookRepository.class),
        stub(com.example.bilingualreader.contract.repository.ReaderRepository.class),
        stub(com.example.bilingualreader.contract.repository.SettingsRepository.class),
        stub(TranslationCoordinator.class), chapterDemo, selection, selectionDemo);
  }
  @Test public void selectionPrivacyFlagIsIndependentOfChapterDemo() {
    com.example.bilingualreader.di.AppContainer graph = container(true, r -> reply(r,"online"), false);
    try {
      assertTrue(graph.demo); assertFalse(graph.selectionDemo); assertFalse(graph.selectionBinding().demo);
    } finally { graph.io.shutdownNow(); }
    graph = container(false, r -> reply(r,"mock"), true);
    try { assertFalse(graph.demo); assertTrue(graph.selectionBinding().demo); }
    finally { graph.io.shutdownNow(); }
  }
  @Test public void openSheetBindingDoesNotChangeWithNewConfiguration() throws Exception {
    com.example.bilingualreader.di.AppContainer graph = container(false,r -> reply(r,"old online"),false);
    try {
      com.example.bilingualreader.di.AppContainer.SelectionBinding openSheet = graph.selectionBinding();
      graph.setSelectionTranslation(r -> reply(r,"new mock"),true);
      assertFalse(openSheet.demo); assertTrue(graph.selectionDemo);
      assertEquals("old online",openSheet.service.translate(request("text")).getItems().get(0).getTranslatedText());
      assertEquals("new mock",graph.selectionBinding().service.translate(request("text")).getItems().get(0).getTranslatedText());
    } finally { graph.io.shutdownNow(); }
  }
  @Test public void originalFiveArgumentConstructorKeepsSelectionUnconfigured() throws Exception {
    com.example.bilingualreader.di.AppContainer graph = new com.example.bilingualreader.di.AppContainer(
        stub(com.example.bilingualreader.contract.repository.BookRepository.class),
        stub(com.example.bilingualreader.contract.repository.ReaderRepository.class),
        stub(com.example.bilingualreader.contract.repository.SettingsRepository.class),
        stub(TranslationCoordinator.class),true);
    try {
      assertFalse(graph.selectionDemo);
      try { graph.selectionTranslation.translate(request("text")); fail("Must not reuse chapter coordinator"); }
      catch(AppException expected) { assertTrue(expected.getUserMessage().contains("尚未配置")); }
    } finally { graph.io.shutdownNow(); }
  }
}
