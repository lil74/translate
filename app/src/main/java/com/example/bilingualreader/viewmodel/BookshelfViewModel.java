package com.example.bilingualreader.viewmodel;

import android.net.Uri;
import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.di.AppContainer;
import java.util.List;

public final class BookshelfViewModel extends BaseViewModel {
  public final LiveData<UiState<List<Book>>> books;

  public BookshelfViewModel(AppContainer graph) {
    super(graph);
    MediatorLiveData<UiState<List<Book>>> state = new MediatorLiveData<>(UiState.loading());
    state.addSource(
        graph.books.observeBooks(),
        list -> {
          if (list != null) state.setValue(UiState.success(list));
        });
    books = state;
  }

  public void importBook(Uri uri) {
    importBook(uri,null);
  }

  public void importBook(Uri uri,String chapterRegex) {
    execute(
        () -> {
          ImportResult result = graph.books.importBook(uri,chapterRegex);
          return (result.isAlreadyExists() ? "书籍已在书架" : "导入成功")
              + (result.getWarnings().isEmpty()
                  ? ""
                  : " · " + String.join("；", result.getWarnings()));
        });
  }

  public void deleteBook(String id) {
    execute(
        () -> {
          graph.books.deleteBook(id);
          return "已删除书籍";
        });
  }
}
