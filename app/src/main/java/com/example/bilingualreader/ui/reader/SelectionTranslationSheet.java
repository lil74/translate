package com.example.bilingualreader.ui.reader;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.lifecycle.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.contract.model.Paragraph;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.viewmodel.*;
import com.google.android.material.bottomsheet.*;

public final class SelectionTranslationSheet extends BottomSheetDialogFragment {
  public static final String TAG = "selection_translation";
  private SelectionTranslationViewModel vm;

  public static SelectionTranslationSheet create(Paragraph paragraph, int start, int end) {
    if (start < 0 || end <= start || end > paragraph.getOriginalText().length())
      throw new IllegalArgumentException("Invalid selection");
    SelectionTranslationSheet sheet = new SelectionTranslationSheet();
    Bundle args = new Bundle();
    args.putString("selected", paragraph.getOriginalText().substring(start, end));
    sheet.setArguments(args);
    return sheet;
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
    return inflater.inflate(R.layout.sheet_selection_translation, parent, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    AppContainer graph = ((ReaderApplication) requireActivity().getApplication()).getContainer();
    vm =
        new ViewModelProvider(
                this,
                new ViewModelProvider.Factory() {
                  @NonNull
                  public <T extends ViewModel> T create(@NonNull Class<T> type) {
                    return type.cast(new SelectionTranslationViewModel(graph.selectionTranslation));
                  }
                })
            .get(SelectionTranslationViewModel.class);
    String selected = requireArguments().getString("selected", "");
    ((TextView) view.findViewById(R.id.selection_source)).setText(selected);
    ((TextView) view.findViewById(R.id.selection_notice))
        .setText(
            graph.selectionDemo
                ? R.string.selection_mock_notice
                : R.string.selection_online_notice);
    Button retry = view.findViewById(R.id.selection_retry);
    vm.result.observe(
        getViewLifecycleOwner(),
        value -> {
          boolean loading = value.status == UiState.Status.LOADING;
          view.findViewById(R.id.selection_spinner)
              .setVisibility(loading ? View.VISIBLE : View.GONE);
          TextView output = view.findViewById(R.id.selection_result);
          output.setText(
              loading
                  ? getString(R.string.selection_loading)
                  : value.status == UiState.Status.ERROR ? value.message : value.data);
          retry.setVisibility(value.status == UiState.Status.ERROR ? View.VISIBLE : View.GONE);
          retry.setText(R.string.selection_retry);
        });
    retry.setOnClickListener(v -> vm.translate(selected));
    view.findViewById(R.id.selection_close)
        .setOnClickListener(
            v -> {
              vm.cancel();
              dismiss();
            });
    if (vm.result.getValue() == null) {
      if (graph.selectionDemo) vm.translate(selected);
      else {
        ((TextView) view.findViewById(R.id.selection_result))
            .setText(R.string.selection_online_notice);
        retry.setText(R.string.selection_confirm);
      }
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
    if (dialog != null) {
      FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
      if (sheet != null) {
        sheet.getLayoutParams().height =
            Math.round(getResources().getDisplayMetrics().heightPixels * .7f);
        sheet.requestLayout();
        BottomSheetBehavior.from(sheet).setState(BottomSheetBehavior.STATE_EXPANDED);
      }
    }
  }

  @Override
  public void onCancel(@NonNull DialogInterface dialog) {
    if (vm != null) vm.cancel();
    super.onCancel(dialog);
  }
}
