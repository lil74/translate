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
  @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
    super.onViewCreated(view,state);
    vm=new ViewModelProvider(this,new ViewModelProvider.Factory(){
      @NonNull @Override public <T extends ViewModel>T create(@NonNull Class<T> type){
        return type.cast(new VideoViewModel(requireContext(),graph()));
      }
    }).get(VideoViewModel.class);
    if(state!=null){exportVtt=state.getBoolean("vtt");exportBilingual=state.getBoolean("bilingual",true);}
    playerView=view.findViewById(R.id.video_player);caption=view.findViewById(R.id.video_caption);
    RecyclerView list=view.findViewById(R.id.video_cues);
    list.setLayoutManager(new LinearLayoutManager(requireContext()));adapter=new CueAdapter();list.setAdapter(adapter);
    view.findViewById(R.id.video_pick).setOnClickListener(v -> videoPicker.launch(new String[]{"video/*"}));
    view.findViewById(R.id.video_subtitle_pick).setOnClickListener(v -> subtitlePicker.launch(new String[]{"*/*"}));
    view.findViewById(R.id.video_cancel).setOnClickListener(v -> {closeDialog();vm.cancel();});
    view.findViewById(R.id.video_ocr).setOnClickListener(v -> chooseOcr());
    view.findViewById(R.id.video_asr).setOnClickListener(v -> chooseSpeech());
    view.findViewById(R.id.video_asr_chinese).setOnClickListener(v -> chooseChineseSpeech());
    view.findViewById(R.id.video_soft_subtitle).setOnClickListener(v -> extractSoftSubtitles());
    view.findViewById(R.id.video_translate).setOnClickListener(v -> confirmTranslation());
    view.findViewById(R.id.video_export).setOnClickListener(v -> chooseExport());
    view.findViewById(R.id.video_service).setVisibility(graph().translationConfig==null?View.GONE:View.VISIBLE);
    view.findViewById(R.id.video_service).setOnClickListener(v -> TranslationSettingsDialog.show(requireContext(),graph().translationConfig));
    Spinner direction=view.findViewById(R.id.video_direction);
    direction.setAdapter(new ArrayAdapter<>(requireContext(),android.R.layout.simple_spinner_dropdown_item,new String[]{"英文 → 中文","中文 → 英文","韩语 → 中文","日语 → 中文"}));
    direction.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(AdapterView<?> p){}
      public void onItemSelected(AdapterView<?> p,View row,int position,long id){
        if(!vm.busy && position!=vm.project().direction){closeDialog();vm.direction(position);}
      }
    }
  }
  private void render(){
    if(getView()==null)return;
    View view=requireView();VideoProject p=vm.project();
    ((TextView)view.findViewById(R.id.video_files)).setText("视频："+(p.videoName==null?"未选择":p.videoName)+"\n字幕："+(p.subtitleName==null?"未导入":p.subtitleName));
    ((TextView)view.findViewById(R.id.video_status)).setText(vm.message);
    ((Spinner)view.findViewById(R.id.video_direction)).setSelection(p.direction);
    view.findViewById(R.id.video_direction).setEnabled(!vm.busy);
    view.findViewById(R.id.video_pick).setEnabled(!vm.busy);
    view.findViewById(R.id.video_ocr).setEnabled(!vm.busy && p.videoUri!=null);
    view.findViewById(R.id.video_asr).setEnabled(!vm.busy && p.videoUri!=null);
    view.findViewById(R.id.video_asr_chinese).setEnabled(!vm.busy && p.videoUri!=null);
    view.findViewById(R.id.video_soft_subtitle).setEnabled(!vm.busy && p.videoUri!=null);
    view.findViewById(R.id.video_subtitle_pick).setEnabled(!vm.busy);
    view.findViewById(R.id.video_translate).setEnabled(!vm.busy && !p.cues.isEmpty());
    ((TextView)view.findViewById(R.id.video_translate)).setText(!p.cues.isEmpty() && p.translatedCount()==p.cues.size()?"重新翻译全部":"翻译 / 继续翻译");
    view.findViewById(R.id.video_cancel).setVisibility(vm.busy?View.VISIBLE:View.GONE);
    ((TextView)view.findViewById(R.id.video_cancel)).setText(vm.recognizingSpeech?"暂停等待":"取消处理");
    view.findViewById(R.id.video_export).setEnabled(!vm.busy && !p.cues.isEmpty() && p.translatedCount()==p.cues.size());
    view.findViewById(R.id.video_demo).setVisibility(p.demo && p.translatedCount()>0?View.VISIBLE:View.GONE);
    ProgressBar progress=view.findViewById(R.id.video_progress);
    progress.setVisibility(vm.busy?View.VISIBLE:View.GONE);progress.setIndeterminate(!vm.translating);
    progress.setProgress(p.cues.isEmpty()?0:p.translatedCount()*1000/p.cues.size());
    if(adapter.items!=p.cues){adapter.items=p.cues;adapter.notifyDataSetChanged();}
    ensureMedia();tickCaptions();
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
  private void extractSoftSubtitles(){
    if(player!=null)player.pause();closeDialog();
    boolean replacing=!vm.project().cues.isEmpty();
    dialog=new MaterialAlertDialogBuilder(requireContext()).setTitle(replacing?"替换当前字幕？":"提取视频内嵌软字幕？")
        .setMessage((replacing?"提取后会替换当前字幕和译文。需要保留时请先导出。":"")
            +"在本机读取视频内嵌的字幕轨道和时间戳，视频不会上传。")
        .setNegativeButton("取消",null).setPositiveButton("提取",(d,w)->vm.extractSoftSubtitles()).show();
  }
  private void chooseSpeech(){
    if(player!=null)player.pause();closeDialog();
    try{
      VideoViewModel.SpeechPrepared prepared=vm.prepareSpeech();
      dialog=new MaterialAlertDialogBuilder(requireContext()).setTitle("上传音频并识别英文语音？")
          .setMessage("会从视频提取 AAC 音频，发送到 "+prepared.translation.endpoint
              +" 对应的团队服务器，并交给阿里云百炼识别，再自动翻译成中文。视频画面不上传。"
              +"语音识别可能按量收费，支持 10 分钟内、20 MB 内音频。成功后会替换当前字幕和译文。"
              +"暂停等待不保证停止已提交的云端计费任务；同一任务可继续获取结果。")
          .setNegativeButton("取消",null).setPositiveButton("同意并开始",(d,w)->vm.recognizeSpeech(prepared)).show();
    }catch(Exception error){notice(error instanceof com.example.bilingualreader.contract.model.AppException
        ?((com.example.bilingualreader.contract.model.AppException)error).getUserMessage():error.getMessage());}
  }
  private void chooseChineseSpeech(){
    if(player!=null)player.pause();closeDialog();
    try{
      VideoViewModel.SpeechPrepared prepared=vm.prepareSpeech("zh");
      dialog=new MaterialAlertDialogBuilder(requireContext()).setTitle("上传音频并识别中文语音？")
          .setMessage("会从视频提取 AAC 音频，发送到 "+prepared.translation.endpoint
              +" 对应的团队服务器，由阿里云百炼识别为中文字幕。视频画面不上传。"
              +"语音识别可能按量收费，支持 10 分钟内、20 MB 内音频。识别结果将替换当前字幕。"
              +"暂停等待不保证停止已提交的云端计费任务；同一任务可继续获取结果。")
          .setNegativeButton("取消",null).setPositiveButton("同意并开始",(d,w)->vm.recognizeChineseSpeech(prepared)).show();
    }catch(Exception error){notice(error instanceof com.example.bilingualreader.contract.model.AppException
        ?((com.example.bilingualreader.contract.model.AppException)error).getUserMessage():error.getMessage());}
  }
  private void confirmTranslation(){
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
