package com.example.bilingualreader.ui.bookshelf;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.*;
import androidx.recyclerview.widget.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.Book;
import com.example.bilingualreader.ui.*;
import com.example.bilingualreader.ui.profile.AccountClient;
import com.example.bilingualreader.ui.profile.CloudBookClient;
import com.example.bilingualreader.viewmodel.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class BookshelfFragment extends BaseFragment {
  private BookshelfViewModel vm;
  private MediaGridAdapter<Book> adapter;
  private CloudBookClient cloud;
  private TextView cloudStatus;
  private final java.util.Set<String> cloudIds = new java.util.HashSet<>();
  private java.util.List<Book> shownBooks = java.util.Collections.emptyList();
  private boolean cloudLoaded, cloudBusy;
  private final ActivityResultLauncher<String[]> picker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null) {
              vm.importBook(uri);
            }
          });

  public BookshelfFragment() {
    super(R.layout.fragment_bookshelf);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    vm = models().get(BookshelfViewModel.class);
    cloud = new CloudBookClient(requireContext());
    cloudStatus = view.findViewById(R.id.bookshelf_cloud_status);
    commands(vm);
    adapter =
        new MediaGridAdapter<>(
            new MediaGridAdapter.Presenter<Book>() {
              public String key(Book b) {
                return b.getBookId();
              }

              public String image(Book b) {
                return b.getCoverPath();
              }

              public String detail(Book b) {
                if (cloudIds.contains(b.getBookId())) return "☁ 已上传到云端";
                return cloudLoaded ? "仅在本机 · 长按可上传" : null;
              }

              public String title(Book b) {
                return (new com.example.bilingualreader.ui.reader.ReaderLocalStore(requireContext())
                            .favorite(b.getBookId())
                        ? "★ "
                        : "")
                    + b.getTitle();
              }

              public void click(Book b) {
                host().openReader(b.getBookId(), b.getTitle(), null, null);
              }

              public void longClick(Book b) {
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("《" + b.getTitle() + "》")
                    .setItems(new String[]{cloudIds.contains(b.getBookId()) ? "查看云端副本" : "上传到云端", "删除本机书籍"},
                        (dialog,index) -> {
                          if(index == 0) {
                            if(cloudIds.contains(b.getBookId())) host().openCloudBooks();
                            else confirmUpload(b);
                          } else confirmDeleteLocal(b);
                        })
                    .show();
              }
            },
            false);
    RecyclerView list = view.findViewById(R.id.list);
    MediaGridAdapter.layout(list, false);
    list.setAdapter(adapter);
    vm.books.observe(
        getViewLifecycleOwner(),
        s -> {
          view.findViewById(R.id.loading)
              .setVisibility(s.status == UiState.Status.LOADING ? View.VISIBLE : View.GONE);
          if (s.data != null) {
            java.util.List<Book> shown = new java.util.ArrayList<>();
            boolean favorites = getArguments() != null && getArguments().getBoolean("favorites");
            com.example.bilingualreader.ui.reader.ReaderLocalStore local =
                new com.example.bilingualreader.ui.reader.ReaderLocalStore(requireContext());
            for (Book book : s.data)
              if (!favorites || local.favorite(book.getBookId())) shown.add(book);
            shownBooks = shown;
            adapter.submit(shownBooks);
            if (favorites) ((TextView) view.findViewById(R.id.empty)).setText("还没有收藏的书籍");
            view.findViewById(R.id.empty).setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
          }
        });
    vm.command.observe(
        getViewLifecycleOwner(),
        s -> {
          boolean busy = s != null && s.status == UiState.Status.LOADING;
          view.findViewById(R.id.import_book).setEnabled(!busy);
          view.findViewById(R.id.command_progress).setVisibility(busy ? View.VISIBLE : View.GONE);
        });
    view.findViewById(R.id.import_book)
        .setOnClickListener(
            v ->
                picker.launch(
                    new String[] {
                      "application/epub+zip", "text/plain", "application/octet-stream"
                    }));
    view.findViewById(R.id.open_cloud_books).setOnClickListener(v -> host().openCloudBooks());
    view.findViewById(R.id.upload_cloud_book).setOnClickListener(v -> chooseUpload());
  }

  private void chooseUpload() {
    if (cloudBusy) {
      Toast.makeText(requireContext(), "正在处理云端书库，请稍候", Toast.LENGTH_SHORT).show();
      return;
    }
    if (shownBooks.isEmpty()) {
      Toast.makeText(requireContext(), "请先导入本地书籍", Toast.LENGTH_LONG).show();
      return;
    }
    String[] titles = new String[shownBooks.size()];
    for (int i = 0; i < shownBooks.size(); i++)
      titles[i] = (cloudIds.contains(shownBooks.get(i).getBookId()) ? "☁ " : "") + shownBooks.get(i).getTitle();
    new MaterialAlertDialogBuilder(requireContext()).setTitle("选择要上传的书籍")
        .setItems(titles, (dialog,index) -> confirmUpload(shownBooks.get(index)))
        .setNegativeButton("取消", null).show();
  }

  private void confirmUpload(Book book) {
    if (!new AccountClient(requireContext()).signedIn()) {
      new MaterialAlertDialogBuilder(requireContext()).setMessage("请先登录账号，再上传书籍。")
          .setNegativeButton("取消", null).setPositiveButton("去登录", (d,w) -> host().openAccount()).show();
      return;
    }
    new MaterialAlertDialogBuilder(requireContext()).setTitle("上传《"+book.getTitle()+"》到云端？")
        .setMessage("上传原始 EPUB/TXT 文件，绑定当前登录账号。")
        .setNegativeButton("取消", null).setPositiveButton("上传", (d,w) -> upload(book)).show();
  }

  private void upload(Book book) {
    if (cloudBusy) return;
    cloudBusy = true;
    cloudStatus.setText("正在上传《"+book.getTitle()+"》…");
    new Thread(() -> {
      try {
        boolean exists = cloud.upload(book);
        if(isAdded()) requireActivity().runOnUiThread(() -> {
          cloudBusy = false;
          cloudStatus.setText(exists ? "《"+book.getTitle()+"》已在云端" : "《"+book.getTitle()+"》上传成功");
          refreshCloud();
        });
      } catch (Exception error) {
        if(isAdded()) requireActivity().runOnUiThread(() -> {
          cloudBusy = false;
          cloudStatus.setText("上传失败："+error.getMessage());
        });
      }
    }, "bookshelf-cloud-upload").start();
  }

  private void refreshCloud() {
    if (cloudBusy || cloudStatus == null) return;
    cloudIds.clear(); cloudLoaded = false;
    if (adapter != null) adapter.submit(shownBooks);
    if (!new AccountClient(requireContext()).signedIn()) {
      cloudStatus.setText("登录后可将本地书籍上传到云端");
      return;
    }
    cloudBusy = true;
    cloudStatus.setText("正在读取云端书库…");
    new Thread(() -> {
      try {
        java.util.List<CloudBookClient.RemoteBook> rows = cloud.list();
        if(isAdded()) requireActivity().runOnUiThread(() -> {
          cloudIds.clear(); for(CloudBookClient.RemoteBook row:rows) cloudIds.add(row.id);
          cloudLoaded = true; cloudBusy = false;
          cloudStatus.setText("云端已有 " + rows.size() + " 本书 · 长按本地书籍可上传");
          if(adapter != null) adapter.submit(shownBooks);
        });
      } catch (Exception error) {
        if(isAdded()) requireActivity().runOnUiThread(() -> {
          cloudBusy = false; cloudStatus.setText("读取云端状态失败："+error.getMessage());
        });
      }
    }, "bookshelf-cloud-list").start();
  }

  private void confirmDeleteLocal(Book book) {
    new MaterialAlertDialogBuilder(requireContext()).setTitle("删除本机《"+book.getTitle()+"》？")
        .setMessage("将删除本机书籍、译文和阅读位置。云端副本会保留。")
        .setNegativeButton("保留", null)
        .setPositiveButton("删除本机", (d,w) -> vm.deleteBook(book.getBookId())).show();
  }

  private boolean isTxt(Uri uri) {
    String mime = requireContext().getContentResolver().getType(uri);
    if ("text/plain".equals(mime)) return true;
    try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (column >= 0) return cursor.getString(column).toLowerCase(Locale.ROOT).endsWith(".txt");
      }
    } catch (RuntimeException ignored) {
    }
    return uri.getLastPathSegment() != null
        && uri.getLastPathSegment().toLowerCase(Locale.ROOT).endsWith(".txt");
  }

  private void showTxtImport(Uri uri) {
    EditText input = new EditText(requireContext());
    input.setHint("例如：^第[一二三四五六七八九十\\d]+章.*$");
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    input.setMinLines(2);
    int padding = (int) (20 * getResources().getDisplayMetrics().density);
    input.setPadding(padding, padding, padding, padding);
    androidx.appcompat.app.AlertDialog dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("TXT 目录识别")
            .setMessage("留空使用内置章节规则。也可填写 Java 正则表达式匹配整行章节标题；第一个捕获组可作为目录名称。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String regex = input.getText().toString().trim();
                      if (!regex.isEmpty()) {
                        if (regex.length() > 300) {
                          input.setError("正则表达式不能超过 300 字符");
                          return;
                        }
                        try {
                          Pattern.compile(regex);
                        } catch (PatternSyntaxException e) {
                          input.setError("正则表达式无效：" + e.getDescription());
                          return;
                        }
                      }
                      vm.importBook(uri, regex);
                      dialog.dismiss();
                    }));
    dialog.show();
  }

  @Override
  public void onDestroyView() {
    if (adapter != null) {
      adapter.close();
      adapter = null;
    }
    super.onDestroyView();
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshCloud();
    host()
        .page(
            getArguments() != null && getArguments().getBoolean("favorites") ? "我的收藏" : "我的书架",
            true);
  }
}
