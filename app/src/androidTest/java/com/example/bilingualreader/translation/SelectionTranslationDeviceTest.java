package com.example.bilingualreader.translation;

import android.content.Context;
import android.widget.TextView;
import androidx.lifecycle.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.ui.MainActivity;
import com.example.bilingualreader.ui.reader.SelectionTranslationSheet;
import com.example.bilingualreader.viewmodel.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Local deterministic providers only; never sends book text to an external service. */
@RunWith(AndroidJUnit4.class)
public class SelectionTranslationDeviceTest {
  private AppContainer graph;
  private AppContainer.SelectionBinding original;
  private ActivityScenario<MainActivity> scenario;
  private final ViewModelStore models = new ViewModelStore();
  private final List<CountDownLatch> releases = new ArrayList<>();
  private final List<AtomicReference<UiState<String>>> states = new ArrayList<>();
  private final List<SelectionTranslationViewModel> observed = new ArrayList<>();
  private final List<androidx.lifecycle.Observer<UiState<String>>> observers = new ArrayList<>();

  @Before public void setup() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    graph = ((ReaderApplication) context.getApplicationContext()).getContainer();
    original = graph.selectionBinding();
  }
  @After public void teardown() {
    for (CountDownLatch release : releases) release.countDown();
    if (scenario != null) scenario.close();
    main(() -> {
      for (int i = 0; i < observed.size(); i++) observed.get(i).result.removeObserver(observers.get(i));
      models.clear();
      graph.setSelectionTranslation(original.service, original.demo);
    });
  }
  private static void main(Runnable action) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }
  private static void await(BooleanSupplier condition) throws Exception {
    long end = System.currentTimeMillis() + 10000;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(20);
    assertTrue("Timed out awaiting selection state", condition.getAsBoolean());
  }
  private static TranslationBatchResult success(TranslationBatchRequest request) {
    TranslationInput input = request.getItems().get(0);
    return new TranslationBatchResult(request.getTaskId(), request.getRequestId(),
        Collections.singletonList(new TranslationOutput(input.getParagraphId(), input.getSourceHash(),
            TranslationStatus.SUCCESS, "译文:" + input.getText(), "device-test", "1", null)));
  }
  private SelectionTranslationViewModel model(TranslationService service) {
    AtomicReference<SelectionTranslationViewModel> ref = new AtomicReference<>();
    main(() -> {
      SelectionTranslationViewModel vm = new SelectionTranslationViewModel(service, false);
      models.put(UUID.randomUUID().toString(), vm);
      AtomicReference<UiState<String>> state = new AtomicReference<>();
      androidx.lifecycle.Observer<UiState<String>> observer = state::set;
      vm.result.observeForever(observer);
      observed.add(vm); observers.add(observer); states.add(state); ref.set(vm);
    });
    return ref.get();
  }
  private UiState<String> state() { return states.get(states.size() - 1).get(); }
  private void showSheet(String selected) {
    scenario = ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(activity -> {
      Paragraph paragraph = new Paragraph("fixture", "fixture", 0, BlockType.TEXT,
          selected, TranslationValidation.sha256(selected));
      SelectionTranslationSheet.create(paragraph, 0, selected.length())
          .showNow(activity.getSupportFragmentManager(), SelectionTranslationSheet.TAG);
    });
  }
  private void click(int id) {
    scenario.onActivity(activity -> {
      SelectionTranslationSheet sheet = (SelectionTranslationSheet) activity.getSupportFragmentManager()
          .findFragmentByTag(SelectionTranslationSheet.TAG);
      assertNotNull(sheet); assertTrue(sheet.requireView().findViewById(id).performClick());
    });
  }
  private String sheetText(int id) {
    AtomicReference<String> text = new AtomicReference<>("");
    scenario.onActivity(activity -> {
      SelectionTranslationSheet sheet = (SelectionTranslationSheet) activity.getSupportFragmentManager()
          .findFragmentByTag(SelectionTranslationSheet.TAG);
      assertNotNull(sheet);
      text.set(((TextView) sheet.requireView().findViewById(id)).getText().toString());
    });
    return text.get();
  }

  @Test public void onlineSheetRequiresExplicitClickAndPreservesSelectedText() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<TranslationBatchRequest> captured = new AtomicReference<>();
    main(() -> graph.setSelectionTranslation(request -> {
      calls.incrementAndGet(); captured.set(request); return success(request);
    }, false));
    String selected = "  Hello, world!\n\uD83D\uDE00  ";
    showSheet(selected);
    assertEquals(0, calls.get());
    assertEquals(selected, sheetText(R.id.selection_source));
    scenario.recreate();
    assertEquals(0, calls.get());
    click(R.id.selection_retry);
    await(() -> calls.get() == 1);
    await(() -> sheetText(R.id.selection_result).equals("译文:" + selected));
    TranslationBatchRequest request = captured.get();
    assertEquals(1, request.getItems().size());
    assertEquals(selected, request.getItems().get(0).getText());
    assertEquals(TranslationValidation.sha256(selected), request.getItems().get(0).getSourceHash());
    assertTrue(request.getTaskId().startsWith("selection:"));
  }

  @Test public void rotatingMockSheetKeepsResultAndOriginalModeWithoutResending() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    main(() -> graph.setSelectionTranslation(request -> { calls.incrementAndGet(); return success(request); }, true));
    showSheet("Hello");
    await(() -> sheetText(R.id.selection_result).equals("译文:Hello"));
    String notice = sheetText(R.id.selection_notice);
    main(() -> graph.setSelectionTranslation(request -> {
      throw new AssertionError("Existing sheet must retain its binding");
    }, false));
    scenario.recreate();
    assertEquals(notice, sheetText(R.id.selection_notice));
    assertEquals("译文:Hello", sheetText(R.id.selection_result));
    assertEquals(1, calls.get());
  }

  @Test public void cancelledLateResponseCannotReplaceNewSelection() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    releases.add(release);
    SelectionTranslationViewModel vm = model(request -> {
      if (request.getItems().get(0).getText().equals("old")) {
        entered.countDown();
        boolean done = false;
        while (!done) try { done = release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { /* Deliberately emulate an uncooperative provider. */ }
      }
      return success(request);
    });
    main(() -> vm.translate("old")); assertTrue(entered.await(5, TimeUnit.SECONDS));
    main(() -> vm.translate("new")); release.countDown();
    await(() -> state() != null && state().status == UiState.Status.SUCCESS);
    assertEquals("译文:new", state().data);
  }

  @Test public void failureWaitsForUserRetryAndRetryHasFreshIds() throws Exception {
    List<TranslationBatchRequest> requests = new CopyOnWriteArrayList<>();
    SelectionTranslationViewModel vm = model(request -> {
      requests.add(request);
      if (requests.size() == 1) throw new AppException(ErrorCode.TRANSLATION_FAILED, "测试失败", true);
      return success(request);
    });
    main(() -> vm.translate("Hello"));
    await(() -> state() != null && state().status == UiState.Status.ERROR);
    assertEquals("测试失败", state().message);
    assertEquals(1, requests.size());
    main(() -> vm.translate("Hello"));
    await(() -> state().status == UiState.Status.SUCCESS);
    assertEquals(2, requests.size());
    assertNotEquals(requests.get(0).getTaskId(), requests.get(1).getTaskId());
    assertNotEquals(requests.get(0).getRequestId(), requests.get(1).getRequestId());
  }

  @Test public void unicodeLimitRejectsOver4000WithoutTruncationOrProviderCall() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    SelectionTranslationViewModel vm = model(request -> { calls.incrementAndGet(); return success(request); });
    String emoji = new String(Character.toChars(0x1F600));
    String accepted = String.join("", Collections.nCopies(4000, emoji));
    main(() -> vm.translate(accepted));
    await(() -> state() != null && state().status == UiState.Status.SUCCESS);
    assertEquals("译文:" + accepted, state().data);
    main(() -> vm.translate(accepted + emoji));
    assertEquals(UiState.Status.ERROR, state().status);
    assertEquals(1, calls.get());
  }
}
