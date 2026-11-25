package com.example.bilingualreader.ui.video;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.*;
import androidx.lifecycle.*;
import androidx.media3.common.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ui.BaseFragment;
import com.example.bilingualreader.ui.settings.TranslationSettingsDialog;
import com.example.bilingualreader.video.*;
import com.example.bilingualreader.viewmodel.VideoViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class VideoFragment extends BaseFragment {
  private VideoViewModel vm;
  private ExoPlayer player;
  private PlayerView playerView;
  private TextView caption;
  private CueAdapter adapter, drawerAdapter;
  private RecyclerView drawerList;
  private boolean drawerVisible, drawerStyle;
  private int captionSize = 16, captionPosition = 2;
  private String loadedUri;
  private long replayEnd = -1;
  private SubtitleSegment replayCue;
  private RecyclerView cueList;
  private boolean hideTranslation, loopSentence;
  private FrameLayout stage, fullscreenOverlay;
  private ViewGroup stageParent;
  private ViewGroup.LayoutParams stageParams;
  private int stageIndex;
  private androidx.activity.OnBackPressedCallback fullscreenBack;

  private final Handler main = new Handler(Looper.getMainLooper());
  private androidx.appcompat.app.AlertDialog dialog;
  private boolean exportOriginal;
  private int importDirection;
  private boolean exportImported;
  private final float[] speeds = {.75f, 1f, 1.25f, 1.5f, 2f};
  private final ActivityResultLauncher<String[]> videoPicker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri == null || vm == null) return;
            try {
              requireContext()
                  .getContentResolver()
                  .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
              VideoNameDialog.show(this, uri.toString(), name(uri));
            } catch (SecurityException error) {
              notice("无法保留视频访问授权，请把文件保存到本机后重新选择");
            }
          });
  private final ActivityResultLauncher<String[]> subtitlePicker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri == null || vm == null) return;
            vm.importAndProcessSubtitles(uri, name(uri), importDirection, exportImported);
          });
  private final ActivityResultLauncher<String> exporter =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("text/plain"),
          uri -> {
            if (uri != null && vm != null) vm.export(uri, false, true, exportOriginal);
          });

  public VideoFragment() {
    super(R.layout.fragment_video);
  }
  @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
    super.onViewCreated(view,state);
    String requested = getArguments() == null ? null : getArguments().getString("videoUri");
    vm = new ViewModelProvider(this, new ViewModelProvider.Factory() {
      @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
        return type.cast(new VideoViewModel(requireContext(), graph(), requested));
      }
    }).get(VideoViewModel.class);
    exportOriginal = state != null && state.getBoolean("exportOriginal");
    if (state != null) {
      importDirection = state.getInt("importDirection");
      exportImported = state.getBoolean("exportImported");
    }
    hideTranslation = state != null && state.getBoolean("hideTranslation");
    loopSentence = state != null && state.getBoolean("loopSentence");
    drawerVisible = state != null && state.getBoolean("drawerVisible");
    drawerStyle = state != null && state.getBoolean("drawerStyle");
    getChildFragmentManager().setFragmentResultListener(VideoNameDialog.RESULT, getViewLifecycleOwner(),
        (key, result) -> {
          String custom = result.getString("customName", "");
          vm.video(Uri.parse(result.getString("uri")),
              custom.isEmpty() ? result.getString("filename") : custom);
        });
    playerView = view.findViewById(R.id.video_player);
    stage = view.findViewById(R.id.video_stage);
    stageParent = (ViewGroup) stage.getParent();
    stageParams = stage.getLayoutParams();
    stageIndex = stageParent.indexOfChild(stage);
    caption = view.findViewById(R.id.video_caption);
    cueList = view.findViewById(R.id.video_cues);
    cueList.setLayoutManager(new LinearLayoutManager(requireContext()));
    adapter = new CueAdapter();
    cueList.setAdapter(adapter);
    drawerList = stage.findViewById(R.id.video_drawer_cues);
    drawerList.setLayoutManager(new LinearLayoutManager(requireContext()));
    drawerAdapter = new CueAdapter(true);
    drawerList.setAdapter(drawerAdapter);
    fullscreenBack = new androidx.activity.OnBackPressedCallback(vm.fullScreen) {
      @Override public void handleOnBackPressed() { setFullscreen(false, true); }
    };
    requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), fullscreenBack);
    stage.findViewById(R.id.video_fullscreen).setOnClickListener(v -> setFullscreen(!vm.fullScreen, true));
    stage.findViewById(R.id.video_exit_fullscreen).setOnClickListener(v -> setFullscreen(false, true));
    stage.findViewById(R.id.video_player_subtitles).setOnClickListener(v -> showSubtitleDrawer(!drawerVisible));
    stage.findViewById(R.id.video_drawer_close).setOnClickListener(v -> showSubtitleDrawer(false));
    stage.findViewById(R.id.video_subtitle_scrim).setOnClickListener(v -> showSubtitleDrawer(false));
    stage.findViewById(R.id.video_drawer_list_tab).setOnClickListener(v -> setDrawerStyle(false));
    stage.findViewById(R.id.video_drawer_style_tab).setOnClickListener(v -> setDrawerStyle(true));
    stage.findViewById(R.id.video_caption_smaller).setOnClickListener(v -> {
      captionSize = Math.max(12, captionSize - 2); applyCaptionStyle();
    });
    stage.findViewById(R.id.video_caption_larger).setOnClickListener(v -> {
      captionSize = Math.min(32, captionSize + 2); applyCaptionStyle();
    });
    int[] positions = {R.id.video_caption_top, R.id.video_caption_middle, R.id.video_caption_bottom};
    for (int i = 0; i < positions.length; i++) {
      int position = i;
      stage.findViewById(positions[i]).setOnClickListener(v -> {
        captionPosition = position; applyCaptionStyle();
      });
    }
    stage.findViewById(R.id.video_drawer_hide).setOnClickListener(v -> {
      hideTranslation = !hideTranslation;
      ((TextView) v).setText(hideTranslation ? "显示译文" : "隐藏译文");
      adapter.notifyDataSetChanged();
      drawerAdapter.notifyDataSetChanged();
      tickCaptions();
    });
    stage.findViewById(R.id.video_drawer_loop).setOnClickListener(v -> {
      loopSentence = !loopSentence;
      ((TextView) v).setText(loopSentence ? "单句循环：开" : "单句循环：关");
    });
    TextView speed = stage.findViewById(R.id.video_speed);
    speed.setText(vm.playbackSpeed + "×");
    speed.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
        .setTitle("播放速度").setItems(new String[]{"0.75×", "1.0×", "1.25×", "1.5×", "2.0×"},
            (d, index) -> {
              vm.playbackSpeed = speeds[index];
              speed.setText(vm.playbackSpeed + "×");
              if (player != null) player.setPlaybackSpeed(vm.playbackSpeed);
            }).show());
    view.findViewById(R.id.video_translate).setOnClickListener(v -> {
      if (vm.project().videoUri == null) videoPicker.launch(new String[]{"video/*"});
      else showTranslationDialog();
    });
    view.findViewById(R.id.video_cancel).setOnClickListener(v -> vm.cancel());
    vm.changes.observe(getViewLifecycleOwner(), ignored -> render());
  }

  private void render() {
    VideoProject project = vm.project();
    TextView action = findVideoView(R.id.video_translate);
    action.setEnabled(!vm.busy);
    action.setText(project.videoUri == null ? "选择视频"
        : project.cues.isEmpty() ? "翻译视频" : "重新翻译");
    ((TextView) findVideoView(R.id.video_fullscreen_title)).setText(project.videoName);
    ((TextView) findVideoView(R.id.video_status)).setText(vm.message);
    findVideoView(R.id.video_cancel).setVisibility(vm.busy ? View.VISIBLE : View.GONE);
    ((TextView) findVideoView(R.id.video_cancel))
        .setText(vm.recognizingSpeech ? "暂停等待" : "取消处理");
    findVideoView(R.id.video_demo).setVisibility(project.demo ? View.VISIBLE : View.GONE);
    ProgressBar progress = findVideoView(R.id.video_progress);
    progress.setVisibility(vm.busy ? View.VISIBLE : View.GONE);
    progress.setIndeterminate(!vm.translating);
    if (!project.cues.isEmpty()) progress.setProgress(project.translatedCount() * 1000 / project.cues.size());
    if (adapter.items != project.cues) {
      adapter.items = project.cues;
      drawerAdapter.items = project.cues;
      adapter.active = drawerAdapter.active = -1;
      adapter.notifyDataSetChanged();
      drawerAdapter.notifyDataSetChanged();
    }
    ((TextView) stage.findViewById(R.id.video_drawer_count)).setText("字幕 · " + project.cues.size());
    setDrawerStyle(drawerStyle);
    ensureMedia();
    tickCaptions();
    if (isResumed()) openPendingExport();
  }

  private <T extends View> T findVideoView(int id) {
    T view = requireView().findViewById(id);
    return view != null ? view : stage.findViewById(id);
  }

  private void showTranslationDialog() {
    closeDialog();
    LinearLayout fields = new LinearLayout(requireContext());
    fields.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(24 * getResources().getDisplayMetrics().density);
    fields.setPadding(padding, 0, padding, 0);
    addChoiceLabel(fields, "翻译语言");
    LinearLayout languages = new LinearLayout(requireContext());
    languages.setGravity(Gravity.CENTER_VERTICAL);
    fields.addView(languages);
    int[] directions = {1, 0, 2, 3};
    Spinner direction = createChoice("原文语言", new String[]{"中文", "英文", "韩文", "日文"});
    languages.addView(direction, new LinearLayout.LayoutParams(0, -2, 1));
    TextView target = new TextView(requireContext());
    target.setText(" → 简体中文");
    target.setTextSize(16);
    languages.addView(target);
    for (int i = 0; i < directions.length; i++)
      if (directions[i] == vm.project().direction) direction.setSelection(i);

    boolean hasCues = !vm.project().cues.isEmpty();
    String[] sources = hasCues
        ? new String[]{"使用当前字幕", "提取内嵌软字幕", "识别画面硬字幕", "识别视频语音", "导入 SRT / VTT"}
        : new String[]{"提取内嵌软字幕", "识别画面硬字幕", "识别视频语音", "导入 SRT / VTT"};
    addChoiceLabel(fields, "字幕来源");
    Spinner source = createChoice("字幕来源", sources);
    fields.addView(source);
    TextView note = new TextView(requireContext());
    fields.addView(note);
    Switch export = new Switch(requireContext());
    export.setText("导出字幕（SRT）");
    export.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
    fields.addView(export);
    Runnable updateNote = () -> {
      boolean chinese = directions[direction.getSelectedItemPosition()] == 1;
      int mode = source.getSelectedItemPosition() + (hasCues ? 0 : 1);
      String message = chinese ? "生成中文字幕。" : "在线翻译会发送字幕文本。";
      if (mode == 3) message += "语音识别会上传音频，可能产生费用。";
      if (mode == 4 && hasCues) message += "成功导入后会替换当前字幕和译文。";
      note.setText(message);
    };
    AdapterView.OnItemSelectedListener update = new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
        updateNote.run();
      }
      @Override public void onNothingSelected(AdapterView<?> parent) {}
    };
    direction.setOnItemSelectedListener(update);
    source.setOnItemSelectedListener(update);
    updateNote.run();
    ScrollView scroll = new ScrollView(requireContext());
    scroll.addView(fields);
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
        .setTitle(hasCues ? "重新翻译" : "翻译视频")
        .setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton("确认", null);
    if (graph().translationConfig != null) builder.setNeutralButton("翻译服务",
        (d, w) -> TranslationSettingsDialog.show(requireContext(), graph().translationConfig));
    dialog = builder.create();
    dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
      int mode = source.getSelectedItemPosition() + (hasCues ? 0 : 1);
      int selectedDirection = directions[direction.getSelectedItemPosition()];
      if (mode == 4) {
        importDirection = selectedDirection;
        exportImported = export.isChecked();
        if (player != null) player.pause();
        closeDialog();
        subtitlePicker.launch(new String[]{"*/*"});
        return;
      }
      try {
        vm.processVideo(selectedDirection, mode, export.isChecked());
        if (player != null) player.pause();
        closeDialog();
      } catch (Exception error) {
        notice(error instanceof com.example.bilingualreader.contract.model.AppException
            ? ((com.example.bilingualreader.contract.model.AppException) error).getUserMessage()
            : error.getMessage());
      }
    }));
    dialog.show();
  }

  private void addChoiceLabel(LinearLayout fields, String label) {
    TextView title = new TextView(requireContext());
    title.setText(label);
    fields.addView(title);
  }

  private Spinner createChoice(String label, String[] choices) {
    Spinner spinner = new Spinner(requireContext());
    spinner.setContentDescription(label);
    spinner.setMinimumHeight(Math.round(48 * getResources().getDisplayMetrics().density));
    spinner.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, choices));
    return spinner;
  }

  private void openPendingExport() {
    Boolean original = vm.consumeExport();
    if (original == null) return;
    exportOriginal = original;
    exporter.launch(original ? "中文字幕.srt" : "双语字幕.srt");
  }

  private void showSubtitleDrawer(boolean visible) {
    drawerVisible = visible;
    stage.findViewById(R.id.video_subtitle_drawer).setVisibility(visible ? View.VISIBLE : View.GONE);
    stage.findViewById(R.id.video_subtitle_scrim).setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private void setDrawerStyle(boolean style) {
    drawerStyle = style;
    boolean empty = vm.project().cues.isEmpty();
    drawerList.setVisibility(!style && !empty ? View.VISIBLE : View.GONE);
    stage.findViewById(R.id.video_drawer_empty).setVisibility(!style && empty ? View.VISIBLE : View.GONE);
    stage.findViewById(R.id.video_drawer_style).setVisibility(style ? View.VISIBLE : View.GONE);
  }

  private void applyCaptionStyle() {
    caption.setTextSize(captionSize);
    ((TextView) stage.findViewById(R.id.video_caption_size)).setText(String.valueOf(captionSize));
    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) caption.getLayoutParams();
    params.gravity = new int[]{Gravity.TOP, Gravity.CENTER_VERTICAL, Gravity.BOTTOM}[captionPosition];
    caption.setLayoutParams(params);
  }

  private void followActiveCue() {
    if (adapter.active >= 0) cueList.scrollToPosition(adapter.active);
    if (drawerVisible && drawerAdapter.active >= 0) drawerList.scrollToPosition(drawerAdapter.active);
  }

  @Override public void onResume() {
    super.onResume();
    openPendingExport();
  }

  private void edit(SubtitleSegment cue) {
    if (vm.busy) {
      notice("请先取消处理，再修正字幕原文");
      return;
    }
    EditText input = new EditText(requireContext());
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    input.setText(cue.sourceText);
    input.setMinLines(3);
    closeDialog();
    dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("修正字幕原文")
            .setMessage("修改后只清除这条字幕的旧译文，时间轴保留。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (d, w) -> vm.edit(cue.id, input.getText().toString()))
            .show();
  }

  private void notice(String text) {
    if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show();
  }

  private String name(Uri uri) {
    try (Cursor cursor =
        requireContext()
            .getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
    } catch (RuntimeException ignored) {
    }
    return "本地文件";
  }

  private void ensureMedia() {
    if (player == null || vm.project().videoUri == null || vm.project().videoUri.equals(loadedUri))
      return;
    loadedUri = vm.project().videoUri;
    replayEnd = -1;
    replayCue = null;
    findVideoView(R.id.video_playback_error).setVisibility(View.GONE);
    player.setMediaItem(MediaItem.fromUri(Uri.parse(loadedUri)));
    player.prepare();
    player.seekTo(vm.project().positionMs);
  }

  private final Runnable ticker =
      new Runnable() {
        public void run() {
          tickCaptions();
          main.postDelayed(this, 100);
        }
      };

  private void tickCaptions() {
    if (player == null || caption == null) return;
    long position = player.getCurrentPosition();
    if (replayEnd >= 0 && position >= replayEnd) {
      if (loopSentence && replayCue != null && player.getPlayWhenReady()) {
        position = replayCue.startMs;
        player.seekTo(position);
        player.play();
      } else {
        player.pause();
        replayEnd = -1;
        replayCue = null;
      }
    }
    StringBuilder text = new StringBuilder();
    int active = -1;
    for (int i = 0; i < vm.project().cues.size(); i++) {
      SubtitleSegment cue = vm.project().cues.get(i);
      if (!(cue.startMs <= position && position < cue.endMs)) continue;
      if (active < 0) active = i;
      if (text.length() > 0) text.append('\n');
      text.append(cue.sourceText);
      if (!hideTranslation && cue.translatedText != null)
        text.append('\n').append(vm.project().demo ? "【模拟】" : "").append(cue.translatedText);
    }
    if (adapter != null && adapter.active != active) {
      int old = adapter.active;
      adapter.active = active;
      if (old >= 0 && old < adapter.getItemCount()) adapter.notifyItemChanged(old);
      if (active >= 0) adapter.notifyItemChanged(active);
      if (player.isPlaying()) followActiveCue();
    }
    if (drawerAdapter != null && drawerAdapter.active != active) {
      int old = drawerAdapter.active;
      drawerAdapter.active = active;
      if (old >= 0 && old < drawerAdapter.getItemCount()) drawerAdapter.notifyItemChanged(old);
      if (active >= 0) drawerAdapter.notifyItemChanged(active);
      if (drawerVisible && player.isPlaying()) followActiveCue();
    }
    if (loopSentence && replayCue == null && active >= 0 && player.isPlaying()) {
      replayCue = vm.project().cues.get(active);
      replayEnd = replayCue.endMs;
    }
    String next = text.toString();
    if (!caption.getText().toString().equals(next)) caption.setText(next);
    caption.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
  }

  @Override
  public void onStart() {
    super.onStart();
    host().page("视频学习", true);
    player = new ExoPlayer.Builder(requireContext()).build();
    playerView.setPlayer(player);
    playerView.setControllerShowTimeoutMs(1500);
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build());
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(
              Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK
                && replayCue != null
                && (newPosition.positionMs < replayCue.startMs
                    || newPosition.positionMs >= replayCue.endMs)) {
              replayCue = null;
              replayEnd = -1;
            }
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (state != Player.STATE_READY || getView() == null || player == null) return;
            long duration = player.getDuration();
            if (duration > 0
                && vm.project().cues.stream().anyMatch(cue -> cue.endMs > duration + 1000)) {
              TextView status = findVideoView(R.id.video_playback_error);
              status.setText("部分字幕超出视频时长，请检查字幕是否与视频匹配。");
              status.setVisibility(View.VISIBLE);
            }
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            if (getView() != null) {
              TextView status = findVideoView(R.id.video_playback_error);
              status.setText("视频无法播放：请检查文件授权或改用设备支持的 MP4/H.264 视频，重新选择视频可重试。");
              status.setVisibility(View.VISIBLE);
            }
          }
        });
    player.setPlaybackSpeed(vm.playbackSpeed);
    loadedUri = null;
    ensureMedia();
    player.setPlayWhenReady(vm.resumePlayback);
    setFullscreen(vm.fullScreen, false);
    main.post(ticker);
  }

  @Override
  public void onStop() {
    closeDialog();
    main.removeCallbacks(ticker);
    if (player != null) {
      vm.resumePlayback = player.getPlayWhenReady();
      vm.checkpoint(player.getCurrentPosition());
      playerView.setPlayer(null);
      player.release();
      player = null;
    }
    if (!requireActivity().isChangingConfigurations()) vm.cancel();
    super.onStop();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle state) {
    super.onSaveInstanceState(state);
    state.putBoolean("exportOriginal", exportOriginal);
    state.putInt("importDirection", importDirection);
    state.putBoolean("exportImported", exportImported);
    state.putBoolean("hideTranslation", hideTranslation);
    state.putBoolean("loopSentence", loopSentence);
    state.putBoolean("drawerVisible", drawerVisible);
    state.putBoolean("drawerStyle", drawerStyle);
  }

  private void closeDialog() {
    if (dialog != null) {
      dialog.dismiss();
      dialog = null;
    }
  }

  private void setFullscreen(boolean enabled, boolean rotate) {
    boolean reopen = drawerVisible;
    showSubtitleDrawer(false);
    if (enabled && fullscreenOverlay == null) {
      stageParent.removeView(stage);
      fullscreenOverlay = new FrameLayout(requireContext());
      fullscreenOverlay.setBackgroundColor(android.graphics.Color.BLACK);
      ((ViewGroup) requireActivity().findViewById(android.R.id.content))
          .addView(fullscreenOverlay, new ViewGroup.LayoutParams(-1, -1));
      fullscreenOverlay.addView(stage, new FrameLayout.LayoutParams(-1, -1));
    } else if (!enabled && fullscreenOverlay != null) {
      fullscreenOverlay.removeView(stage);
      ((ViewGroup) fullscreenOverlay.getParent()).removeView(fullscreenOverlay);
      fullscreenOverlay = null;
      stageParent.addView(stage, stageIndex, stageParams);
    }
    vm.fullScreen = enabled;
    stage.findViewById(R.id.video_fullscreen_top).setVisibility(enabled ? View.VISIBLE : View.GONE);
    applyCaptionStyle();
    if (reopen && enabled) showSubtitleDrawer(true);
    fullscreenBack.setEnabled(enabled);
    ((TextView) stage.findViewById(R.id.video_fullscreen)).setText(enabled ? "退出" : "全屏");
    androidx.core.view.WindowInsetsControllerCompat bars =
        androidx.core.view.WindowCompat.getInsetsController(
            requireActivity().getWindow(), requireActivity().getWindow().getDecorView());
    bars.setSystemBarsBehavior(
        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    if (enabled) bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
    else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
    if (rotate)
      requireActivity()
          .setRequestedOrientation(
              enabled
                  ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                  : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
  }

  @Override
  public void onDestroyView() {
    closeDialog();
    if (fullscreenOverlay != null) {
      ((ViewGroup) fullscreenOverlay.getParent()).removeView(fullscreenOverlay);
      fullscreenOverlay = null;
    }
    if (isRemoving() || requireActivity().isFinishing()) {
      vm.fullScreen = false;
      requireActivity()
          .setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
      androidx.core.view.WindowCompat.getInsetsController(
              requireActivity().getWindow(), requireActivity().getWindow().getDecorView())
          .show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
    }
    stage = null;
    stageParent = null;
    caption = null;
    playerView = null;
    drawerList = null;
    drawerAdapter = null;
    adapter = null;
    cueList = null;
    super.onDestroyView();
  }

  private final class CueAdapter extends RecyclerView.Adapter<CueAdapter.Holder> {
    List<SubtitleSegment> items = Collections.emptyList();
    int active = -1;
    final boolean floating;

    CueAdapter() {
      this(false);
    }

    CueAdapter(boolean floating) {
      this.floating = floating;
    }

    final class Holder extends RecyclerView.ViewHolder {
      final TextView time, original, translation, playing;

      Holder(View view) {
        super(view);
        time = view.findViewById(R.id.cue_time);
        original = view.findViewById(R.id.cue_original);
        translation = view.findViewById(R.id.cue_translation);
        playing = view.findViewById(R.id.cue_active);
      }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
      return new Holder(
          LayoutInflater.from(parent.getContext())
              .inflate(
                  floating ? R.layout.item_video_cue_floating : R.layout.item_video_cue,
                  parent,
                  false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
      SubtitleSegment cue = items.get(position);
      holder.time.setText(
          String.format(Locale.ROOT, "%02d:%02d", cue.startMs / 60000, (cue.startMs / 1000) % 60));
      holder.original.setText(cue.sourceText);
      holder.translation.setText(
          cue.translatedText == null
              ? ""
              : (vm.project().demo ? "【模拟译文】" : "") + cue.translatedText);
      holder.translation.setVisibility(
          hideTranslation || cue.translatedText == null ? View.GONE : View.VISIBLE);
      holder.itemView.setSelected(position == active);
      holder.playing.setVisibility(position == active ? View.VISIBLE : View.GONE);
      holder.itemView.setOnClickListener(
          v -> {
            if (player == null || loadedUri == null) {
              notice("请先选择视频");
              return;
            }
            replayCue = loopSentence ? cue : null;
            replayEnd = loopSentence ? cue.endMs : -1;
            player.seekTo(cue.startMs);
            player.play();
            playerView.hideController();
            tickCaptions();
          });
      holder.itemView.setOnLongClickListener(
          v -> {
            edit(cue);
            return true;
          });
    }

    @Override
    public int getItemCount() {
      return items.size();
    }
  }
}
