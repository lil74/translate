package com.example.bilingualreader.ui.toc;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.recyclerview.widget.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ui.*;
import com.example.bilingualreader.viewmodel.*;
import java.util.*;

public final class TocFragment extends BaseFragment {
  public TocFragment() {
    super(R.layout.fragment_toc);
  }

  public static TocFragment create(String book, String title) {
    TocFragment f = new TocFragment();
    Bundle b = new Bundle();
    b.putString("book", book);
    b.putString("title", title);
    f.setArguments(b);
    return f;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    TocViewModel vm = models().get(TocViewModel.class);
    String book = requireArguments().getString("book"),
        title = requireArguments().getString("title");
    ((TextView) view.findViewById(R.id.book_title)).setText(title);

    TocAdapter adapter =
        new TocAdapter(e -> host().openReader(book, title, e.getChapterId(), e.getParagraphId()));
    RecyclerView list = view.findViewById(R.id.list);
    list.setLayoutManager(new LinearLayoutManager(requireContext()));
    list.setAdapter(adapter);
    vm.entries.observe(
        getViewLifecycleOwner(),
        s -> {
          view.findViewById(R.id.loading)
              .setVisibility(s.status == UiState.Status.LOADING ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.retry)
              .setVisibility(s.status == UiState.Status.ERROR ? View.VISIBLE : View.GONE);
          ((TextView) view.findViewById(R.id.status))
              .setText(
                  s.message != null
                      ? s.message
                      : s.data != null ? s.data.size() + " 个目录项" : "正在读取目录…");
          view.findViewById(R.id.continue_reading).setEnabled(s.data != null && !s.data.isEmpty());
          if (s.data != null) {

            adapter.submit(s.data);
          }
        });
    view.findViewById(R.id.continue_reading)
        .setOnClickListener(v -> host().openReader(book, title, null, null));
    view.findViewById(R.id.retry).setOnClickListener(v -> vm.load(book));
    vm.load(book);
  }

  @Override
  public void onResume() {
    super.onResume();
    host().page("书籍目录", true);
  }
}
