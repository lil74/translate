package com.example.bilingualreader.ui.reader;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.recyclerview.widget.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.translation.TranslationConfigStore;
import com.example.bilingualreader.ui.*;
import com.example.bilingualreader.viewmodel.*;

public final class ReaderFragment extends BaseFragment {
  private ReaderViewModel vm;
  private RecyclerView list;
  private LinearLayoutManager layout;
  private ParagraphAdapter adapter;
  private boolean restorePending, restoring;
  private boolean restoreViewport;
  private String restoreId;
  private int restoreOffset;
  private ReaderSettings lastSettings;
  private PopupMenu translationMenu;
  private ReaderControls controls;
  private ReaderSettings desiredSettings;

  public ReaderFragment() {
    super(R.layout.fragment_reader);
  }

  public static ReaderFragment create(String book, String title, String chapter, String paragraph) {
    ReaderFragment f = new ReaderFragment();
    Bundle b = new Bundle();
    b.putString("book", book);
    b.putString("title", title);
    b.putString("chapter", chapter);
    b.putString("paragraph", paragraph);
    f.setArguments(b);
    return f;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    super.onViewCreated(view, state);
    vm = models().get(ReaderViewModel.class);
    commands(vm);
    list = view.findViewById(R.id.list);
    layout = new LinearLayoutManager(requireContext());
    adapter = new ParagraphAdapter();
    adapter.setOnSelectionTranslate(
        (paragraph, start, end, bounds) -> {
          androidx.fragment.app.FragmentManager manager = getChildFragmentManager();
          if (manager.isStateSaved()) return;
          androidx.fragment.app.Fragment previous =
              manager.findFragmentByTag(SelectionTranslationSheet.TAG);
          if (previous instanceof SelectionTranslationSheet)
            ((SelectionTranslationSheet) previous).dismissNow();
          SelectionTranslationSheet.create(paragraph, start, end, bounds)
              .showNow(manager, SelectionTranslationSheet.TAG);
        });
    adapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT);
    list.setLayoutManager(layout);
    list.setAdapter(adapter);
    controls = new ReaderControls(this, view);
    ((TextView) view.findViewById(R.id.reader_book_name))
        .setText(requireArguments().getString("title", "阅读"));
    list.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> updateReadingInfo());
    if (state != null && state.getBoolean("controlsVisible"))
      view.post(
          () -> {
            if (controls != null) controls.show(state.getString("controlsPage", ""));
          });
    applyTypography();
    GestureDetector taps =
        new GestureDetector(
            requireContext(),
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onSingleTapConfirmed(MotionEvent e) {
                if (list == null || !isResumed()) return false;
                View focus = list.findFocus();
                if (focus instanceof TextView && ((TextView) focus).hasSelection()) return false;
                if (getChildFragmentManager().findFragmentByTag(SelectionTranslationSheet.TAG)
                    != null) return false;
                if (e.getX() > list.getWidth() * .2f
                    && e.getX() < list.getWidth() * .8f
                    && e.getY() > list.getHeight() * .2f
                    && e.getY() < list.getHeight() * .8f) {
                  controls.toggle();
                  return true;
                }
                return false;
              }
            });
    list.addOnItemTouchListener(
        new RecyclerView.SimpleOnItemTouchListener() {
          @Override
          public boolean onInterceptTouchEvent(@NonNull RecyclerView r, @NonNull MotionEvent e) {
            taps.onTouchEvent(e);
            return false;
          }
        });
    vm.command.observe(getViewLifecycleOwner(), command -> refreshTranslationMenu());
    list.setItemAnimator(null);
    list.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrollStateChanged(@NonNull RecyclerView r, int state) {
            if (state == RecyclerView.SCROLL_STATE_DRAGGING) controls.hide();
          }

          @Override
          public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
            if (!restoring && !restorePending) remember();
            updateReadingInfo();
          }
        });
    vm.location.observe(
        getViewLifecycleOwner(),
        s -> {
          view.findViewById(R.id.loading)
              .setVisibility(s.status == UiState.Status.LOADING ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.error)
              .setVisibility(s.status == UiState.Status.ERROR ? View.VISIBLE : View.GONE);
          view.findViewById(R.id.retry_reading)
              .setVisibility(s.status == UiState.Status.ERROR ? View.VISIBLE : View.GONE);
          ((TextView) view.findViewById(R.id.error)).setText(s.message);
          updateChapterToolbar();
          updateReadingInfo();
          refreshTranslationMenu();
          if (s.data != null) {
            androidx.fragment.app.Fragment lookup =
                getChildFragmentManager().findFragmentByTag(SelectionTranslationSheet.TAG);
            if (lookup instanceof SelectionTranslationSheet
                && lookup.getArguments() != null
                && !s.data
                    .chapter
                    .getChapterId()
                    .equals(lookup.getArguments().getString("chapterId")))
              ((SelectionTranslationSheet) lookup).dismiss();

            adapter.submit(java.util.Collections.emptyList());
            ReadingProgress viewport = vm.getViewport(s.data.chapter.getChapterId());
            restoreId = viewport == null ? s.data.paragraphId : viewport.getParagraphId();
            restoreOffset =
                viewport == null ? s.data.offset : vm.getViewportOffset(list.getWidth());
            restorePending = true;
            restoreViewport = viewport != null;
          }
          view.findViewById(R.id.previous).setEnabled(vm.canMove(-1));
          view.findViewById(R.id.next).setEnabled(vm.canMove(1));
        });
    vm.paragraphs.observe(
        getViewLifecycleOwner(),
        content -> {
          boolean loading = content.status == UiState.Status.LOADING;
          view.findViewById(R.id.loading).setVisibility(loading ? View.VISIBLE : View.GONE);
          if (loading) adapter.submit(java.util.Collections.emptyList());
          if (content.data != null) {
            UiState<ReaderViewModel.Location> current = vm.location.getValue();
            if (current == null || current.data == null) return;
            for (ParagraphItem row : content.data)
              if (!row.getParagraph().getChapterId().equals(current.data.chapter.getChapterId()))
                return;
            adapter.submit(content.data);
            ((TextView) view.findViewById(R.id.error)).setText("本章节暂无可显示的正文，请选择其他章节。");
            view.findViewById(R.id.error)
                .setVisibility(content.data.isEmpty() ? View.VISIBLE : View.GONE);
            restore();
            list.post(this::updateReadingInfo);
          }
        });
    vm.settings.observe(
        getViewLifecycleOwner(),
        s -> {
          if (lastSettings != null
              && (s.getFontSizeSp() != lastSettings.getFontSizeSp()
                  || s.getLineSpacingMultiplier() != lastSettings.getLineSpacingMultiplier())) {
            restoreId = adapter.idAt(layout.findFirstVisibleItemPosition());
            restoreOffset = 0;
            restorePending = true;
            restoreViewport = false;
          }
          lastSettings = s;
          if (desiredSettings == null) desiredSettings = s;
          adapter.settings(s);
          restore();
        });
    renderTask(view, null);
    vm.task.observe(getViewLifecycleOwner(), t -> renderTask(view, t));
    view.findViewById(R.id.retry_reading).setOnClickListener(v -> vm.retryOpen());
    view.findViewById(R.id.previous)
        .setOnClickListener(
            v -> {
              remember();
              vm.move(-1);
            });
    view.findViewById(R.id.next)
        .setOnClickListener(
            v -> {
              remember();
              vm.move(1);
            });
    view.findViewById(R.id.toc)
        .setOnClickListener(
            v -> {
              remember();
              vm.flushProgress();
              if (!getParentFragmentManager().popBackStackImmediate())
                host()
                    .openToc(
                        requireArguments().getString("book"),
                        requireArguments().getString("title"));
            });
    vm.open(
        requireArguments().getString("book"),
        requireArguments().getString("chapter"),
        requireArguments().getString("paragraph"));
  }

  private void renderTask(View view, TranslationTask task) {
    adapter.running(isTranslationActive());
    refreshTranslationMenu();
  }

  private boolean isTranslationActive() {
    TranslationTask t = vm.task.getValue();
    return t != null && (t.getState() == TaskState.QUEUED || t.getState() == TaskState.RUNNING);
  }

  public String getTranslationAction() {
    TranslationTask t = vm.task.getValue();
    if (t == null) return getString(R.string.reader_translate_action);
    switch (t.getState()) {
      case COMPLETED:
        return getString(R.string.reader_translate_action);
      case CANCELLED:
        return "继续翻译";
      case FAILED:
      case PARTIAL_FAILED:
        return "重试未完成段落";
      default:
        return getString(R.string.reader_translate_action);
    }
  }

  public String getTranslationStatus() {
    TranslationTask t = vm.task.getValue();
    String label = "未翻译 · 原文可直接阅读";
    if (t != null) {
      switch (t.getState()) {
        case QUEUED:
          label = t.getErrorMessage() == null ? "等待网络或开始翻译" : t.getErrorMessage();
          break;
        case RUNNING:
          label = "正在翻译";
          break;
        case COMPLETED:
          label = "本章翻译完成";
          break;
        case CANCELLED:
          label = "已取消 · 已完成译文已保留";
          break;
        case FAILED:
        case PARTIAL_FAILED:
          label = t.getErrorMessage() == null ? "翻译未完成" : t.getErrorMessage();
          break;
      }
      label += " · " + t.getSuccessCount() + " / " + t.getTotalCount();
      if (t.getFailedCount() > 0) label += " · 失败 " + t.getFailedCount();
    }
    TranslationConfigStore config = graph().translationConfig;
    boolean demo =
        config == null ? graph().demo : TranslationConfigStore.MOCK.equals(config.mode());
    return demo ? "模拟验证 · " + label : label;
  }

  /** The compact toolbar icon opens text actions and the latest progress only on demand. */
  public PopupMenu showTranslationActions() {
    View anchor = requireActivity().findViewById(R.id.reader_translation);
    if (anchor == null) anchor = requireActivity().findViewById(R.id.toolbar);
    if (translationMenu != null) translationMenu.dismiss();
    PopupMenu popup = new PopupMenu(requireContext(), anchor);
    translationMenu = popup;
    populateTranslationMenu(popup.getMenu());
    popup.setOnMenuItemClickListener(
        item -> {
          if (item.getItemId() == R.id.translate) {
            remember();
            requestChapterTranslation();
            return true;
          }
          if (item.getItemId() == R.id.cancel) {
            vm.cancel();
            return true;
          }
          return false;
        });
    popup.setOnDismissListener(
        menu -> {
          if (translationMenu == menu) translationMenu = null;
        });
    popup.show();
    return popup;
  }

  private void requestChapterTranslation() {
    TranslationConfigStore config = graph().translationConfig;
    if (config == null && !graph().demo) {
      new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
          .setTitle("翻译当前章节")
          .setMessage("原文将发送到团队配置的在线翻译服务。是否继续？")
          .setNegativeButton("暂不翻译", null)
          .setPositiveButton("继续", (dialog, which) -> vm.translate())
          .show();
    } else if (config != null && config.needsConsent()) {
      String endpoint = config.endpoint();
      new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
          .setTitle("发送本章原文用于翻译？")
          .setMessage("本章英语原文会发送到：\n" + endpoint + "\n\n译文会保存在手机中。请确认你同意向该服务发送原文。")
          .setNegativeButton("取消", null)
          .setPositiveButton(
              "同意并翻译",
              (dialog, which) -> {
                if (TranslationConfigStore.ONLINE.equals(config.mode())
                    && endpoint.equals(config.endpoint())) {
                  config.consent(endpoint);
                  vm.translate();
                }
              })
          .show();
    } else vm.translate();
  }

  private void populateTranslationMenu(Menu menu) {
    menu.clear();
    menu.add(0, R.id.task_status, 0, getTranslationStatus()).setEnabled(false);
    UiState<ReaderViewModel.Location> location = vm.location.getValue();
    UiState<String> command = vm.command.getValue();
    boolean busy = command != null && command.status == UiState.Status.LOADING;
    menu.add(0, R.id.translate, 1, getTranslationAction())
        .setEnabled(location != null && location.data != null && !isTranslationActive() && !busy);
    if (isTranslationActive()) menu.add(0, R.id.cancel, 2, "取消任务").setEnabled(!busy);
  }

  private void refreshTranslationMenu() {
    if (translationMenu != null) populateTranslationMenu(translationMenu.getMenu());
  }

  private void updateChapterToolbar() {
    if (controls == null) return;
    UiState<ReaderViewModel.Location> state = vm.location.getValue();
    controls.title(
        state != null && state.data != null
            ? state.data.sectionTitle
            : getString(R.string.reader_loading_title));
  }

  private void restore() {
    if (!restorePending || adapter.getItemCount() == 0 || list == null) return;
    if (list.getWidth() == 0) {
      list.post(
          () -> {
            if (list != null) restore();
          });
      return;
    }
    restorePending = false;
    restoring = true;
    UiState<ReaderViewModel.Location> current = vm.location.getValue();
    if (restoreViewport
        && current != null
        && current.data != null
        && vm.getViewport(current.data.chapter.getChapterId()) != null)
      restoreOffset = vm.getViewportOffset(list.getWidth());
    layout.scrollToPositionWithOffset(adapter.indexOf(restoreId), restoreOffset);
    list.post(
        () -> {
          restoring = false;
          updateReadingInfo();
        });
  }

  private void updateReadingInfo() {
    if (list == null || getView() == null) return;
    UiState<ReaderViewModel.Location> state = vm.location.getValue();
    ((TextView) getView().findViewById(R.id.reader_footer_chapter))
        .setText(state != null && state.data != null ? state.data.sectionTitle : "");
    TextView progress = getView().findViewById(R.id.reader_footer_progress);
    int first = layout.findFirstVisibleItemPosition();
    View row = layout.findViewByPosition(first);
    if (state == null || state.data == null || adapter.getItemCount() == 0 || row == null) {
      progress.setText("—");
      return;
    }
    double hidden =
        (double) (list.getPaddingTop() - layout.getDecoratedTop(row))
            / Math.max(1, layout.getDecoratedMeasuredHeight(row));
    int percent = vm.readingPercent(first, hidden, !list.canScrollVertically(1));
    progress.setText(percent + "%");
    progress.setContentDescription("全书阅读进度 " + percent + "%");
  }

  private void remember() {
    if (list == null || restoring || restorePending) return;
    int first = layout.findFirstVisibleItemPosition();
    String id = adapter.idAt(first);
    View row = layout.findViewByPosition(first);
    if (id != null && row != null)
      vm.remember(id, layout.getDecoratedTop(row) - list.getPaddingTop(), list.getWidth());
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle state) {
    super.onSaveInstanceState(state);
    if (controls != null) {
      state.putBoolean("controlsVisible", controls.isVisible());
      state.putString("controlsPage", controls.page());
    }
  }

  @Override
  public void onPause() {
    remember();
    if (vm != null) vm.flushProgress();
    requireActivity().findViewById(R.id.toolbar).setVisibility(View.VISIBLE);
    WindowManager.LayoutParams attrs = requireActivity().getWindow().getAttributes();
    attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    requireActivity().getWindow().setAttributes(attrs);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    if (translationMenu != null) {
      translationMenu.dismiss();
      translationMenu = null;
    }
    controls = null;
    list = null;
    lastSettings = null;
    desiredSettings = null;
    super.onDestroyView();
  }

  @Override
  public void onResume() {
    super.onResume();
    requireActivity().findViewById(R.id.toolbar).setVisibility(View.GONE);
    applyBrightness();
    updateChapterToolbar();
  }

  String bookId() {
    return requireArguments().getString("book");
  }

  ReaderSettings readerSettings() {
    return desiredSettings != null
        ? desiredSettings
        : lastSettings != null ? lastSettings : ReaderSettings.defaults();
  }

  void saveReaderSettings(Integer font, Float spacing, Boolean night) {
    ReaderSettings old = readerSettings();
    desiredSettings =
        new ReaderSettings(
            font == null ? old.getFontSizeSp() : font,
            spacing == null ? old.getLineSpacingMultiplier() : spacing,
            night == null ? old.getTheme() : night ? ReaderTheme.NIGHT : ReaderTheme.DAY);
    ReaderSettings next = desiredSettings;
    com.example.bilingualreader.contract.repository.SettingsRepository settingsRepository =
        graph().settings;
    graph()
        .io
        .execute(
            () -> {
              try {
                settingsRepository.saveSettings(next);
              } catch (AppException e) {
                vm.command.postValue(UiState.error(e.getUserMessage()));
              }
            });
  }

  void applyTypography() {
    if (list == null) return;
    String anchor = adapter.idAt(layout.findFirstVisibleItemPosition());
    android.content.SharedPreferences prefs = new ReaderLocalStore(requireContext()).preferences();
    int margin =
        Math.round(prefs.getInt("margin", 24) * getResources().getDisplayMetrics().density);
    list.setPadding(margin, list.getPaddingTop(), margin, list.getPaddingBottom());
    if (getView() != null)
      for (int id : new int[] {R.id.reader_book_name, R.id.reader_footer}) {
        View label = getView().findViewById(id);
        label.setPadding(margin, label.getPaddingTop(), margin, label.getPaddingBottom());
      }
    adapter.bold(prefs.getBoolean("bold", false));
    if (anchor != null) layout.scrollToPositionWithOffset(adapter.indexOf(anchor), 0);
  }

  void applyBrightness() {
    android.content.SharedPreferences prefs = new ReaderLocalStore(requireContext()).preferences();
    WindowManager.LayoutParams attrs = requireActivity().getWindow().getAttributes();
    attrs.screenBrightness =
        prefs.getBoolean("systemBrightness", true)
            ? -1f
            : Math.max(.05f, prefs.getInt("brightness", 50) / 100f);
    requireActivity().getWindow().setAttributes(attrs);
  }

  ReaderLocalStore.Bookmark currentBookmark() {
    UiState<ReaderViewModel.Location> state = vm.location.getValue();
    int first = layout.findFirstVisibleItemPosition();
    String paragraph = adapter.idAt(first);
    if (state == null || state.data == null || paragraph == null) return null;
    View row = layout.findViewByPosition(first);
    TextView text = row == null ? null : row.findViewById(R.id.original);
    String excerpt = text == null ? "" : text.getText().toString();
    if (excerpt.length() > 90) excerpt = excerpt.substring(0, 90) + "…";
    return new ReaderLocalStore.Bookmark(
        state.data.chapter.getChapterId(),
        paragraph,
        state.data.sectionTitle,
        excerpt,
        row == null ? 0 : layout.getDecoratedTop(row) - list.getPaddingTop());
  }

  void jumpBookmark(ReaderLocalStore.Bookmark mark) {
    remember();
    if (!vm.jumpToBookmark(mark.chapter, mark.paragraph, mark.offset))
      Toast.makeText(requireContext(), "书签对应章节已不存在", Toast.LENGTH_SHORT).show();
  }
}
