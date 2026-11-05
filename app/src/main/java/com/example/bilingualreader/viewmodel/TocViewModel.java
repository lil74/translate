package com.example.bilingualreader.viewmodel;

import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.di.AppContainer;
import java.util.List;

public final class TocViewModel extends BaseViewModel {
  public final MutableLiveData<UiState<List<TocEntry>>> entries =
      new MutableLiveData<>(UiState.loading());

  public TocViewModel(AppContainer graph) {
    super(graph);
  }

  public void load(String bookId) {
    entries.setValue(UiState.loading());
    graph.io.execute(
        () -> {
          try {
            entries.postValue(UiState.success(graph.books.getTableOfContents(bookId)));
          } catch (AppException e) {
            entries.postValue(UiState.error(e.getUserMessage()));
          }
        });
  }
}
