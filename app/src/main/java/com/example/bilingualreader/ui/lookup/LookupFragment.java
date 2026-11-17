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
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Future;

public final class LookupFragment extends BaseFragment {
  private LookupViewModel vm;
  private EditText input;
  private final Handler suggestionsHandler=new Handler(Looper.getMainLooper());
  private Future<?> suggestionsTask;
  private long suggestionsGeneration;

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
          suggest();
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
              suggest();
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
              .setText(value == null ? "" : success ? DictionaryText.format(DictionaryText.chineseOnly(value.data)) : value.message);
          boolean demo = vm.submitted != null && vm.submitted.binding.demo;
          TextView label = view.findViewById(R.id.lookup_result_label);
          boolean dictionary=vm.submitted!=null && vm.submitted.request.getPurpose()==com.example.bilingualreader.contract.translation.TranslationBatchRequest.Purpose.DICTIONARY;
          label.setText(demo?"模拟译文，非真实翻译":"词典释义 · 按来源词性分组");
          label.setVisibility(success && (demo || dictionary) ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.lookup_submit).setEnabled(!loading);
        });
    suggest();
  }

  private void dismissConfirmation() {
    // Queries start directly when explicitly submitted; no second confirmation dialog.
  }

  private boolean dictionaryInput(){
    return vm.direction!=com.example.bilingualreader.translation.LookupQuery.ZH_TO_EN
        && vm.input.trim().matches("[A-Za-z]+(?:[ '-][A-Za-z]*)*") && vm.input.trim().length()<=100;
  }

  private void suggest(){
    suggestionsHandler.removeCallbacksAndMessages(null);
    if(suggestionsTask!=null)suggestionsTask.cancel(true);
    long generation=++suggestionsGeneration;
    View view=getView();if(view==null)return;
    LinearLayout list=view.findViewById(R.id.lookup_suggestions);list.removeAllViews();
    TextView status=view.findViewById(R.id.lookup_suggestion_status);
    status.setVisibility(View.GONE);
    if(!dictionaryInput())return;
    status.setText("正在查找候选词…");status.setVisibility(View.VISIBLE);
    suggestionsHandler.postDelayed(()->{
      if(getView()!=view)return;
      suggestionsTask=LocalDictionary.search(requireContext(),vm.input,entries->{
        if(getView()!=view || generation!=suggestionsGeneration)return;
        status.setText(entries.isEmpty()?"未找到前缀匹配，点击搜索可查询当前输入":"点击单词查看释义");
        for(LocalDictionary.Entry entry:entries){
          LinearLayout row=new LinearLayout(requireContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,16,0,16);
          TextView word=new TextView(requireContext());word.setText(entry.word);word.setTextSize(18);word.setTypeface(null,android.graphics.Typeface.BOLD);word.setTextColor(getResources().getColor(R.color.ink,null));
          word.setMaxWidth((int)(getResources().getDisplayMetrics().widthPixels*.45));
          TextView meaning=new TextView(requireContext());meaning.setText(entry.meaning.replace('\n',' '));meaning.setTextSize(15);meaning.setTextColor(getResources().getColor(R.color.muted,null));meaning.setMaxLines(2);meaning.setEllipsize(android.text.TextUtils.TruncateAt.END);meaning.setPadding(16,0,0,0);
          row.addView(word,new LinearLayout.LayoutParams(-2,-2));row.addView(meaning,new LinearLayout.LayoutParams(0,-2,1));
          row.setMinimumHeight((int)(64*getResources().getDisplayMetrics().density));row.setBackgroundResource(android.R.drawable.list_selector_background);
          row.setFocusable(true);row.setContentDescription(entry.word+"，"+entry.meaning);row.setOnClickListener(v->{hideKeyboard();host().openWord(entry.word);});list.addView(row);
        }
      },message->{if(getView()==view && generation==suggestionsGeneration)status.setText(message);});
    },180);
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
      if(snapshot.request.getPurpose()==com.example.bilingualreader.contract.translation.TranslationBatchRequest.Purpose.DICTIONARY) {
        hideKeyboard();host().openWord(vm.input.trim());return;
      }
      if (!snapshot.binding.demo) {
        if (graph().translationConfig != null)
          graph()
              .translationConfig
              .validate(graph().translationConfig.mode(), graph().translationConfig.endpoint());
      }
      start(snapshot);
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
    suggestionsGeneration++;
    suggestionsHandler.removeCallbacksAndMessages(null);
    if(suggestionsTask!=null)suggestionsTask.cancel(true);
    input = null;
    super.onDestroyView();
  }
}
