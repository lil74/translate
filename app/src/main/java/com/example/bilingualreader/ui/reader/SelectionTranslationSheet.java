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
import com.example.bilingualreader.contract.translation.TranslationInput;
import com.example.bilingualreader.translation.SelectionContext;
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
  private EnglishPronunciation pronunciation;
  private boolean originKnown;
  private int coordinateOriginX,coordinateOriginY;

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
    window.setWindowAnimations(0);
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
  }

  public static SelectionTranslationSheet create(Paragraph paragraph, int start, int end) {
    if (start < 0 || end <= start || end > paragraph.getOriginalText().length())
      throw new IllegalArgumentException("Invalid selection");
    SelectionTranslationSheet sheet = new SelectionTranslationSheet();
    Bundle args = new Bundle();
    args.putString("selected", paragraph.getOriginalText().substring(start, end));
    args.putString("chapterId", paragraph.getChapterId());
    if(paragraph.getOriginalText().codePointCount(start,end)<=SelectionContext.MAX_POINTS) {
      TranslationInput.Context context=SelectionContext.fromParagraph(paragraph.getOriginalText(),start,end);
      args.putString("contextText",context.getText());args.putInt("contextStart",context.getStart());args.putInt("contextEnd",context.getEnd());
    }
    sheet.setArguments(args);
    return sheet;
  }

  public static SelectionTranslationSheet create(
      Paragraph paragraph, int start, int end, Rect bounds) {
    SelectionTranslationSheet popup = create(paragraph, start, end);
    popup.requireArguments().putInt("anchorTop", bounds.top);
    popup.requireArguments().putInt("anchorBottom", bounds.bottom);
    popup.requireArguments().putInt("anchorLeft",bounds.left);
    popup.requireArguments().putInt("anchorRight",bounds.right);
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
                    return type.cast(
                        new SelectionTranslationViewModel(binding.service, binding.demo));
                  }
                })
            .get(SelectionTranslationViewModel.class);
    String selected = requireArguments().getString("selected", "");
    TranslationInput.Context context=requireArguments().containsKey("contextText")
        ?new TranslationInput.Context(requireArguments().getString("contextText"),
            requireArguments().getInt("contextStart"),requireArguments().getInt("contextEnd")):null;
    ((TextView) view.findViewById(R.id.selection_source)).setText(selected);
    ImageButton speak=view.findViewById(R.id.selection_pronounce);
    TextView voiceStatus=view.findViewById(R.id.selection_voice_status);
    View voiceSettings=view.findViewById(R.id.selection_voice_settings);
    pronunciation=new EnglishPronunciation(requireContext(),voiceState->{
      boolean playing=voiceState==EnglishPronunciation.State.SPEAKING;
      speak.setImageResource(playing?R.drawable.ic_stop_pronounce:R.drawable.ic_pronounce);
      speak.setContentDescription(getString(playing?R.string.selection_stop_pronounce:R.string.selection_pronounce));
      int message=voiceState==EnglishPronunciation.State.PREPARING?R.string.selection_voice_preparing
          :voiceState==EnglishPronunciation.State.UNAVAILABLE?R.string.selection_voice_missing
          :voiceState==EnglishPronunciation.State.ERROR?R.string.selection_voice_error:0;
      voiceStatus.setVisibility(message==0?View.GONE:View.VISIBLE);
      if(message!=0)voiceStatus.setText(message);
      voiceSettings.setVisibility(voiceState==EnglishPronunciation.State.UNAVAILABLE?View.VISIBLE:View.GONE);
    });
    speak.setOnClickListener(v->pronunciation.play(selected));
    voiceSettings.setOnClickListener(v->{
      android.content.Intent install=new android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
      String engine=pronunciation.enginePackage();if(engine!=null)install.setPackage(engine);
      try {startActivity(install);}catch(android.content.ActivityNotFoundException unavailable) {
        Toast.makeText(requireContext(),R.string.selection_voice_settings_missing,Toast.LENGTH_LONG).show();
      }
    });
    ((TextView) view.findViewById(R.id.selection_notice))
        .setText(
            vm.demo
                ? R.string.selection_mock_notice
                : context==null ? R.string.selection_online_notice : R.string.selection_context_notice);
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
    retry.setOnClickListener(v -> vm.translate(selected,context));
    view.findViewById(R.id.selection_close)
        .setOnClickListener(
            v -> {
              vm.cancel();
              dismiss();
            });
    if (vm.result.getValue() == null) {
      if (vm.demo) vm.translate(selected,context);
      else {
        ((TextView) view.findViewById(R.id.selection_result))
            .setText(context==null?R.string.selection_online_notice:R.string.selection_context_consent);
        retry.setText(context==null?R.string.selection_confirm:R.string.selection_context_confirm);
      }
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    originKnown=false;
    Dialog dialog = getDialog();
    if (dialog == null || dialog.getWindow() == null) return;
    Window window = dialog.getWindow();
    configureWindow(window);
    Rect frame = new Rect();
    requireActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
    int screenWidth = frame.width();
    int width = Math.min(dp(300), Math.max(dp(120), screenWidth - dp(32)));
    window.setGravity(Gravity.TOP | Gravity.LEFT);
    window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    View toolbar = requireActivity().findViewById(R.id.reader_toolbar);
    WindowManager.LayoutParams attrs = window.getAttributes();
    attrs.x = 0;
    attrs.y = (toolbar == null ? dp(56) : toolbar.getHeight()) + dp(8);
    window.setAttributes(attrs);
    window.getDecorView().post(this::positionWindow);
  }

  private void positionWindow() {
    Dialog dialog = getDialog();
    if (dialog == null || dialog.getWindow() == null || !isAdded() || getView()==null) return;
    Window window = dialog.getWindow();
    int height = window.getDecorView().getHeight();
    if (height == 0) return;
    Rect frame = new Rect();
    requireActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
    View reading=requireActivity().findViewById(R.id.list);
    Rect content=new Rect();
    if(reading!=null && reading.getGlobalVisibleRect(content))frame.intersect(content);
    frame.inset(dp(8),dp(8));
    if(frame.width()<=0 || frame.height()<=0)return;
    if(window.getDecorView().getWidth()>frame.width()) {
      window.setLayout(frame.width(),WindowManager.LayoutParams.WRAP_CONTENT);return;
    }
    View scroll=requireView().findViewById(R.id.selection_scroll);
    if(scroll instanceof SelectionResultScrollView) {
      int fixedHeight=requireView().getHeight()-scroll.getHeight();
      ((SelectionResultScrollView)scroll).setMaximumHeight(Math.max(dp(24),frame.height()-fixedHeight));
    }
    int top = requireArguments().getInt("anchorTop", -1);
    int bottom = requireArguments().getInt("anchorBottom", -1);
    int left=requireArguments().getInt("anchorLeft",frame.centerX()-1);
    int right=requireArguments().getInt("anchorRight",frame.centerX()+1);
    if (requireArguments().getInt("anchorScreenWidth")
            != getResources().getDisplayMetrics().widthPixels
        || requireArguments().getInt("anchorScreenHeight")
            != getResources().getDisplayMetrics().heightPixels) {
      top = -1;
      bottom = -1;
    }
    WindowManager.LayoutParams attrs = window.getAttributes();
    int[] actual=new int[2];window.getDecorView().getLocationOnScreen(actual);
    if(!originKnown) {
      coordinateOriginX=actual[0]-attrs.x;coordinateOriginY=actual[1]-attrs.y;originKnown=true;
    }
    int[] desired=userPositioned
        ?new int[]{Math.max(frame.left,Math.min(actual[0],frame.right-window.getDecorView().getWidth())),
            Math.max(frame.top,Math.min(actual[1],frame.bottom-height))}
        :SelectionPopupPlacement.locate(frame.left,frame.top,frame.right,frame.bottom,left,top,right,bottom,
            window.getDecorView().getWidth(),height,dp(8));
    int x=desired[0]-coordinateOriginX,y=desired[1]-coordinateOriginY;
    if (attrs.y != y || attrs.x!=x) {
      attrs.y = y;attrs.x=x;
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
  public void onStop() {
    if(pronunciation!=null)pronunciation.stop();
    super.onStop();
  }

  @Override
  public void onDestroyView() {
    if(pronunciation!=null){pronunciation.close();pronunciation=null;}
    super.onDestroyView();
  }

  @Override
  public void onCancel(@NonNull DialogInterface dialog) {
    if (vm != null) vm.cancel();
    super.onCancel(dialog);
  }
}
