package com.example.bilingualreader.ui.lookup;

import android.content.*;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.annotation.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.AppException;
import com.example.bilingualreader.ui.BaseFragment;
import com.example.bilingualreader.viewmodel.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class LookupFragment extends BaseFragment {
  private LookupViewModel vm;
  private EditText input;
  private androidx.appcompat.app.AlertDialog confirmation;

  public LookupFragment() {
    super(R.layout.fragment_lookup);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    vm = models().get(LookupViewModel.class);
    input = view.findViewById(R.id.lookup_input);
    input.setText(vm.input);
    // Discard history saved by older builds; this screen is memory-only.
    requireContext().getSharedPreferences("lookup_history", Context.MODE_PRIVATE).edit().clear().apply();
    Spinner direction = view.findViewById(R.id.lookup_direction);
    direction.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item,
        new String[] {"自动判断方向", "英文 → 中文", "中文 → 英文"}));
    direction.setSelection(vm.direction);
    direction.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
        if (vm.direction != position) {
          vm.direction = position;
          vm.invalidate();
          dismissConfirmation();
        }
      }
      @Override public void onNothingSelected(AdapterView<?> parent) {}
    });
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (!vm.input.contentEquals(s)) {
              vm.input = s.toString();
              vm.invalidate();
              dismissConfirmation();
            }
            input.setError(
                s.toString().codePointCount(0, s.length()) > 4000 ? "最多 4000 个字符" : null);
            view.findViewById(R.id.lookup_clear)
                .setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
          }

          public void afterTextChanged(Editable e) {}
        });
    view.findViewById(R.id.lookup_clear)
        .setVisibility(vm.input.isEmpty() ? View.GONE : View.VISIBLE);
    view.findViewById(R.id.lookup_clear)
        .setOnClickListener(
            v -> {
              input.setText("");
              input.requestFocus();
            });
    input.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
            submit();
            return true;
          }
          return false;
        });
    view.findViewById(R.id.lookup_submit).setOnClickListener(v -> submit());
    view.findViewById(R.id.lookup_service).setVisibility(graph().translationConfig == null ? View.GONE : View.VISIBLE);
    view.findViewById(R.id.lookup_service).setOnClickListener(v -> {
      dismissConfirmation();
      com.example.bilingualreader.ui.settings.TranslationSettingsDialog.show(
          requireContext(), graph().translationConfig);
    });
    view.findViewById(R.id.lookup_retry).setOnClickListener(v -> submit());
    view.findViewById(R.id.lookup_exit)
        .setOnClickListener(
            v -> {
              hideKeyboard();
              requireActivity().getOnBackPressedDispatcher().onBackPressed();
            });
    view.findViewById(R.id.lookup_cancel).setOnClickListener(v -> vm.stop());
    view.findViewById(R.id.lookup_copy)
        .setOnClickListener(
            v -> {
              UiState<String> result = vm.result.getValue();
              if (result != null && result.status == UiState.Status.SUCCESS) {
                ClipboardManager clipboard =
                    (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("译文", result.data));
                Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show();
              }
            });
    vm.result.observe(
        getViewLifecycleOwner(),
        value -> {
          boolean loading = value != null && value.status == UiState.Status.LOADING;
          boolean success = value != null && value.status == UiState.Status.SUCCESS;
          boolean error = value != null && value.status == UiState.Status.ERROR;
          view.findViewById(R.id.lookup_loading).setVisibility(loading ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.lookup_cancel).setVisibility(loading ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.lookup_output_panel)
              .setVisibility(success || error ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.lookup_copy).setVisibility(success ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.lookup_retry).setVisibility(error ? View.VISIBLE : View.GONE);
          ((TextView) view.findViewById(R.id.lookup_result))
              .setText(value == null ? "" : success ? value.data : value.message);
          boolean demo = vm.submitted != null && vm.submitted.binding.demo;
          TextView label = view.findViewById(R.id.lookup_result_label);
          label.setText("模拟译文，非真实翻译");
          label.setVisibility(success && demo ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.lookup_submit).setEnabled(!loading);
        });
  }

  private void dismissConfirmation() {
    if (confirmation != null) {
      confirmation.dismiss();
      confirmation = null;
    }
  }

  private void hideKeyboard() {
    if (input != null)
      ((InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(input.getWindowToken(), 0);
  }

  private void submit() {
    input.setError(null);
    try {
      LookupViewModel.Snapshot snapshot = vm.prepare();
      if (snapshot.binding.demo) start(snapshot);
      else {
        if (graph().translationConfig != null)
          graph()
              .translationConfig
              .validate(graph().translationConfig.mode(), graph().translationConfig.endpoint());
        String endpoint = snapshot.endpoint;
        dismissConfirmation();
        confirmation = new MaterialAlertDialogBuilder(requireContext())
            .setTitle("发送输入内容用于翻译？")
            .setMessage(
                "输入内容将发送到：\n" + endpoint + "\n\n方向：" + snapshot.label() + "。本次输入和译文仅临时保留，不保存查询历史或书籍译文。")
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "同意并查询",
                (d, w) -> {
                  if (isAdded() && getView() != null && vm.isCurrent(snapshot)) start(snapshot);
                })
            .show();
      }
    } catch (AppException e) {
      input.setError(e.getUserMessage());
    }
  }

  private void start(LookupViewModel.Snapshot snapshot) {
    ((InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(input.getWindowToken(), 0);
    input.clearFocus();
    vm.translate(snapshot);
  }

  @Override
  public void onResume() {
    super.onResume();
    requireActivity().findViewById(R.id.toolbar).setVisibility(View.GONE);
    if (vm.input.isEmpty()) input.requestFocus();
  }

  @Override
  public void onStop() {
    dismissConfirmation();
    requireActivity().findViewById(R.id.toolbar).setVisibility(View.VISIBLE);
    if (!requireActivity().isChangingConfigurations()) vm.stop();
    super.onStop();
  }

  @Override
  public void onDestroyView() {
    input = null;
    super.onDestroyView();
  }
}
