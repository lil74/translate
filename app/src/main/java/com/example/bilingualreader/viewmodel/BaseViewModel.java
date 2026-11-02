package com.example.bilingualreader.viewmodel;

import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.AppException;
import com.example.bilingualreader.di.AppContainer;

public abstract class BaseViewModel extends ViewModel {
  protected final AppContainer graph;
  public final MutableLiveData<UiState<String>> command = new MutableLiveData<>();

  protected BaseViewModel(AppContainer graph) {
    this.graph = graph;
  }

  protected interface Action {
    String run() throws AppException;
  }

  protected void execute(Action action) {
    command.setValue(UiState.loading());
    graph.io.execute(
        () -> {
          try {
            command.postValue(UiState.success(action.run()));
          } catch (AppException e) {
            command.postValue(UiState.error(e.getUserMessage()));
          } catch (Exception e) {
            command.postValue(UiState.error("操作失败，请重试"));
          }
        });
  }

  public void consumeCommand() {
    command.setValue(null);
  }
}
