package com.example.bilingualreader.ui.reader;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.*;
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
    requireActivity()
        .addMenuProvider(
            new MenuProvider() {
              @Override
              public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                menu.add(0, R.id.reader_translation, 0, R.string.reader_translate_action)
                    .setIcon(R.drawable.ic_translate)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
              }

              @Override
              public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() != R.id.reader_translation) return false;
                showTranslationActions();
                return true;
              }
            },
            getViewLifecycleOwner(),
            Lifecycle.State.RESUMED);
    vm.command.observe(getViewLifecycleOwner(), command -> refreshTranslationMenu());
    list.setItemAnimator(null);
    list.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
            if (!restoring && !restorePending) remember();
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
        return "使用已有译文";
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
          label = "等待翻译";
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
    return graph().demo ? "模拟演示 · " + label : label;
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
            if (graph().demo) vm.translate();
            else
              new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                  .setTitle("翻译当前章节")
                  .setMessage("原文将发送到团队配置的在线翻译服务。是否继续？")
                  .setNegativeButton("暂不翻译", null)
                  .setPositiveButton("继续", (dialog, which) -> vm.translate())
                  .show();
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
    if (!isResumed()) return;
    UiState<ReaderViewModel.Location> state = vm.location.getValue();
    host()
        .page(
            state != null && state.data != null
                ? state.data.sectionTitle
                : getString(R.string.reader_loading_title),
            true);
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
    list.post(() -> restoring = false);
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
  public void onPause() {
    remember();
    if (vm != null) vm.flushProgress();
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    if (translationMenu != null) {
      translationMenu.dismiss();
      translationMenu = null;
    }
    list = null;
    lastSettings = null;
    super.onDestroyView();
  }

  @Override
  public void onResume() {
    super.onResume();
    updateChapterToolbar();
  }
}
