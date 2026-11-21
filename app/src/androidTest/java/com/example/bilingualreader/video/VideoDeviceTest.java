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
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VideoDeviceTest {
  private Context context;
  private AppContainer graph;
  private AppContainer.SelectionBinding original;
  private ActivityScenario<MainActivity> scenario;
  private VideoViewModel vm;
  private VideoProject previousProject;
  private int previousCaptionSize, previousCaptionPosition;
  private boolean hadCaptionSize, hadCaptionPosition;
  private final List<File> files = new ArrayList<>();
  private final List<CountDownLatch> releases = new ArrayList<>();
  private final AtomicInteger calls = new AtomicInteger();

  private interface Check {
    boolean ready() throws Exception;
  }

  private static void await(Check condition) throws Exception {
    long until = System.currentTimeMillis() + 15000;
    while (!condition.ready() && System.currentTimeMillis() < until) Thread.sleep(25);
    assertTrue("Video condition timed out", condition.ready());
  }

  private static void main(Runnable action) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  private boolean modelReady(boolean translated) {
    AtomicBoolean ready = new AtomicBoolean();
    main(() -> ready.set(!vm.busy && (!translated || vm.project().translatedCount() == 2)));
    return ready.get();
  }

  @Before
  public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    graph = ((ReaderApplication) context.getApplicationContext()).getContainer();
    previousProject = new VideoProjectStore(context).load();
    android.content.SharedPreferences style =
        context.getSharedPreferences("video_caption_style", Context.MODE_PRIVATE);
    hadCaptionSize = style.contains("size");
    hadCaptionPosition = style.contains("position");
    previousCaptionSize = style.getInt("size", 16);
    previousCaptionPosition = style.getInt("position", 2);
    style.edit().putInt("size", 16).putInt("position", 2).commit();
    original = graph.selectionBinding();
    main(
        () ->
            graph.setSelectionTranslation(
                request -> {
                  calls.incrementAndGet();
                  return success(request, "译文:");
                },
                true));
    scenario = ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(
        activity -> {
          activity.openVideo();
          activity.getSupportFragmentManager().executePendingTransactions();
          VideoFragment page =
              (VideoFragment)
                  activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
          vm =
              new ViewModelProvider(
                      page,
                      new ViewModelProvider.Factory() {
                        @Override
                        public <T extends ViewModel> T create(Class<T> type) {
                          throw new AssertionError("Expected retained model");
                        }
                      })
                  .get(VideoViewModel.class);
        });
    await(() -> modelReady(false));
    File video = File.createTempFile("video-fixture-", ".mp4", context.getCacheDir());
    files.add(video);
    encode(video);
    File subtitles = File.createTempFile("video-fixture-", ".srt", context.getCacheDir());
    files.add(subtitles);
    try (Writer writer =
        new OutputStreamWriter(new FileOutputStream(subtitles), StandardCharsets.UTF_8)) {
      writer.write(
          "1\n"
              + "00:00:00,200 --> 00:00:01,200\n"
              + "Hello, world!\n\n"
              + "2\n"
              + "00:00:02,000 --> 00:00:03,200\n"
              + "Let's read together.\n");
    }
    main(
        () -> {
          vm.video(Uri.fromFile(video), "Generated test video");
          vm.importSubtitles(Uri.fromFile(subtitles), "test.srt");
        });
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          main(() -> ready.set(!vm.busy && vm.project().cues.size() == 2));
          return ready.get();
        });
  }

  @After
  public void cleanup() throws Exception {
    for (CountDownLatch release : releases) release.countDown();
    if (scenario != null) scenario.close();
    main(() -> graph.setSelectionTranslation(original.service, original.demo));
    if (previousProject != null)
      VideoProjectStore.IO
          .submit(
              () -> {
                try {
                  VideoProjectStore store = new VideoProjectStore(context);
                  for (File file : files)
                    if (file.getName().endsWith(".mp4"))
                      store.delete(Uri.fromFile(file).toString());
                  store.save(previousProject);
                } catch (IOException error) {
                  throw new RuntimeException(error);
                }
              })
          .get(15, TimeUnit.SECONDS);
    android.content.SharedPreferences.Editor style =
        context.getSharedPreferences("video_caption_style", Context.MODE_PRIVATE).edit();
    if (hadCaptionSize) style.putInt("size", previousCaptionSize);
    else style.remove("size");
    if (hadCaptionPosition) style.putInt("position", previousCaptionPosition);
    else style.remove("position");
    style.commit();
    for (File file : files) file.delete();
  }

  private static TranslationBatchResult success(TranslationBatchRequest request, String prefix) {
    TranslationInput input = request.getItems().get(0);
    return new TranslationBatchResult(
        request.getTaskId(),
        request.getRequestId(),
        Collections.singletonList(
            new TranslationOutput(
                input.getParagraphId(),
                input.getSourceHash(),
                TranslationStatus.SUCCESS,
                prefix + input.getText(),
                "device-test",
                "1",
                null)));
  }

  private void translate() {
    main(
        () -> {
          try {
            vm.translate(vm.prepare());
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
  }

  @Test
  public void fullscreenSubtitlePanelSharesPortraitSettingsAndBackClosesPanelFirst()
      throws Exception {
    scenario.onActivity(activity -> activity.findViewById(R.id.video_fullscreen).performClick());
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                View stage = activity.findViewById(R.id.video_stage);
                Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
                ready.set(
                    vm.fullScreen
                        && stage.getWidth() > stage.getHeight()
                        && player != null
                        && player.getPlaybackState() == Player.STATE_READY);
              });
          return ready.get();
        });
    scenario.onActivity(
        activity -> {
          activity.findViewById(R.id.video_player_subtitles).performClick();
          View drawer = activity.findViewById(R.id.video_subtitle_drawer),
              stage = activity.findViewById(R.id.video_stage);
          assertEquals(View.VISIBLE, drawer.getVisibility());
          assertFalse(((PlayerView) activity.findViewById(R.id.video_player)).getUseController());
          assertTrue(drawer.getWidth() < stage.getWidth() / 2);
          assertEquals(View.GONE, activity.findViewById(R.id.video_tools_panel).getVisibility());
        });
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                RecyclerView rows = activity.findViewById(R.id.video_drawer_cues);
                ready.set(
                    rows.getAdapter().getItemCount() == 2
                        && rows.findViewHolderForAdapterPosition(1) != null);
              });
          return ready.get();
        });
    scenario.onActivity(
        activity -> {
          RecyclerView rows = activity.findViewById(R.id.video_drawer_cues);
          rows.findViewHolderForAdapterPosition(1).itemView.performClick();
          Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
          assertTrue(player.getPlayWhenReady());
          player.pause();
        });
    await(
        () -> {
          AtomicBoolean located = new AtomicBoolean();
          scenario.onActivity(
              activity ->
                  located.set(
                      ((PlayerView) activity.findViewById(R.id.video_player))
                              .getPlayer()
                              .getCurrentPosition()
                          >= 1900));
          return located.get();
        });
    screenshot("video-fullscreen-cue-list.png");
    scenario.onActivity(
        activity -> {
          activity.findViewById(R.id.video_drawer_style_tab).performClick();
          activity.findViewById(R.id.video_caption_larger).performClick();
          activity.findViewById(R.id.video_caption_top).performClick();
          TextView caption = activity.findViewById(R.id.video_caption);
          assertEquals(
              18f,
              caption.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity,
              .1f);
          assertEquals(
              android.view.Gravity.TOP,
              ((android.widget.FrameLayout.LayoutParams) caption.getLayoutParams()).gravity
                  & android.view.Gravity.VERTICAL_GRAVITY_MASK);
          activity.findViewById(R.id.video_drawer_hide).performClick();
          assertEquals(
              "显示译文",
              ((TextView) activity.findViewById(R.id.video_hide_translation)).getText().toString());
        });
    screenshot("video-fullscreen-caption-style.png");
    scenario.recreate();
    await(
        () -> {
          AtomicBoolean restored = new AtomicBoolean();
          scenario.onActivity(
              activity ->
                  restored.set(
                      vm.fullScreen
                          && activity.findViewById(R.id.video_subtitle_drawer).getVisibility()
                              == View.VISIBLE));
          return restored.get();
        });
    scenario.onActivity(
        activity -> {
          assertEquals(
              "18 sp",
              ((TextView) activity.findViewById(R.id.video_caption_size)).getText().toString());
          activity.getOnBackPressedDispatcher().onBackPressed();
          assertTrue(vm.fullScreen);
          assertEquals(
              View.GONE, activity.findViewById(R.id.video_subtitle_drawer).getVisibility());
          assertTrue(((PlayerView) activity.findViewById(R.id.video_player)).getUseController());
          activity.findViewById(R.id.video_exit_fullscreen).performClick();
        });
    await(() -> !vm.fullScreen);
    scenario.onActivity(
        activity -> {
          TextView caption = activity.findViewById(R.id.video_caption);
          assertEquals(
              18f,
              caption.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity,
              .1f);
        });
  }

  private android.widget.EditText nameInput(View view) {
    if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        android.widget.EditText input = nameInput(group.getChildAt(i));
        if (input != null) return input;
      }
    }
    return null;
  }

  private androidx.fragment.app.DialogFragment nameDialog(MainActivity activity) {
    androidx.fragment.app.Fragment page =
        activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    return (androidx.fragment.app.DialogFragment)
        page.getChildFragmentManager().findFragmentByTag("video-name");
  }

  @Test
  public void optionalVideoNameSurvivesRotationAndSkipUsesFilename() throws Exception {
    scenario.onActivity(
        activity -> {
          androidx.fragment.app.Fragment page =
              activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
          com.example.bilingualreader.ui.video.VideoNameDialog.show(
              page, Uri.fromFile(files.get(0)).toString(), "Original video.mp4");
        });
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity ->
                  ready.set(
                      nameDialog(activity) != null && nameDialog(activity).getDialog() != null));
          return ready.get();
        });
    scenario.onActivity(
        activity ->
            nameInput(nameDialog(activity).getDialog().getWindow().getDecorView())
                .setText("我的听力练习"));
    scenario.recreate();
    scenario.onActivity(
        activity -> {
          androidx.appcompat.app.AlertDialog dialog =
              (androidx.appcompat.app.AlertDialog) nameDialog(activity).getDialog();
          assertEquals("我的听力练习", nameInput(dialog.getWindow().getDecorView()).getText().toString());
          dialog.getButton(-1).performClick();
        });
    await(() -> "我的听力练习".equals(vm.project().videoName));
    scenario.onActivity(
        activity -> {
          assertEquals(
              "我的听力练习", ((TextView) activity.findViewById(R.id.video_title)).getText().toString());
          com.example.bilingualreader.ui.video.VideoNameDialog.show(
              activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container),
              Uri.fromFile(files.get(0)).toString(),
              "Original video.mp4");
        });
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity ->
                  ready.set(
                      nameDialog(activity) != null && nameDialog(activity).getDialog() != null));
          return ready.get();
        });
    scenario.onActivity(
        activity ->
            ((androidx.appcompat.app.AlertDialog) nameDialog(activity).getDialog())
                .getButton(-2)
                .performClick());
    await(() -> "Original video.mp4".equals(vm.project().videoName));
  }

  @Test
  public void studyPageKeepsPlayerFixedAndToolsCloseBeforeLeaving() throws Exception {
    scenario.onActivity(
        activity -> {
          assertEquals(View.GONE, activity.findViewById(R.id.video_preparation).getVisibility());
          assertEquals(
              View.VISIBLE, activity.findViewById(R.id.video_learning_controls).getVisibility());
          assertEquals(View.GONE, activity.findViewById(R.id.video_tools_panel).getVisibility());
          View stage = activity.findViewById(R.id.video_stage);
          int[] before = new int[2];
          stage.getLocationOnScreen(before);
          ((RecyclerView) activity.findViewById(R.id.video_cues)).scrollBy(0, 200);
          int[] after = new int[2];
          stage.getLocationOnScreen(after);
          assertArrayEquals(before, after);
          activity.findViewById(R.id.video_tools).performClick();
          assertEquals(View.VISIBLE, activity.findViewById(R.id.video_tools_panel).getVisibility());
          activity.getOnBackPressedDispatcher().onBackPressed();
          assertTrue(
              activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container)
                  instanceof VideoFragment);
          assertEquals(View.GONE, activity.findViewById(R.id.video_tools_panel).getVisibility());
        });
    translate();
    await(() -> modelReady(true));
    screenshot("video-study-ui.png");
    scenario.onActivity(activity -> activity.findViewById(R.id.video_tools).performClick());
    screenshot("video-tools-ui.png");
    scenario.onActivity(activity -> activity.findViewById(R.id.video_tools_close).performClick());
  }

  private void screenshot(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    android.graphics.Bitmap shot =
        InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull(shot);
    try (OutputStream output = new FileOutputStream(new File(context.getCacheDir(), name))) {
      assertTrue(shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
    } finally {
      shot.recycle();
    }
  }

  @Test
  public void preparationOffersSubtitleImportAndRecognition() throws Exception {
    File fresh = File.createTempFile("video-empty-", ".mp4", context.getCacheDir());
    files.add(fresh);
    java.nio.file.Files.copy(
        files.get(0).toPath(), fresh.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    scenario.onActivity(
        activity -> {
          vm.video(Uri.fromFile(fresh), "New video");
          assertEquals(View.VISIBLE, activity.findViewById(R.id.video_preparation).getVisibility());
          assertEquals(
              View.GONE, activity.findViewById(R.id.video_learning_controls).getVisibility());
          assertEquals(View.GONE, activity.findViewById(R.id.video_tools_panel).getVisibility());
          assertTrue(activity.findViewById(R.id.video_prepare_subtitles).isEnabled());
          assertTrue(activity.findViewById(R.id.video_prepare_asr).isEnabled());
          assertTrue(activity.findViewById(R.id.video_prepare_ocr).isEnabled());
        });
    screenshot("video-preparation-ui.png");
  }

  @Test
  public void hiddenTranslationAppliesToOverlayAndRowsAndSurvivesRotation() throws Exception {
    translate();
    await(() -> modelReady(true));
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
                ready.set(player != null && player.getPlaybackState() == Player.STATE_READY);
              });
          return ready.get();
        });
    scenario.onActivity(
        activity -> {
          RecyclerView list = activity.findViewById(R.id.video_cues);
          list.findViewHolderForAdapterPosition(0).itemView.performClick();
          activity.findViewById(R.id.video_hide_translation).performClick();
          assertFalse(
              ((TextView) activity.findViewById(R.id.video_caption))
                  .getText()
                  .toString()
                  .contains("译文:"));
          ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer().pause();
        });
    scenario.recreate();
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                RecyclerView list = activity.findViewById(R.id.video_cues);
                RecyclerView.ViewHolder row = list.findViewHolderForAdapterPosition(0);
                ready.set(
                    row != null
                        && row.itemView.findViewById(R.id.cue_translation).getVisibility()
                            == View.GONE);
                assertEquals(
                    "显示译文",
                    ((TextView) activity.findViewById(R.id.video_hide_translation))
                        .getText()
                        .toString());
              });
          return ready.get();
        });
    scenario.onActivity(
        activity -> activity.findViewById(R.id.video_hide_translation).performClick());
    await(
        () -> {
          AtomicBoolean visible = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                RecyclerView list = activity.findViewById(R.id.video_cues);
                RecyclerView.ViewHolder row = list.findViewHolderForAdapterPosition(0);
                visible.set(
                    row != null
                        && row.itemView.findViewById(R.id.cue_translation).getVisibility()
                            == View.VISIBLE);
              });
          return visible.get();
        });
  }

  @Test
  public void sentenceLoopRepeatsAndCanReturnToSinglePlayback() throws Exception {
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
                ready.set(player != null && player.getPlaybackState() == Player.STATE_READY);
              });
          return ready.get();
        });
    scenario.onActivity(
        activity -> {
          ((android.widget.CompoundButton) activity.findViewById(R.id.video_loop)).setChecked(true);
          ((RecyclerView) activity.findViewById(R.id.video_cues))
              .findViewHolderForAdapterPosition(0)
              .itemView
              .performClick();
        });
    Thread.sleep(1800);
    scenario.onActivity(
        activity -> {
          Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
          assertTrue(player.getPlayWhenReady());
          assertTrue(player.getCurrentPosition() < 1350);
          ((android.widget.CompoundButton) activity.findViewById(R.id.video_loop))
              .setChecked(false);
        });
    await(
        () -> {
          AtomicBoolean paused = new AtomicBoolean();
          scenario.onActivity(
              activity ->
                  paused.set(
                      !((PlayerView) activity.findViewById(R.id.video_player))
                          .getPlayer()
                          .getPlayWhenReady()));
          return paused.get();
        });
  }

  @Test
  public void localPlaybackBilingualOverlaySingleCuePauseAndExport() throws Exception {
    translate();
    await(() -> modelReady(true));
    assertEquals(2, calls.get());
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
                ready.set(player != null && player.getPlaybackState() == Player.STATE_READY);
              });
          return ready.get();
        });
    scenario.onActivity(
        activity -> {
          RecyclerView rows = activity.findViewById(R.id.video_cues);
          assertEquals(2, rows.getAdapter().getItemCount());
          assertNotNull(rows.findViewHolderForAdapterPosition(0));
          rows.findViewHolderForAdapterPosition(0).itemView.performClick();
        });
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                TextView text = activity.findViewById(R.id.video_caption);
                ready.set(
                    text.getVisibility() == View.VISIBLE
                        && text.getText().toString().contains("Hello, world!")
                        && text.getText().toString().contains("译文:"));
              });
          return ready.get();
        });
    android.graphics.Bitmap preview =
        InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull(preview);
    try (OutputStream out =
        new FileOutputStream(new File(context.getCacheDir(), "video-stage1-preview.png"))) {
      assertTrue(preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      preview.recycle();
    }
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
                ready.set(
                    player != null
                        && !player.getPlayWhenReady()
                        && player.getCurrentPosition() >= 1200
                        && player.getCurrentPosition() < 1550);
              });
          return ready.get();
        });
    File exported = File.createTempFile("video-export-", ".vtt", context.getCacheDir());
    files.add(exported);
    main(() -> vm.export(Uri.fromFile(exported), true, true));
    await(() -> exported.length() > 20);
    String result =
        new String(java.nio.file.Files.readAllBytes(exported.toPath()), StandardCharsets.UTF_8);
    assertTrue(result.startsWith("WEBVTT"));
    assertTrue(result.contains("模拟译文，非真实翻译"));
    assertTrue(result.contains("00:00:00.200 --> 00:00:01.200"));
    assertTrue(result.contains("Hello, world!"));
    assertEquals(2, new SubtitleCodec().acquire(result).size());
  }

  @Test
  public void switchingFromMockToOnlineDoesNotReuseMockAndCheckpointRestores() throws Exception {
    translate();
    await(() -> modelReady(true));
    assertEquals(2, calls.get());
    main(
        () ->
            graph.setSelectionTranslation(
                request -> {
                  calls.incrementAndGet();
                  return success(request, "real:");
                },
                false));
    translate();
    await(() -> modelReady(true));
    assertEquals(4, calls.get());
    main(
        () -> {
          try {
            vm.translate(vm.prepare(true));
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
    await(() -> modelReady(true));
    assertEquals(6, calls.get());
    main(
        () -> {
          assertFalse(vm.project().demo);
          for (SubtitleSegment cue : vm.project().cues)
            assertTrue(cue.translatedText.startsWith("real:"));
          vm.checkpoint(900);
        });
    VideoProjectStore store = new VideoProjectStore(context);
    await(
        () -> {
          VideoProject restored = store.load();
          return restored.positionMs == 900 && restored.translatedCount() == 2 && !restored.demo;
        });
    VideoProject restored = store.load();
    assertEquals(200, restored.cues.get(0).startMs);
    assertEquals("Hello, world!", restored.cues.get(0).sourceText);
  }

  @Test
  public void rotationKeepsRunningTaskAndRetainedModel() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    releases.add(release);
    main(
        () ->
            graph.setSelectionTranslation(
                request -> {
                  calls.incrementAndGet();
                  entered.countDown();
                  try {
                    release.await(10, TimeUnit.SECONDS);
                  } catch (InterruptedException error) {
                    throw new AppException(ErrorCode.TRANSLATION_FAILED, "cancelled", false);
                  }
                  return success(request, "译文:");
                },
                true));
    translate();
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    VideoViewModel before = vm;
    scenario.recreate();
    scenario.onActivity(
        activity -> {
          VideoFragment page =
              (VideoFragment)
                  activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
          VideoViewModel retained = new ViewModelProvider(page).get(VideoViewModel.class);
          assertSame(before, retained);
          assertTrue(retained.translating);
        });
    assertEquals(1, calls.get());
    release.countDown();
    await(() -> modelReady(true));
    assertEquals(2, calls.get());
  }

  @Test
  public void cancelledLateResponseCannotReplaceResumeCheckpoint() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    releases.add(release);
    main(
        () ->
            graph.setSelectionTranslation(
                request -> {
                  int call = calls.incrementAndGet();
                  if (call == 2) {
                    entered.countDown();
                    boolean ready = false;
                    while (!ready)
                      try {
                        ready = release.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException ignored) {
                      }
                  }
                  return success(request, "call:" + call + ":");
                },
                true));
    translate();
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          main(() -> ready.set(vm.project().translatedCount() == 1));
          return ready.get();
        });
    main(() -> vm.cancel());
    release.countDown();
    translate();
    await(() -> modelReady(true));
    assertEquals(3, calls.get());
    main(
        () -> {
          assertTrue(vm.project().cues.get(0).translatedText.startsWith("call:1:"));
          assertTrue(vm.project().cues.get(1).translatedText.startsWith("call:3:"));
        });
  }

  @Test
  public void recognizesEnglishFromActualVideoWithoutSubtitleFile() throws Exception {
    main(
        () -> {
          try {
            vm.recognize(.6f, 1f, vm.prepareEnglishOcr());
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
    await(() -> modelReady(false));
    main(
        () -> {
          assertEquals("画面英文 OCR", vm.project().subtitleName);
          assertEquals(vm.project().cues.size(), vm.project().translatedCount());
          assertTrue(calls.get() > 0);
          assertFalse(vm.project().cues.isEmpty());
          assertEquals(SubtitleSegment.TextSource.OCR, vm.project().cues.get(0).textSource);
          assertTrue(
              vm.project()
                  .cues
                  .get(0)
                  .sourceText
                  .toLowerCase(java.util.Locale.ROOT)
                  .contains("hello"));
        });
  }

  @Test
  public void fullscreenRotatesAndBackRestoresPositionSpeedAndCaptions() throws Exception {
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
                ready.set(player != null && player.getPlaybackState() == Player.STATE_READY);
              });
          return ready.get();
        });
    scenario.onActivity(
        activity -> {
          Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
          vm.playbackSpeed = 2f;
          player.setPlaybackSpeed(2f);
          player.pause();
          player.seekTo(700);
          activity.findViewById(R.id.video_fullscreen).performClick();
        });
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                View stage = activity.findViewById(R.id.video_stage),
                    content = activity.findViewById(android.R.id.content);
                ready.set(
                    vm.fullScreen
                        && activity.getResources().getConfiguration().orientation
                            == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        && stage != null
                        && stage.getWidth() >= content.getWidth() - 4
                        && stage.getHeight() >= content.getHeight() - 4);
              });
          return ready.get();
        });
    scenario.onActivity(
        activity -> {
          Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
          assertEquals(2f, player.getPlaybackParameters().speed, 0.01f);
          assertFalse(player.getPlayWhenReady());
          assertTrue(Math.abs(player.getCurrentPosition() - 700) < 150);
          assertTrue(
              ((TextView) activity.findViewById(R.id.video_speed))
                  .getText()
                  .toString()
                  .contains("2.0"));
          activity.getOnBackPressedDispatcher().onBackPressed();
        });
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean();
          scenario.onActivity(
              activity -> {
                ready.set(
                    !vm.fullScreen
                        && activity.getResources().getConfiguration().orientation
                            == android.content.res.Configuration.ORIENTATION_PORTRAIT);
              });
          return ready.get();
        });
    scenario.onActivity(
        activity -> {
          assertEquals(2, vm.project().cues.size());
          Player player = ((PlayerView) activity.findViewById(R.id.video_player)).getPlayer();
          assertEquals(2f, player.getPlaybackParameters().speed, 0.01f);
          assertTrue(Math.abs(player.getCurrentPosition() - 700) < 150);
          assertEquals(
              "全屏", ((TextView) activity.findViewById(R.id.video_fullscreen)).getText().toString());
        });
  }

  @Test
  public void missingAudioIsRejectedBeforeUpload() throws Exception {
    AtomicReference<Uri> video = new AtomicReference<>();
    main(() -> video.set(Uri.parse(vm.project().videoUri)));
    try {
      VideoAudio.extract(context, video.get());
      fail("Expected missing audio error");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("没有音轨"));
    }
  }

  private static void encode(File output) throws Exception {
    MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
    MediaMuxer muxer =
        new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    boolean muxing = false;
    int track = -1, frame = 0;
    boolean finished = false;
    try {
      MediaFormat format = MediaFormat.createVideoFormat("video/avc", 160, 160);
      format.setInteger(
          MediaFormat.KEY_COLOR_FORMAT,
          MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
      format.setInteger(MediaFormat.KEY_BIT_RATE, 160000);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      codec.start();
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      long deadline = System.currentTimeMillis() + 30000;
      while (!finished && System.currentTimeMillis() < deadline) {
        if (frame <= 40) {
          int index = codec.dequeueInputBuffer(10000);
          if (index >= 0) {
            ByteBuffer buffer = codec.getInputBuffer(index);
            buffer.clear();
            if (frame == 40)
              codec.queueInputBuffer(
                  index, 0, 0, frame * 100000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            else {
              byte[] pixels = new byte[160 * 160 * 3 / 2];
              android.graphics.Bitmap image =
                  android.graphics.Bitmap.createBitmap(
                      160, 160, android.graphics.Bitmap.Config.ARGB_8888);
              android.graphics.Canvas canvas = new android.graphics.Canvas(image);
              canvas.drawColor(android.graphics.Color.WHITE);
              android.graphics.Paint paint =
                  new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
              paint.setColor(android.graphics.Color.BLACK);
              paint.setTextSize(20);
              canvas.drawText("Hello world", 12, 140, paint);
              int[] colors = new int[160 * 160];
              image.getPixels(colors, 0, 160, 0, 0, 160, 160);
              image.recycle();
              for (int pixel = 0; pixel < colors.length; pixel++)
                pixels[pixel] = (byte) (16 + android.graphics.Color.red(colors[pixel]) * 219 / 255);
              Arrays.fill(pixels, 160 * 160, pixels.length, (byte) 128);
              buffer.put(pixels);
              codec.queueInputBuffer(index, 0, pixels.length, frame * 100000L, 0);
            }
            frame++;
          }
        }
        int index = codec.dequeueOutputBuffer(info, 10000);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          track = muxer.addTrack(codec.getOutputFormat());
          muxer.start();
          muxing = true;
        } else if (index >= 0) {
          ByteBuffer buffer = codec.getOutputBuffer(index);
          if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
            assertTrue(muxing);
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            muxer.writeSampleData(track, buffer, info);
          }
          finished = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          codec.releaseOutputBuffer(index, false);
        }
      }
      assertTrue("Video encoder did not finish", finished);
    } finally {
      codec.stop();
      codec.release();
      if (muxing) muxer.stop();
      muxer.release();
    }
  }
}
