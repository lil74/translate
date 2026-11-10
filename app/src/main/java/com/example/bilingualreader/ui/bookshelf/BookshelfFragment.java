package com.example.bilingualreader.ui.bookshelf;

import android.os.Bundle;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
            if (uri != null) {
              if (isTxt(uri)) showTxtImport(uri);
              else vm.importBook(uri);
            }
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
        .setText(graph().demo ? "界面演示版 · 内置两章故事\n模拟译文仅供联调。" : "支持导入 EPUB 和 TXT · 阅读位置自动保存");
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
            v -> picker.launch(new String[] {"application/epub+zip", "text/plain", "application/octet-stream"}));
  }

  private boolean isTxt(Uri uri) {
    String mime=requireContext().getContentResolver().getType(uri);
    if ("text/plain".equals(mime)) return true;
    try (Cursor cursor=requireContext().getContentResolver().query(uri,null,null,null,null)) {
      if (cursor!=null && cursor.moveToFirst()) {
        int column=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (column>=0) return cursor.getString(column).toLowerCase(Locale.ROOT).endsWith(".txt");
      }
    } catch (RuntimeException ignored) { }
    return uri.getLastPathSegment()!=null && uri.getLastPathSegment().toLowerCase(Locale.ROOT).endsWith(".txt");
  }

  private void showTxtImport(Uri uri) {
    EditText input=new EditText(requireContext());
    input.setHint("例如：^第[一二三四五六七八九十\\d]+章.*$");
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    input.setMinLines(2);
    int padding=(int)(20*getResources().getDisplayMetrics().density);
    input.setPadding(padding,padding,padding,padding);
    androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(requireContext())
        .setTitle("TXT 目录识别")
        .setMessage("留空使用内置章节规则。也可填写 Java 正则表达式匹配整行章节标题；第一个捕获组可作为目录名称。")
        .setView(input)
        .setNegativeButton("取消",null)
        .setPositiveButton("导入",null)
        .create();
    dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      String regex=input.getText().toString().trim();
      if (!regex.isEmpty()) {
        if (regex.length()>300) {input.setError("正则表达式不能超过 300 字符");return;}
        try {Pattern.compile(regex);} catch (PatternSyntaxException e) {input.setError("正则表达式无效："+e.getDescription());return;}
      }
      vm.importBook(uri,regex);
      dialog.dismiss();
    }));
    dialog.show();
  }

  @Override
  public void onResume() {
    super.onResume();
    host().page("轻阅 / LIBRARY", false);
  }
}
