package com.example.bilingualreader.viewmodel;

import androidx.annotation.NonNull;
import androidx.lifecycle.*;
import com.example.bilingualreader.di.AppContainer;

public final class ViewModelFactory implements ViewModelProvider.Factory {
  private final AppContainer graph;

  public ViewModelFactory(AppContainer graph) {
    this.graph = graph;
  }

  @NonNull
  @Override
  public <T extends ViewModel> T create(@NonNull Class<T> type) {
    ViewModel model;
    if (type == BookshelfViewModel.class) model = new BookshelfViewModel(graph);
    else if (type == TocViewModel.class) model = new TocViewModel(graph);
    else if (type == ReaderViewModel.class) model = new ReaderViewModel(graph);
    else if (type == LookupViewModel.class) model = new LookupViewModel(graph);
    else if (type == SettingsViewModel.class) model = new SettingsViewModel(graph);
    else throw new IllegalArgumentException(type.getName());
    return type.cast(model);
  }
}
