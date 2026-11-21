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
  private boolean drawerVisible, drawerStyle, controllerVisible;
  private int captionSize = 16, captionPosition = 2;
  private String loadedUri;
  private long replayEnd = -1;
  private SubtitleSegment replayCue;
  private RecyclerView cueList;
  private boolean hideTranslation, loopSentence, followPlayback = true, toolsVisible;
  private androidx.activity.OnBackPressedCallback toolsBack;
  private FrameLayout stage, fullscreenOverlay;
  private ViewGroup stageParent;
  private ViewGroup.LayoutParams stageParams;
  private int stageIndex;
  private androidx.activity.OnBackPressedCallback fullscreenBack;

  private final Handler main = new Handler(Looper.getMainLooper());
  private androidx.appcompat.app.AlertDialog dialog;
  private boolean exportVtt, exportBilingual = true;
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
            if (vm.project().cues.isEmpty()) vm.importSubtitles(uri, name(uri));
            else {
              closeDialog();
              dialog =
                  new MaterialAlertDialogBuilder(requireContext())
                      .setTitle("替换当前字幕？")
                      .setMessage("当前字幕和译文会被替换，视频保留。需要保留译文时请先导出字幕。")
                      .setNegativeButton("取消", null)
                      .setPositiveButton("替换", (d, w) -> vm.importSubtitles(uri, name(uri)))
                      .show();
            }
          });
  private final ActivityResultLauncher<String> exporter =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("text/plain"),
          uri -> {
            if (uri != null && vm != null) vm.export(uri, exportVtt, exportBilingual);
          });

  public VideoFragment() {
    super(R.layout.fragment_video);
  }

  private void selectVideo(Uri uri, String displayName) {
    Runnable apply =
        () -> {
          loadedUri = null;
          vm.video(uri, displayName);
        };
    if (vm.project().videoUri != null
        && !vm.project().videoUri.equals(uri.toString())
        && !vm.project().cues.isEmpty()) {
      closeDialog();
      dialog =
          new MaterialAlertDialogBuilder(requireContext())
              .setTitle("切换到另一个视频？")
              .setMessage("当前视频的字幕和译文会保存在视频库中，新视频将使用独立的项目。")
              .setNegativeButton("取消", null)
              .setPositiveButton("切换", (d, w) -> apply.run())
              .show();
    } else apply.run();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    vm =
        new ViewModelProvider(
                this,
                new ViewModelProvider.Factory() {
                  @NonNull
                  @Override
                  public <T extends ViewModel> T create(@NonNull Class<T> type) {
                    return type.cast(
                        new VideoViewModel(
                            requireContext(),
                            graph(),
                            getArguments() == null ? null : getArguments().getString("videoUri")));
                  }
                })
            .get(VideoViewModel.class);
    getChildFragmentManager()
        .setFragmentResultListener(
            VideoNameDialog.RESULT,
            getViewLifecycleOwner(),
            (key, result) -> {
              String custom = result.getString("customName", "");
              selectVideo(
                  Uri.parse(result.getString("uri")),
                  custom.isEmpty() ? result.getString("filename") : custom);
            });
    if (state != null) {
      exportVtt = state.getBoolean("vtt");
      exportBilingual = state.getBoolean("bilingual", true);
      hideTranslation = state.getBoolean("hideTranslation");
      loopSentence = state.getBoolean("loopSentence");
      followPlayback = state.getBoolean("followPlayback", true);
      toolsVisible = state.getBoolean("toolsVisible");
    }
    if (getArguments() != null && getArguments().containsKey("videoUri"))
      findVideoView(R.id.video_pick).setVisibility(View.GONE);
    playerView = findVideoView(R.id.video_player);
    caption = findVideoView(R.id.video_caption);
    android.content.SharedPreferences style =
        requireContext().getSharedPreferences("video_caption_style", Context.MODE_PRIVATE);
    captionSize = Math.max(12, Math.min(32, style.getInt("size", 16)));
    captionPosition = Math.max(0, Math.min(2, style.getInt("position", 2)));
    if (state != null) {
      drawerVisible = state.getBoolean("drawerVisible");
      drawerStyle = state.getBoolean("drawerStyle");
    }
    playerView.setControllerVisibilityListener(
        (PlayerView.ControllerVisibilityListener)
            visibility -> {
              controllerVisible = visibility == View.VISIBLE;
              applyCaptionStyle();
            });
    RecyclerView list = findVideoView(R.id.video_cues);
    cueList = list;
    list.setLayoutManager(new LinearLayoutManager(requireContext()));
    adapter = new CueAdapter();
    list.setAdapter(adapter);
    findVideoView(R.id.video_pick)
        .setOnClickListener(v -> videoPicker.launch(new String[] {"video/*"}));
    findVideoView(R.id.video_subtitle_pick)
        .setOnClickListener(
            v -> {
              showTools(false);
              subtitlePicker.launch(new String[] {"*/*"});
            });
    findVideoView(R.id.video_cancel)
        .setOnClickListener(
            v -> {
              closeDialog();
              vm.cancel();
            });
    findVideoView(R.id.video_ocr)
        .setOnClickListener(
            v -> {
              showTools(false);
              chooseOcr();
            });
    findVideoView(R.id.video_asr)
        .setOnClickListener(
            v -> {
              showTools(false);
              chooseSpeech();
            });
    findVideoView(R.id.video_translate)
        .setOnClickListener(
            v -> {
              showTools(false);
              confirmTranslation();
            });
    findVideoView(R.id.video_export)
        .setOnClickListener(
            v -> {
              showTools(false);
              chooseExport();
            });
    findVideoView(R.id.video_prepare_subtitles)
        .setOnClickListener(v -> findVideoView(R.id.video_subtitle_pick).performClick());
    findVideoView(R.id.video_prepare_asr)
        .setOnClickListener(v -> findVideoView(R.id.video_asr).performClick());
    findVideoView(R.id.video_prepare_ocr)
        .setOnClickListener(v -> findVideoView(R.id.video_ocr).performClick());
    findVideoView(R.id.video_service)
        .setVisibility(graph().translationConfig == null ? View.GONE : View.VISIBLE);
    findVideoView(R.id.video_service)
        .setOnClickListener(
            v -> TranslationSettingsDialog.show(requireContext(), graph().translationConfig));
    Spinner direction = findVideoView(R.id.video_direction);
    direction.setAdapter(
        new ArrayAdapter<>(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"英文 → 中文", "中文 → 英文"}));
    direction.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View row, int position, long id) {
            if (!vm.busy && position != vm.project().direction) {
              closeDialog();
              vm.direction(position);
            }
          }
        });
    stage = findVideoView(R.id.video_stage);
    stageParent = (ViewGroup) stage.getParent();
    stageParams = stage.getLayoutParams();
    stageIndex = stageParent.indexOfChild(stage);
    TextView speed = findVideoView(R.id.video_speed);
    speed.setText(String.format(java.util.Locale.ROOT, "%.2g×", vm.playbackSpeed));
    speed.setOnClickListener(
        v -> {
          closeDialog();
          dialog =
              new MaterialAlertDialogBuilder(requireContext())
                  .setTitle("播放速度")
                  .setItems(
                      new String[] {"0.75×", "1.0×", "1.25×", "1.5×", "2.0×"},
                      (d, index) -> {
                        vm.playbackSpeed = speeds[index];
                        speed.setText(
                            String.format(java.util.Locale.ROOT, "%.2g×", vm.playbackSpeed));
                        if (player != null) player.setPlaybackSpeed(vm.playbackSpeed);
                      })
                  .show();
        });
    findVideoView(R.id.video_fullscreen)
        .setOnClickListener(v -> setFullscreen(!vm.fullScreen, true));
    fullscreenBack =
        new androidx.activity.OnBackPressedCallback(vm.fullScreen) {
          @Override
          public void handleOnBackPressed() {
            setFullscreen(false, true);
          }
        };
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(getViewLifecycleOwner(), fullscreenBack);
    toolsBack =
        new androidx.activity.OnBackPressedCallback(false) {
          @Override
          public void handleOnBackPressed() {
            if (drawerVisible) showSubtitleDrawer(false);
            else showTools(false);
          }
        };
    requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), toolsBack);
    findVideoView(R.id.video_tools).setOnClickListener(v -> showTools(true));
    findVideoView(R.id.video_player_subtitles)
        .setOnClickListener(
            v -> {
              if (vm.fullScreen) {
                drawerStyle = false;
                showSubtitleDrawer(true);
              } else showTools(true);
            });
    findVideoView(R.id.video_exit_fullscreen).setOnClickListener(v -> setFullscreen(false, true));
    findVideoView(R.id.video_tools_close).setOnClickListener(v -> showTools(false));
    findVideoView(R.id.video_tools_scrim).setOnClickListener(v -> showTools(false));
    findVideoView(R.id.video_status)
        .setOnClickListener(
            v -> {
              closeDialog();
              dialog =
                  new MaterialAlertDialogBuilder(requireContext())
                      .setTitle("处理状态")
                      .setMessage(vm.message)
                      .setPositiveButton("知道了", null)
                      .show();
            });
    findVideoView(R.id.video_hide_translation)
        .setOnClickListener(
            v -> {
              hideTranslation = !hideTranslation;
              adapter.notifyDataSetChanged();
              updateStudyButtons();
              tickCaptions();
            });
    findVideoView(R.id.video_follow)
        .setOnClickListener(
            v -> {
              followPlayback = !followPlayback;
              updateStudyButtons();
              if (followPlayback) followActiveCue();
            });
    com.google.android.material.switchmaterial.SwitchMaterial loop = findVideoView(R.id.video_loop);
    loop.setChecked(loopSentence);
    loop.setOnCheckedChangeListener(
        (button, checked) -> {
          loopSentence = checked;
          updateStudyButtons();
          if (checked && adapter.active >= 0 && adapter.active < adapter.items.size()) {
            replayCue = adapter.items.get(adapter.active);
            replayEnd = replayCue.endMs;
          }
        });
    ((CompoundButton) findVideoView(R.id.video_panel_hide))
        .setOnClickListener(v -> findVideoView(R.id.video_hide_translation).performClick());
    ((CompoundButton) findVideoView(R.id.video_panel_loop))
        .setOnClickListener(
            v ->
                ((CompoundButton) findVideoView(R.id.video_loop))
                    .setChecked(((CompoundButton) v).isChecked()));
    list.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrollStateChanged(@NonNull RecyclerView rows, int state) {
            if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
              followPlayback = false;
              updateStudyButtons();
            }
          }
        });
    view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> resizeVideoLayout());
    drawerList = findVideoView(R.id.video_drawer_cues);
    drawerList.setLayoutManager(new LinearLayoutManager(requireContext()));
    drawerAdapter = new CueAdapter(true);
    drawerList.setAdapter(drawerAdapter);
    drawerList.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrollStateChanged(@NonNull RecyclerView rows, int state) {
            if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
              followPlayback = false;
              updateStudyButtons();
            }
          }
        });
    findVideoView(R.id.video_drawer_close).setOnClickListener(v -> showSubtitleDrawer(false));
    findVideoView(R.id.video_subtitle_scrim).setOnClickListener(v -> showSubtitleDrawer(false));
    findVideoView(R.id.video_drawer_list_tab)
        .setOnClickListener(
            v -> {
              drawerStyle = false;
              updateDrawer();
            });
    findVideoView(R.id.video_drawer_style_tab)
        .setOnClickListener(
            v -> {
              drawerStyle = true;
              updateDrawer();
            });
    findVideoView(R.id.video_caption_smaller)
        .setOnClickListener(v -> saveCaptionStyle(captionSize - 2, captionPosition));
    findVideoView(R.id.video_caption_larger)
        .setOnClickListener(v -> saveCaptionStyle(captionSize + 2, captionPosition));
    findVideoView(R.id.video_caption_top).setOnClickListener(v -> saveCaptionStyle(captionSize, 0));
    findVideoView(R.id.video_caption_middle)
        .setOnClickListener(v -> saveCaptionStyle(captionSize, 1));
    findVideoView(R.id.video_caption_bottom)
        .setOnClickListener(v -> saveCaptionStyle(captionSize, 2));
    findVideoView(R.id.video_drawer_hide)
        .setOnClickListener(v -> findVideoView(R.id.video_hide_translation).performClick());
    findVideoView(R.id.video_drawer_loop)
        .setOnClickListener(v -> ((CompoundButton) findVideoView(R.id.video_loop)).toggle());
    stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> resizeDrawer());
    applyCaptionStyle();
    showTools(toolsVisible);
    updateStudyButtons();
    vm.changes.observe(getViewLifecycleOwner(), ignored -> render());
  }

  private <T extends View> T findVideoView(int id) {
    T view = getView() == null ? null : getView().findViewById(id);
    return view != null
        ? view
        : fullscreenOverlay == null ? null : fullscreenOverlay.findViewById(id);
  }

  private void showTools(boolean show) {
    if (show && vm.fullScreen) {
      showSubtitleDrawer(true);
      return;
    }
    toolsVisible = show;
    if (getView() == null) return;
    findVideoView(R.id.video_tools_panel).setVisibility(show ? View.VISIBLE : View.GONE);
    findVideoView(R.id.video_tools_scrim).setVisibility(show ? View.VISIBLE : View.GONE);
    if (toolsBack != null) toolsBack.setEnabled(show || drawerVisible);
    View content = vm.fullScreen ? stage : ((ViewGroup) requireView()).getChildAt(0);
    content.setImportantForAccessibility(
        show
            ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    if (show) {
      if (playerView != null) playerView.hideController();
      resizeVideoLayout();
      findVideoView(R.id.video_tools_close).requestFocus();
    }
  }

  private void showSubtitleDrawer(boolean show) {
    drawerVisible = show;
    if (stage == null) return;
    stage.findViewById(R.id.video_subtitle_drawer).setVisibility(show ? View.VISIBLE : View.GONE);
    stage.findViewById(R.id.video_subtitle_scrim).setVisibility(show ? View.VISIBLE : View.GONE);
    if (toolsBack != null) toolsBack.setEnabled(show || toolsVisible);
    if (playerView != null) playerView.setUseController(!show);
    if (show) {
      playerView.hideController();
      resizeDrawer();
      updateDrawer();
      followActiveCue();
    }
    applyCaptionStyle();
  }

  private void resizeDrawer() {
    if (stage == null || stage.getWidth() == 0) return;
    View drawer = stage.findViewById(R.id.video_subtitle_drawer);
    int width = Math.min(dp(300), Math.max(dp(220), Math.round(stage.getWidth() * .38f)));
    if (drawer.getLayoutParams().width != width) {
      drawer.getLayoutParams().width = width;
      drawer.requestLayout();
      applyCaptionStyle();
    }
  }

  private void updateDrawer() {
    if (stage == null || drawerAdapter == null) return;
    ((TextView) findVideoView(R.id.video_drawer_count))
        .setText("字幕 · " + drawerAdapter.getItemCount() + " 句");
    findVideoView(R.id.video_drawer_cues).setVisibility(drawerStyle ? View.GONE : View.VISIBLE);
    findVideoView(R.id.video_drawer_empty)
        .setVisibility(
            !drawerStyle && drawerAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    findVideoView(R.id.video_drawer_style).setVisibility(drawerStyle ? View.VISIBLE : View.GONE);
    findVideoView(R.id.video_drawer_list_tab).setAlpha(drawerStyle ? .55f : 1f);
    findVideoView(R.id.video_drawer_style_tab).setAlpha(drawerStyle ? 1f : .55f);
    ((TextView) findVideoView(R.id.video_caption_size)).setText(captionSize + " sp");
    findVideoView(R.id.video_caption_smaller).setEnabled(captionSize > 12);
    findVideoView(R.id.video_caption_larger).setEnabled(captionSize < 32);
    int[] ids = {R.id.video_caption_top, R.id.video_caption_middle, R.id.video_caption_bottom};
    for (int i = 0; i < ids.length; i++) {
      View button = findVideoView(ids[i]);
      button.setSelected(i == captionPosition);
      button.setAlpha(i == captionPosition ? 1f : .5f);
    }
    ((TextView) findVideoView(R.id.video_drawer_hide)).setText(hideTranslation ? "显示译文" : "隐藏译文");
    ((TextView) findVideoView(R.id.video_drawer_loop)).setText(loopSentence ? "单句循环：开" : "单句循环：关");
  }

  private void saveCaptionStyle(int size, int position) {
    captionSize = Math.max(12, Math.min(32, size));
    captionPosition = position;
    requireContext()
        .getSharedPreferences("video_caption_style", Context.MODE_PRIVATE)
        .edit()
        .putInt("size", captionSize)
        .putInt("position", position)
        .apply();
    applyCaptionStyle();
    updateDrawer();
  }

  private void applyCaptionStyle() {
    if (caption == null) return;
    caption.setTextSize(captionSize);
    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) caption.getLayoutParams();
    params.rightMargin =
        drawerVisible && stage != null
            ? stage.findViewById(R.id.video_subtitle_drawer).getLayoutParams().width
            : 0;
    params.gravity =
        (captionPosition == 0
                ? Gravity.TOP
                : captionPosition == 1 ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM)
            | Gravity.LEFT;
    params.topMargin = captionPosition == 0 ? dp(controllerVisible && vm.fullScreen ? 64 : 16) : 0;
    params.bottomMargin = captionPosition == 2 ? dp(controllerVisible ? 92 : 16) : 0;
    caption.setLayoutParams(params);
  }

  private void resizeVideoLayout() {
    if (getView() == null || getView().getHeight() == 0) return;
    View panel = findVideoView(R.id.video_tools_panel);
    FrameLayout.LayoutParams panelParams = (FrameLayout.LayoutParams) panel.getLayoutParams();
    int oldWidth = panelParams.width,
        oldHeight = panelParams.height,
        oldGravity = panelParams.gravity;
    panelParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
    panelParams.gravity = Gravity.BOTTOM;
    panelParams.height =
        Math.min(dp(580), Math.max(dp(180), (int) (requireView().getHeight() * .78f)));
    if (oldWidth != panelParams.width
        || oldHeight != panelParams.height
        || oldGravity != panelParams.gravity) panel.setLayoutParams(panelParams);
    resizeDrawer();
    if (stage != null && !vm.fullScreen) {
      int height =
          Math.min(requireView().getWidth() * 9 / 16, (int) (requireView().getHeight() * .4f));
      if (stage.getLayoutParams().height != height) {
        stage.getLayoutParams().height = height;
        stage.requestLayout();
      }
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void updateStudyButtons() {
    if (getView() == null) return;
    if (drawerAdapter != null) {
      drawerAdapter.notifyDataSetChanged();
      updateDrawer();
    }
    ((CompoundButton) findVideoView(R.id.video_panel_hide)).setChecked(hideTranslation);
    ((CompoundButton) findVideoView(R.id.video_panel_loop)).setChecked(loopSentence);
    ((TextView) findVideoView(R.id.video_hide_translation))
        .setText(hideTranslation ? "显示译文" : "隐藏译文");
    View follow = findVideoView(R.id.video_follow);
    follow.setSelected(followPlayback);
    ((TextView) follow).setText(followPlayback ? "跟随中" : "跟随播放");
  }

  private void followActiveCue() {
    if (cueList != null && adapter != null && adapter.active >= 0)
      ((LinearLayoutManager) cueList.getLayoutManager())
          .scrollToPositionWithOffset(adapter.active, dp(8));
    if (drawerVisible && drawerList != null && drawerAdapter.active >= 0)
      ((LinearLayoutManager) drawerList.getLayoutManager())
          .scrollToPositionWithOffset(drawerAdapter.active, dp(8));
  }

  private void render() {
    if (getView() == null) return;
    View view = requireView();
    VideoProject p = vm.project();
    boolean hasCues = !p.cues.isEmpty();
    ((TextView) findVideoView(R.id.video_title))
        .setText(p.videoName == null ? "视频学习" : p.videoName);
    ((TextView) findVideoView(R.id.video_fullscreen_title))
        .setText(p.videoName == null ? "视频学习" : p.videoName);
    ((TextView) findVideoView(R.id.video_summary))
        .setText(hasCues ? p.cues.size() + " 句字幕 · 已翻译 " + p.translatedCount() + " 句" : "准备字幕");
    findVideoView(R.id.video_learning_controls).setVisibility(hasCues ? View.VISIBLE : View.GONE);
    findVideoView(R.id.video_preparation).setVisibility(hasCues ? View.GONE : View.VISIBLE);
    findVideoView(R.id.video_cues).setVisibility(hasCues ? View.VISIBLE : View.GONE);
    ((TextView) findVideoView(R.id.video_files))
        .setText(
            "视频："
                + (p.videoName == null ? "未选择" : p.videoName)
                + "\n字幕："
                + (p.subtitleName == null ? "未导入" : p.subtitleName));
    ((TextView) findVideoView(R.id.video_status)).setText(vm.message);
    ((Spinner) findVideoView(R.id.video_direction)).setSelection(p.direction);
    findVideoView(R.id.video_direction).setEnabled(!vm.busy);
    findVideoView(R.id.video_pick).setEnabled(!vm.busy);
    findVideoView(R.id.video_ocr).setEnabled(!vm.busy && p.videoUri != null);
    findVideoView(R.id.video_asr).setEnabled(!vm.busy && p.videoUri != null);
    findVideoView(R.id.video_subtitle_pick).setEnabled(!vm.busy);
    findVideoView(R.id.video_prepare_subtitles).setEnabled(!vm.busy);
    findVideoView(R.id.video_prepare_asr).setEnabled(!vm.busy && p.videoUri != null);
    findVideoView(R.id.video_prepare_ocr).setEnabled(!vm.busy && p.videoUri != null);
    findVideoView(R.id.video_translate).setEnabled(!vm.busy && !p.cues.isEmpty());
    ((TextView) findVideoView(R.id.video_translate))
        .setText(
            !p.cues.isEmpty() && p.translatedCount() == p.cues.size() ? "重新翻译全部" : "翻译 / 继续翻译");
    findVideoView(R.id.video_cancel).setVisibility(vm.busy ? View.VISIBLE : View.GONE);
    ((TextView) findVideoView(R.id.video_cancel)).setText(vm.recognizingSpeech ? "暂停等待" : "取消处理");
    findVideoView(R.id.video_export)
        .setEnabled(!vm.busy && !p.cues.isEmpty() && p.translatedCount() == p.cues.size());
    findVideoView(R.id.video_demo)
        .setVisibility(p.demo && p.translatedCount() > 0 ? View.VISIBLE : View.GONE);
    ProgressBar progress = findVideoView(R.id.video_progress);
    progress.setVisibility(vm.busy ? View.VISIBLE : View.GONE);
    progress.setIndeterminate(!vm.translating);
    progress.setProgress(p.cues.isEmpty() ? 0 : p.translatedCount() * 1000 / p.cues.size());
    if (adapter.items != p.cues) {
      adapter.items = p.cues;
      adapter.active = -1;
      adapter.notifyDataSetChanged();
      if (replayCue != null) {
        SubtitleSegment found = null;
        for (SubtitleSegment cue : p.cues) if (cue.id.equals(replayCue.id)) found = cue;
        replayCue = found;
        replayEnd = found == null ? -1 : found.endMs;
      }
    }
    if (drawerAdapter.items != p.cues) {
      drawerAdapter.items = p.cues;
      drawerAdapter.active = -1;
      drawerAdapter.notifyDataSetChanged();
    }
    updateDrawer();
    ensureMedia();
    tickCaptions();
  }

  private void chooseOcr() {
    if (player != null) player.pause();
    closeDialog();
    String[] regions = {"底部字幕区域（下方 35%）", "中下部（下方 60%）", "整个画面（可能包含水印、标题）"};
    dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("识别并翻译：选择英文区域")
            .setItems(
                regions,
                (d, index) -> {
                  try {
                    VideoViewModel.Prepared prepared = vm.prepareEnglishOcr();
                    float top = index == 0 ? .65f : index == 1 ? .4f : 0f;
                    dialog =
                        new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("识别画面英文并翻译成中文？")
                            .setMessage(
                                "识别在本机进行，成功后自动翻译。将替换现有字幕和译文。\n"
                                    + (prepared.binding.demo
                                        ? "当前为模拟模式，只生成明确标记的模拟译文。"
                                        : "识别文字将发送到：" + prepared.endpoint + "。视频不会上传。"))
                            .setNegativeButton("取消", null)
                            .setPositiveButton(
                                "开始", (confirm, w) -> vm.recognize(top, 1f, prepared))
                            .show();
                  } catch (com.example.bilingualreader.contract.model.AppException error) {
                    notice(error.getUserMessage());
                  }
                })
            .show();
  }

  private void chooseSpeech() {
    if (player != null) player.pause();
    closeDialog();
    try {
      VideoViewModel.SpeechPrepared prepared = vm.prepareSpeech();
      dialog =
          new MaterialAlertDialogBuilder(requireContext())
              .setTitle("上传音频并识别英文语音？")
              .setMessage(
                  "会从视频提取 AAC 音频，发送到 "
                      + prepared.translation.endpoint
                      + " 对应的团队服务器，并交给阿里云百炼识别，再自动翻译成中文。视频画面不上传。"
                      + "语音识别可能按量收费，支持 10 分钟内、20 MB 内音频。成功后会替换当前字幕和译文。"
                      + "暂停等待不保证停止已提交的云端计费任务；同一任务可继续获取结果。")
              .setNegativeButton("取消", null)
              .setPositiveButton("同意并开始", (d, w) -> vm.recognizeSpeech(prepared))
              .show();
    } catch (Exception error) {
      notice(
          error instanceof com.example.bilingualreader.contract.model.AppException
              ? ((com.example.bilingualreader.contract.model.AppException) error).getUserMessage()
              : error.getMessage());
    }
  }

  private void confirmTranslation() {
    try {
      VideoViewModel.Prepared prepared =
          vm.prepare(vm.project().translatedCount() == vm.project().cues.size());
      if (prepared.binding.demo) {
        vm.translate(prepared);
        return;
      }
      closeDialog();
      dialog =
          new MaterialAlertDialogBuilder(requireContext())
              .setTitle("发送字幕文本用于翻译？")
              .setMessage(
                  "将向 "
                      + prepared.endpoint
                      + " 发送字幕原文。\n方向："
                      + ("en".equals(prepared.source) ? "英文 → 中文" : "中文 → 英文")
                      + "\n共 "
                      + vm.project().cues.size()
                      + " 条字幕。视频文件不会上传；译文会保存在本机。"
                      + (prepared.restart ? "本次会清除旧译文并重新翻译全部字幕。" : "同一服务已完成的片段会跳过；切换服务会重新生成译文。"))
              .setNegativeButton("取消", null)
              .setPositiveButton("同意并翻译", (d, w) -> vm.translate(prepared))
              .show();
    } catch (com.example.bilingualreader.contract.model.AppException error) {
      notice(error.getUserMessage());
    }
  }

  private void chooseExport() {
    closeDialog();
    String[] options = {"双语 SRT", "仅译文 SRT", "双语 VTT", "仅译文 VTT"};
    dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("导出字幕")
            .setItems(
                options,
                (d, which) -> {
                  exportVtt = which >= 2;
                  exportBilingual = which % 2 == 0;
                  exporter.launch(
                      "video-subtitles"
                          + (vm.project().demo ? "-demo" : "")
                          + (exportVtt ? ".vtt" : ".srt"));
                })
            .show();
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
      if (followPlayback && player.isPlaying()) followActiveCue();
    }
    if (drawerAdapter != null && drawerAdapter.active != active) {
      int old = drawerAdapter.active;
      drawerAdapter.active = active;
      if (old >= 0 && old < drawerAdapter.getItemCount()) drawerAdapter.notifyItemChanged(old);
      if (active >= 0) drawerAdapter.notifyItemChanged(active);
      if (drawerVisible && followPlayback && player.isPlaying()) followActiveCue();
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
    state.putBoolean("vtt", exportVtt);
    state.putBoolean("bilingual", exportBilingual);
    state.putBoolean("hideTranslation", hideTranslation);
    state.putBoolean("loopSentence", loopSentence);
    state.putBoolean("followPlayback", followPlayback);
    state.putBoolean("toolsVisible", toolsVisible);
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
    showTools(false);
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
    resizeVideoLayout();
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
    if (!enabled && getView() != null) getView().post(this::resizeVideoLayout);
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
    toolsBack = null;
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
            replayCue = cue;
            replayEnd = cue.endMs;
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
