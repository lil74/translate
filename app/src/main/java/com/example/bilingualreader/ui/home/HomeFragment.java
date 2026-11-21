package com.example.bilingualreader.ui.home;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.lifecycle.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ui.BaseFragment;
import com.example.bilingualreader.ui.reader.ReaderLocalStore;
import com.example.bilingualreader.viewmodel.*;
import java.util.Locale;

public final class HomeFragment extends BaseFragment {
  private HomeViewModel vm;

  public HomeFragment() {
    super(R.layout.fragment_home);
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
                    return type.cast(new HomeViewModel(graph(), requireContext()));
                  }
                })
            .get(HomeViewModel.class);
    BookshelfViewModel library = models().get(BookshelfViewModel.class);
    library.books.observe(
        getViewLifecycleOwner(),
        s -> {
          if (s.data != null) vm.books(s.data);
        });
    view.findViewById(R.id.home_lookup).setOnClickListener(v -> host().openLookup());
    view.findViewById(R.id.home_library).setOnClickListener(v -> host().openBookshelf(false));
    view.findViewById(R.id.home_videos).setOnClickListener(v -> host().openVideoLibrary());
    view.findViewById(R.id.home_video)
        .setOnClickListener(
            v -> {
              HomeViewModel.Summary s = vm.summary.getValue();
              if (s != null && s.video.videoUri != null) host().openVideoProject(s.video.videoUri);
              else host().openVideoLibrary();
            });
    view.findViewById(R.id.home_book)
        .setOnClickListener(
            v -> {
              HomeViewModel.Summary s = vm.summary.getValue();
              if (s != null && s.book != null)
                host().openReader(s.book.getBookId(), s.book.getTitle(), null, null);
              else host().openBookshelf(false);
            });
    vm.summary.observe(
        getViewLifecycleOwner(),
        s -> {
          ((TextView) view.findViewById(R.id.home_book_title))
              .setText(s.book == null ? "还没有阅读记录" : s.book.getTitle());
          ((TextView) view.findViewById(R.id.home_book_position))
              .setText(s.book == null ? "" : s.chapter);
          ((TextView) view.findViewById(R.id.home_book_action))
              .setText(s.book == null ? "打开书架 →" : "继续阅读 →");
          android.content.SharedPreferences prefs =
              new ReaderLocalStore(requireContext()).preferences();
          ProgressBar progress = view.findViewById(R.id.home_book_progress);
          boolean has = s.book != null && prefs.contains("progress:" + s.book.getBookId());
          progress.setVisibility(has ? View.VISIBLE : View.GONE);
          if (has)
            progress.setProgress(
                Math.max(0, Math.min(100, prefs.getInt("progress:" + s.book.getBookId(), 0))));
          boolean video = s.video.videoUri != null;
          ((TextView) view.findViewById(R.id.home_video_title))
              .setText(
                  video ? (s.video.videoName == null ? "上次的视频" : s.video.videoName) : "还没有观看记录");
          long seconds = s.video.positionMs / 1000;
          ((TextView) view.findViewById(R.id.home_video_position))
              .setText(
                  s.warning != null
                      ? "记录暂时无法读取，可重新选择视频"
                      : video
                          ? String.format(Locale.CHINA, "看到 %02d:%02d", seconds / 60, seconds % 60)
                          : "");
          ((TextView) view.findViewById(R.id.home_video_action))
              .setText(video ? "继续观看 →" : "打开视频 →");
        });
  }

  @Override
  public void onResume() {
    super.onResume();
    host().page("轻阅", false);
    if (vm != null) vm.refresh();
  }
}
