package com.example.bilingualreader.ui;

import android.widget.Toast;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.viewmodel.*;

public abstract class BaseFragment extends Fragment {
  protected BaseFragment(int layout) {
    super(layout);
  }

  protected AppContainer graph() {
    return ((ReaderApplication) requireActivity().getApplication()).getContainer();
  }

  protected ViewModelProvider models() {
    return new ViewModelProvider(this, new ViewModelFactory(graph()));
  }

  protected MainActivity host() {
    return (MainActivity) requireActivity();
  }

  protected void commands(BaseViewModel vm) {
    vm.command.observe(
        getViewLifecycleOwner(),
        s -> {
          if (s != null && s.status != UiState.Status.LOADING) {
            String message = s.status == UiState.Status.ERROR ? s.message : s.data;
            if (message != null && !message.isEmpty())
              Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            vm.consumeCommand();
          }
        });
  }
}
