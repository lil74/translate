package com.example.bilingualreader.ui.reader;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.lifecycle.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.contract.model.Paragraph;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.viewmodel.*;

/** In-app floating lookup window; keeps the reader bright and touchable outside its bounds. */
public final class SelectionTranslationSheet extends AppCompatDialogFragment {
  public static final String TAG = "selection_translation";
  private SelectionTranslationViewModel vm;
  private float touchX, touchY;
  private int windowX, windowY;
  private boolean moved;
  private boolean userPositioned;

  @Override
  public void onCreate(@Nullable Bundle state) {
    super.onCreate(state);
    setStyle(STYLE_NO_TITLE, R.style.Theme_BilingualReader_SelectionFloating);
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(@Nullable Bundle state) {
    Dialog dialog = super.onCreateDialog(state);
    dialog.setCanceledOnTouchOutside(false);
    configureWindow(dialog.getWindow());
    return dialog;
  }

  private void configureWindow(@Nullable Window window) {
    if (window == null) return;
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
    window.setDimAmount(0f);
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
  }

  public static SelectionTranslationSheet create(Paragraph paragraph, int start, int end) {
    if (start < 0 || end <= start || end > paragraph.getOriginalText().length())
      throw new IllegalArgumentException("Invalid selection");
    SelectionTranslationSheet sheet = new SelectionTranslationSheet();
    Bundle args = new Bundle();
    args.putString("selected", paragraph.getOriginalText().substring(start, end));
    args.putString("chapterId", paragraph.getChapterId());
    sheet.setArguments(args);
    return sheet;
  }

  public static SelectionTranslationSheet create(
      Paragraph paragraph, int start, int end, Rect bounds) {
    SelectionTranslationSheet popup = create(paragraph, start, end);
    popup.requireArguments().putInt("anchorTop", bounds.top);
    popup.requireArguments().putInt("anchorBottom", bounds.bottom);
    return popup;
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
    if (!requireArguments().containsKey("anchorScreenWidth")) {
      requireArguments()
          .putInt("anchorScreenWidth", getResources().getDisplayMetrics().widthPixels);
      requireArguments()
          .putInt("anchorScreenHeight", getResources().getDisplayMetrics().heightPixels);
    }
    bindDragHandle(view);
    view.addOnLayoutChangeListener(
        (v, l, t, r, b, oldL, oldT, oldR, oldB) -> v.post(this::positionWindow));
    AppContainer graph = ((ReaderApplication) requireActivity().getApplication()).getContainer();
    vm =
        new ViewModelProvider(
                this,
                new ViewModelProvider.Factory() {
                  @NonNull
                  public <T extends ViewModel> T create(@NonNull Class<T> type) {
                    AppContainer.SelectionBinding binding = graph.selectionBinding();
                    return type.cast(new SelectionTranslationViewModel(binding.service, binding.demo));
                  }
                })
            .get(SelectionTranslationViewModel.class);
    String selected = requireArguments().getString("selected", "");
    ((TextView) view.findViewById(R.id.selection_source)).setText(selected);
    ((TextView) view.findViewById(R.id.selection_notice))
        .setText(
            vm.demo
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
      if (vm.demo) vm.translate(selected);
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
    Dialog dialog = getDialog();
    if (dialog == null || dialog.getWindow() == null) return;
    Window window = dialog.getWindow();
    configureWindow(window);
    Rect frame = new Rect();
    requireActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
    int screenWidth = frame.width();
    int width = Math.min(dp(340), Math.max(dp(120), screenWidth - dp(32)));
    window.setGravity(Gravity.TOP | Gravity.LEFT);
    window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    View toolbar = requireActivity().findViewById(R.id.toolbar);
    WindowManager.LayoutParams attrs = window.getAttributes();
    attrs.x = Math.max(0, (screenWidth - width) / 2);
    attrs.y = (toolbar == null ? dp(56) : toolbar.getHeight()) + dp(8);
    window.setAttributes(attrs);
    window.getDecorView().post(this::positionWindow);
  }

  private void positionWindow() {
    Dialog dialog = getDialog();
    if (userPositioned || dialog == null || dialog.getWindow() == null || !isAdded()) return;
    Window window = dialog.getWindow();
    int height = window.getDecorView().getHeight();
    if (height == 0) return;
    Rect frame = new Rect();
    requireActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
    View toolbar = requireActivity().findViewById(R.id.toolbar);
    int minY = (toolbar == null ? dp(56) : toolbar.getHeight()) + dp(8);
    int maxY = Math.max(minY, frame.height() - height - dp(8));
    int top = requireArguments().getInt("anchorTop", -1) - frame.top;
    int bottom = requireArguments().getInt("anchorBottom", -1) - frame.top;
    if (requireArguments().getInt("anchorScreenWidth")
            != getResources().getDisplayMetrics().widthPixels
        || requireArguments().getInt("anchorScreenHeight")
            != getResources().getDisplayMetrics().heightPixels) {
      top = -1;
      bottom = -1;
    }
    int y = minY;
    if (top >= 0 && bottom >= top) {
      int above = top - height - dp(8), below = bottom + dp(8);
      if (above >= minY) y = Math.min(above, maxY);
      else if (below <= maxY) y = Math.max(minY, below);
      else y = top > frame.height() / 2 ? minY : maxY;
    }
    WindowManager.LayoutParams attrs = window.getAttributes();
    if (attrs.y != y) {
      attrs.y = y;
      window.setAttributes(attrs);
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void bindDragHandle(View content) {
    View handle = content.findViewById(R.id.selection_drag);
    handle.setOnTouchListener(
        (v, event) -> {
          Dialog dialog = getDialog();
          if (dialog == null || dialog.getWindow() == null) return false;
          Window window = dialog.getWindow();
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              touchX = event.getRawX();
              touchY = event.getRawY();
              windowX = window.getAttributes().x;
              windowY = window.getAttributes().y;
              moved = false;
              return true;
            case MotionEvent.ACTION_MOVE:
              float dx = event.getRawX() - touchX, dy = event.getRawY() - touchY;
              if (!moved
                  && Math.hypot(dx, dy)
                      < ViewConfiguration.get(requireContext()).getScaledTouchSlop()) return true;
              moved = true;
              userPositioned = true;
              WindowManager.LayoutParams attrs = window.getAttributes();
              int screenWidth = getResources().getDisplayMetrics().widthPixels;
              int screenHeight = getResources().getDisplayMetrics().heightPixels;
              attrs.x = Math.max(0, Math.min(windowX + Math.round(dx), screenWidth - attrs.width));
              attrs.y =
                  Math.max(
                      0,
                      Math.min(
                          windowY + Math.round(dy),
                          Math.max(0, screenHeight - window.getDecorView().getHeight() - dp(48))));
              window.setAttributes(attrs);
              return true;
            case MotionEvent.ACTION_UP:
              if (!moved) v.performClick();
              return true;
            case MotionEvent.ACTION_CANCEL:
              return true;
            default:
              return false;
          }
        });
  }

  @Override
  public void onCancel(@NonNull DialogInterface dialog) {
    if (vm != null) vm.cancel();
    super.onCancel(dialog);
  }
}
