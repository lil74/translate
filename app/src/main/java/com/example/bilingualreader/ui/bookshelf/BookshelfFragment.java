package com.example.bilingualreader.ui.bookshelf;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.*;
import androidx.recyclerview.widget.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.Book;
import com.example.bilingualreader.ui.*;
import com.example.bilingualreader.viewmodel.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class BookshelfFragment extends BaseFragment {
  private BookshelfViewModel vm;
  private final ActivityResultLauncher<String[]> picker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null) vm.importBook(uri);
          });

  public BookshelfFragment() {
    super(R.layout.fragment_bookshelf);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    vm = models().get(BookshelfViewModel.class);
    commands(vm);
    ((TextView) view.findViewById(R.id.mode_banner))
        .setText(graph().demo ? "界面演示版 · 内置两章故事\n模拟译文仅供联调，真实 EPUB 导入待接入。" : "初始框架 · 正式数据与翻译模块尚待接入");
    EntryAdapter<Book> adapter =
        new EntryAdapter<>(
            new EntryAdapter.Presenter<Book>() {
              public String title(Book b) {
                return b.getTitle();
              }

              public String subtitle(Book b) {
                return b.getAuthor()
                    + "\n"
                    + b.getChapterCount()
                    + " 节 · "
                    + (b.getLastReadAt() == null ? "开始阅读" : "继续阅读");
              }

              public void click(Book b) {
                host().openToc(b.getBookId(), b.getTitle());
              }

              public void longClick(Book b) {
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("删除《" + b.getTitle() + "》？")
                    .setMessage("将删除书籍、译文和阅读位置。" + (graph().demo ? "演示书籍将在重启应用后恢复。" : ""))
                    .setNegativeButton("保留", null)
                    .setPositiveButton("删除", (d, w) -> vm.deleteBook(b.getBookId()))
                    .show();
              }
            });
    RecyclerView list = view.findViewById(R.id.list);
    list.setLayoutManager(new LinearLayoutManager(requireContext()));
    list.setAdapter(adapter);
    vm.books.observe(
        getViewLifecycleOwner(),
        s -> {
          view.findViewById(R.id.loading)
              .setVisibility(s.status == UiState.Status.LOADING ? View.VISIBLE : View.GONE);
          if (s.data != null) {
            adapter.submit(s.data);
            view.findViewById(R.id.empty)
                .setVisibility(s.data.isEmpty() ? View.VISIBLE : View.GONE);
          }
        });
    vm.command.observe(
        getViewLifecycleOwner(),
        s ->
            view.findViewById(R.id.import_book)
                .setEnabled(s == null || s.status != UiState.Status.LOADING));
    view.findViewById(R.id.import_book)
        .setOnClickListener(
            v -> picker.launch(new String[] {"application/epub+zip", "application/octet-stream"}));
  }

  @Override
  public void onResume() {
    super.onResume();
    host().page("轻阅 / LIBRARY", false);
  }
}
