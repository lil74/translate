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
  private LookupHistory history;

  public LookupFragment() {
    super(R.layout.fragment_lookup);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    vm = models().get(LookupViewModel.class);
    input = view.findViewById(R.id.lookup_input);
    input.setText(vm.input);
    history = new LookupHistory(requireContext());
    vm.direction = com.example.bilingualreader.translation.LookupQuery.AUTO;
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (!vm.input.contentEquals(s)) {
              vm.input = s.toString();
              vm.invalidate();
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
          if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
              || (event != null
                  && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                  && event.getAction() == KeyEvent.ACTION_DOWN)) {
            submit();
            return true;
          }
          return false;
        });
    view.findViewById(R.id.lookup_submit).setOnClickListener(v -> submit());
    view.findViewById(R.id.lookup_retry).setOnClickListener(v -> submit());
    view.findViewById(R.id.lookup_exit)
        .setOnClickListener(
            v -> {
              hideKeyboard();
              requireActivity().getOnBackPressedDispatcher().onBackPressed();
            });
    view.findViewById(R.id.lookup_cancel).setOnClickListener(v -> vm.stop());
    view.findViewById(R.id.lookup_history_clear)
        .setOnClickListener(
            v -> {
              history.clear();
              renderHistory(view);
            });
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
    renderHistory(view);
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
          boolean demo = vm.submitted == null ? vm.cachedDemo : vm.submitted.binding.demo;
          TextView label = view.findViewById(R.id.lookup_result_label);
          label.setText("模拟译文");
          label.setVisibility(success && demo ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.lookup_submit).setEnabled(!loading);
          if (success
              && vm.submitted != null
              && !vm.submitted.request.getRequestId().equals(vm.recordedRequestId)) {
            vm.recordedRequestId = vm.submitted.request.getRequestId();
            history.record(vm.submitted.request.getItems().get(0).getText(), value.data, demo);
            renderHistory(view);
          }
        });
  }

  private void renderHistory(View view) {
    LinearLayout list = view.findViewById(R.id.lookup_history);
    list.removeAllViews();
    java.util.List<LookupHistory.Entry> rows = history.rows();
    view.findViewById(R.id.lookup_history_clear).setEnabled(!rows.isEmpty());
    view.findViewById(R.id.lookup_history_clear).setAlpha(rows.isEmpty() ? .35f : 1f);
    for (LookupHistory.Entry entry : rows) {
      LinearLayout row = new LinearLayout(requireContext());
      row.setGravity(Gravity.CENTER_VERTICAL);
      int height = Math.round(64 * getResources().getDisplayMetrics().density);
      list.addView(row, new LinearLayout.LayoutParams(-1, height));
      TextView word = new TextView(requireContext()), meaning = new TextView(requireContext());
      word.setText(entry.query);
      word.setTextSize(16);
      word.setTypeface(null, android.graphics.Typeface.BOLD);
      meaning.setText((entry.demo ? "模拟 · " : "") + entry.translated);
      meaning.setTextSize(14);
      meaning.setTextColor(
          androidx.core.content.ContextCompat.getColor(
              requireContext(), com.example.bilingualreader.R.color.muted));
      for (TextView t : new TextView[] {word, meaning}) {
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
      }
      word.setPadding(0, 0, Math.round(12 * getResources().getDisplayMetrics().density), 0);
      row.addView(word, new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(meaning, new LinearLayout.LayoutParams(0, -2, 2));
      android.util.TypedValue ripple = new android.util.TypedValue();
      requireContext()
          .getTheme()
          .resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
      row.setBackgroundResource(ripple.resourceId);
      row.setFocusable(true);
      row.setContentDescription(entry.query + "，" + (entry.demo ? "模拟译文，" : "") + entry.translated);
      row.setOnClickListener(
          v -> {
            input.setText(entry.query);
            input.setSelection(input.length());
            vm.restoreCached(entry.query, entry.translated, entry.demo);
            hideKeyboard();
          });
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
        String endpoint =
            graph().translationConfig == null
                ? "当前配置的在线翻译服务"
                : graph().translationConfig.endpoint();
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("发送输入内容用于翻译？")
            .setMessage(
                "输入内容将发送到：\n" + endpoint + "\n\n方向：" + snapshot.label() + "。本次查询不会保存到书籍译文中。")
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "同意并查询",
                (d, w) -> {
                  if (isAdded() && getView() != null) start(snapshot);
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
