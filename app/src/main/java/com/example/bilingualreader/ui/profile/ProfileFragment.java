package com.example.bilingualreader.ui.profile;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
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
    view.findViewById(R.id.profile_favorites).setOnClickListener(v -> host().openExpressions());
    view.findViewById(R.id.profile_account).setOnClickListener(v -> host().openAccount());
    view.findViewById(R.id.profile_cloud_books).setOnClickListener(v -> host().openCloudBooks());
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
    AccountClient account = new AccountClient(requireContext());
    ((TextView) requireView().findViewById(R.id.profile_account))
        .setText(account.signedIn() ? "账号：" + account.username() + "　›" : "登录或注册账号　›");
    if (account.signedIn()) {
      ((TextView) requireView().findViewById(R.id.profile_cloud_books)).setText("云端书库 · 正在检查　›");
      ((TextView) requireView().findViewById(R.id.profile_favorites)).setText("单词与短语收藏 · 正在检查　›");
      new Thread(() -> {
        String bookLabel, expressionLabel;
        try { bookLabel = "云端书库 · " + new CloudBookClient(requireContext()).list().size() + " 本已上传　›"; }
        catch (Exception error) { bookLabel = "云端书库 · 暂时无法连接　›"; }
        try { expressionLabel = "单词与短语收藏 · " + new CloudExpressionClient(requireContext()).list().size() + " 条已同步　›"; }
        catch (Exception error) { expressionLabel = "单词与短语收藏 · 暂时无法连接　›"; }
        String booksText = bookLabel, expressionsText = expressionLabel;
        if (isAdded()) requireActivity().runOnUiThread(() -> {
          View root = getView();
          if (root == null || !new AccountClient(requireContext()).signedIn()) return;
          ((TextView) root.findViewById(R.id.profile_cloud_books)).setText(booksText);
          ((TextView) root.findViewById(R.id.profile_favorites)).setText(expressionsText);
        });
      }, "profile-cloud-status").start();
    } else {
      ((TextView) requireView().findViewById(R.id.profile_cloud_books)).setText("云端书库 · 登录后使用　›");
      ((TextView) requireView().findViewById(R.id.profile_favorites)).setText("单词与短语收藏　›");
    }
    host().page("我的", false);
  }
}
