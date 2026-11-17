package com.example.bilingualreader.ui.profile;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.Book;
import com.example.bilingualreader.ui.BaseFragment;
import com.example.bilingualreader.ui.reader.ReaderLocalStore;
import com.example.bilingualreader.viewmodel.BookshelfViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

public final class ProfileFragment extends BaseFragment {
  private List<Book> books = Collections.emptyList();

  public ProfileFragment() {
    super(R.layout.fragment_profile);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    models()
        .get(BookshelfViewModel.class)
        .books
        .observe(
            getViewLifecycleOwner(),
            s -> {
              if (s.data != null) books = s.data;
            });
    view.findViewById(R.id.profile_favorites).setOnClickListener(v -> host().openBookshelf(true));
    view.findViewById(R.id.profile_lookup).setOnClickListener(v -> host().openLookup());
    view.findViewById(R.id.profile_settings).setOnClickListener(v -> host().openAppSettings());
    view.findViewById(R.id.profile_bookmarks).setOnClickListener(v -> bookmarks());
  }

  private void bookmarks() {
    List<String> names = new ArrayList<>();
    List<Book> owners = new ArrayList<>();
    List<ReaderLocalStore.Bookmark> marks = new ArrayList<>();
    ReaderLocalStore local = new ReaderLocalStore(requireContext());
    for (Book book : books)
      for (ReaderLocalStore.Bookmark mark : local.bookmarks(book.getBookId())) {
        names.add(book.getTitle() + " · " + mark.title);
        owners.add(book);
        marks.add(mark);
      }
    MaterialAlertDialogBuilder dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("阅读书签")
            .setNegativeButton("关闭", null);
    if (names.isEmpty()) dialog.setMessage("还没有阅读书签");
    else
      dialog.setItems(
          names.toArray(new String[0]),
          (d, index) -> {
            Book b = owners.get(index);
            ReaderLocalStore.Bookmark mark = marks.get(index);
            host().openBookmark(b.getBookId(), b.getTitle(), mark);
          });
    dialog.show();
  }

  @Override
  public void onResume() {
    super.onResume();
    host().page("我的", false);
  }
}
