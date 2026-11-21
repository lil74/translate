package com.example.bilingualreader.ui.video;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.*;
import androidx.lifecycle.*;
import androidx.recyclerview.widget.RecyclerView;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ui.*;
import com.example.bilingualreader.video.VideoLibrary;
import com.example.bilingualreader.viewmodel.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

public final class VideoLibraryFragment extends BaseFragment {
  private VideoLibraryViewModel vm;
  private MediaGridAdapter<VideoLibrary.Entry> adapter;
  private final ActivityResultLauncher<String[]> picker =
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
              Toast.makeText(requireContext(), "无法保留访问授权，请选择本机视频文件", Toast.LENGTH_LONG).show();
            }
          });

  public VideoLibraryFragment() {
    super(R.layout.fragment_video_library);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    vm =
        new ViewModelProvider(
                this,
                new ViewModelProvider.Factory() {
                  @NonNull
                  public <T extends ViewModel> T create(@NonNull Class<T> type) {
                    return type.cast(new VideoLibraryViewModel(requireContext()));
                  }
                })
            .get(VideoLibraryViewModel.class);
    getChildFragmentManager()
        .setFragmentResultListener(
            VideoNameDialog.RESULT,
            getViewLifecycleOwner(),
            (key, result) ->
                vm.add(
                    result.getString("uri"),
                    result.getString("filename"),
                    result.getString("customName")));
    adapter =
        new MediaGridAdapter<>(
            new MediaGridAdapter.Presenter<VideoLibrary.Entry>() {
              public String key(VideoLibrary.Entry e) {
                return e.uri;
              }

              public String image(VideoLibrary.Entry e) {
                return e.uri;
              }

              public String title(VideoLibrary.Entry e) {
                return e.name == null ? "视频" : e.name;
              }

              public String detail(VideoLibrary.Entry e) {
                long seconds = e.positionMs / 1000;
                return seconds == 0
                    ? "未观看"
                    : String.format(Locale.CHINA, "看到 %02d:%02d", seconds / 60, seconds % 60);
              }

              public void click(VideoLibrary.Entry e) {
                host().openVideoProject(e.uri);
              }

              public void longClick(VideoLibrary.Entry e) {
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("移除这个视频？")
                    .setMessage("本地字幕、译文和学习进度会移除，原视频文件保留。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("移除", (d, w) -> vm.delete(e.uri))
                    .show();
              }
            },
            true);
    RecyclerView list = view.findViewById(R.id.video_library_list);
    MediaGridAdapter.layout(list, true);
    list.setAdapter(adapter);
    view.findViewById(R.id.import_video)
        .setOnClickListener(v -> picker.launch(new String[] {"video/*"}));
    view.findViewById(R.id.video_library_retry).setOnClickListener(v -> vm.refresh());
    vm.items.observe(
        getViewLifecycleOwner(),
        s -> {
          boolean loading = s.status == UiState.Status.LOADING,
              error = s.status == UiState.Status.ERROR;
          view.findViewById(R.id.video_library_loading)
              .setVisibility(loading ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.import_video).setEnabled(!loading);
          view.findViewById(R.id.video_library_retry)
              .setVisibility(error ? View.VISIBLE : View.GONE);
          if (s.data != null) adapter.submit(s.data);
          TextView empty = view.findViewById(R.id.video_library_empty);
          empty.setVisibility(
              error || s.data != null && s.data.isEmpty() ? View.VISIBLE : View.GONE);
          empty.setText(error ? s.message : "还没有视频");
        });
    vm.notice.observe(
        getViewLifecycleOwner(),
        text -> {
          if (text != null) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
            vm.notice.setValue(null);
          }
        });
  }

  private String name(Uri uri) {
    try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (column >= 0 && c.getString(column) != null) return c.getString(column);
      }
    } catch (RuntimeException ignored) {
    }
    return "视频";
  }

  @Override
  public void onResume() {
    super.onResume();
    host().page("我的视频", true);
    if (vm != null) vm.refresh();
  }

  @Override
  public void onDestroyView() {
    if (adapter != null) {
      adapter.close();
      adapter = null;
    }
    super.onDestroyView();
  }
}
