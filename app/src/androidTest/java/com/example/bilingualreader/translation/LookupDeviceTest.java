package com.example.bilingualreader.translation;

import static org.junit.Assert.*;
import android.content.Context;
import android.widget.*;
import androidx.lifecycle.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.*;
import com.example.bilingualreader.contract.model.TranslationStatus;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.ui.MainActivity;
import com.example.bilingualreader.ui.lookup.LookupFragment;
import com.example.bilingualreader.viewmodel.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;

/** Controlled services only: no paid requests or private book text. */
@RunWith(AndroidJUnit4.class)
public final class LookupDeviceTest {
  private AppContainer graph;
  private AppContainer.SelectionBinding original;
  private final ViewModelStore store = new ViewModelStore();
  private final List<CountDownLatch> releases = new ArrayList<>();
  private final AtomicReference<UiState<String>> state = new AtomicReference<>();
  private final androidx.lifecycle.Observer<UiState<String>> observer = state::set;
  private LookupViewModel vm;
  private ActivityScenario<MainActivity> scenario;

  private static void main(Runnable action) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }
  private static void await(BooleanSupplier ready) throws Exception {
    long until = System.currentTimeMillis() + 10000;
    while (!ready.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(20);
    assertTrue("Lookup timed out", ready.getAsBoolean());
  }
  private static TranslationBatchResult success(TranslationBatchRequest request) {
    TranslationInput input = request.getItems().get(0);
    return new TranslationBatchResult(request.getTaskId(), request.getRequestId(),
        Collections.singletonList(new TranslationOutput(input.getParagraphId(), input.getSourceHash(),
            TranslationStatus.SUCCESS, "result:" + input.getText(), "test", "1", null)));
  }
  @Before public void setup() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    graph = ((ReaderApplication) context.getApplicationContext()).getContainer();
    original = graph.selectionBinding();
  }
  @After public void teardown() {
    for (CountDownLatch release : releases) release.countDown();
    if (scenario != null) scenario.close();
    main(() -> {
      if (vm != null) vm.result.removeObserver(observer);
      store.clear();
      graph.setSelectionTranslation(original.service, original.demo);
    });
  }
  private void model(TranslationService service) {
    main(() -> {
      graph.setSelectionTranslation(service, true);
      vm = new LookupViewModel(graph);
      store.put("lookup", vm);
      vm.result.observeForever(observer);
    });
  }
  private void query(String input) {
    main(() -> {
      try {
        vm.input = input;
        vm.invalidate();
        vm.translate(vm.prepare());
      } catch (Exception error) { throw new AssertionError(error); }
    });
  }
  @Test public void lateResponseCannotOverwriteEditedInputOrNextQuery() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    releases.add(release);
    model(request -> {
      if (request.getItems().get(0).getText().equals("old")) {
        entered.countDown();
        boolean done = false;
        while (!done) try { done = release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { /* deliberately uncancellable test provider */ }
      }
      return success(request);
    });
    query("old");
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    main(() -> { vm.input = "new"; vm.invalidate(); });
    assertNull(state.get());
    query("new");
    release.countDown();
    await(() -> state.get() != null && state.get().status == UiState.Status.SUCCESS);
    assertEquals("result:new", state.get().data);
  }
  @Test public void serviceSnapshotAndFreshRetryIdsAreIndependent() throws Exception {
    AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
    model(request -> { first.incrementAndGet(); return success(request); });
    AtomicReference<String> firstId = new AtomicReference<>();
    main(() -> {
      try {
        vm.input = "hello";
        LookupViewModel.Snapshot prepared = vm.prepare();
        firstId.set(prepared.request.getRequestId());
        graph.setSelectionTranslation(request -> { second.incrementAndGet(); return success(request); }, true);
        vm.translate(prepared);
      } catch (Exception error) { throw new AssertionError(error); }
    });
    await(() -> state.get() != null && state.get().status == UiState.Status.SUCCESS);
    assertEquals(1, first.get()); assertEquals(0, second.get());
    query("hello");
    await(() -> second.get() == 1 && state.get().status == UiState.Status.SUCCESS);
    main(() -> assertNotEquals(firstId.get(), vm.submitted.request.getRequestId()));
  }
  @Test public void editingInvalidatesUnconfirmedSnapshotWithoutCallingProvider() {
    AtomicInteger calls = new AtomicInteger();
    model(request -> { calls.incrementAndGet(); return success(request); });
    main(() -> {
      try {
        vm.input = "hello";
        LookupViewModel.Snapshot prepared = vm.prepare();
        vm.direction = LookupQuery.ZH_TO_EN;
        vm.invalidate();
        assertFalse(vm.isCurrent(prepared));
        vm.translate(prepared);
        assertNull(vm.result.getValue());
      } catch (Exception error) { throw new AssertionError(error); }
    });
    assertEquals(0, calls.get());
  }
  @Test public void backgroundCancelsPendingQueryWithoutAutomaticResend() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    releases.add(release);
    AtomicInteger calls = new AtomicInteger();
    main(() -> graph.setSelectionTranslation(request -> {
      calls.incrementAndGet(); entered.countDown();
      try { release.await(5, TimeUnit.SECONDS); }
      catch (InterruptedException ignored) {}
      return success(request);
    }, true));
    scenario = ActivityScenario.launch(MainActivity.class);
    AtomicReference<LookupViewModel> current = new AtomicReference<>();
    scenario.onActivity(activity -> {
      activity.openLookup();
      activity.getSupportFragmentManager().executePendingTransactions();
      LookupFragment page = (LookupFragment) activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      current.set(new ViewModelProvider(page, new ViewModelFactory(graph)).get(LookupViewModel.class));
      ((EditText) page.requireView().findViewById(R.id.lookup_input)).setText("hello");
      page.requireView().findViewById(R.id.lookup_submit).performClick();
    });
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    scenario.moveToState(Lifecycle.State.CREATED);
    main(() -> assertEquals(UiState.Status.ERROR, current.get().result.getValue().status));
    release.countDown();
    scenario.moveToState(Lifecycle.State.RESUMED);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    main(() -> assertEquals(UiState.Status.ERROR, current.get().result.getValue().status));
    assertEquals(1, calls.get());
  }
  @Test public void rotationRetainsDirectionAndDoesNotRepeatRequestOrSaveHistory() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    main(() -> graph.setSelectionTranslation(request -> { calls.incrementAndGet(); return success(request); }, true));
    scenario = ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(activity -> {
      activity.openLookup();
      activity.getSupportFragmentManager().executePendingTransactions();
      LookupFragment page = (LookupFragment) activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      ((Spinner) page.requireView().findViewById(R.id.lookup_direction)).setSelection(LookupQuery.ZH_TO_EN);
    });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    AtomicReference<LookupViewModel> retained = new AtomicReference<>();
    scenario.onActivity(activity -> {
      LookupFragment page = (LookupFragment) activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      retained.set(new ViewModelProvider(page, new ViewModelFactory(graph)).get(LookupViewModel.class));
      ((EditText) page.requireView().findViewById(R.id.lookup_input)).setText(" 你好\n世界 ");
      page.requireView().findViewById(R.id.lookup_submit).performClick();
    });
    await(() -> calls.get() == 1);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    scenario.recreate();
    scenario.onActivity(activity -> {
      LookupFragment page = (LookupFragment) activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      LookupViewModel current = new ViewModelProvider(page, new ViewModelFactory(graph)).get(LookupViewModel.class);
      assertSame(retained.get(), current);
      assertEquals(LookupQuery.ZH_TO_EN, current.direction);
      assertEquals(" 你好\n世界 ", current.input);
      assertTrue(activity.getSharedPreferences("lookup_history", Context.MODE_PRIVATE).getAll().isEmpty());
    });
    assertEquals(1, calls.get());
  }
}
