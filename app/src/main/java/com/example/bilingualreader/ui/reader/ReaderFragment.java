package com.example.bilingualreader.ui.reader;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.*;
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
        (paragraph, start, end) -> {
          androidx.fragment.app.FragmentManager manager = getChildFragmentManager();
          if (!manager.isStateSaved()
              && manager.findFragmentByTag(SelectionTranslationSheet.TAG) == null)
            SelectionTranslationSheet.create(paragraph, start, end)
                .showNow(manager, SelectionTranslationSheet.TAG);
        });
    adapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT);
    list.setLayoutManager(layout);
    list.setAdapter(adapter);
    vm.command.observe(
        getViewLifecycleOwner(),
        command -> {
          if (command != null && command.status == UiState.Status.LOADING)
            view.findViewById(R.id.translate).setEnabled(false);
          else renderTask(view, vm.task.getValue());
        });
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
          view.findViewById(R.id.translate).setEnabled(s.data != null);
          if (s.data != null) {
            ((TextView) view.findViewById(R.id.chapter_title)).setText(s.data.chapter.getTitle());
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
    view.findViewById(R.id.translate)
        .setOnClickListener(
            v -> {
              if (graph().demo) vm.translate();
              else
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("翻译当前章节")
                    .setMessage("原文将发送到团队配置的在线翻译服务。是否继续？")
                    .setNegativeButton("暂不翻译", null)
                    .setPositiveButton("继续", (dialog, which) -> vm.translate())
                    .show();
            });
    view.findViewById(R.id.cancel).setOnClickListener(v -> vm.cancel());
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

  private void renderTask(View view, TranslationTask t) {
    boolean active =
        t != null && (t.getState() == TaskState.RUNNING || t.getState() == TaskState.QUEUED);
    String label = "未翻译 · 原文可直接阅读";
    String button = "翻译本章";
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
          button = "使用已有译文";
          break;
        case CANCELLED:
          label = "已取消 · 已完成译文已保留";
          button = "继续翻译";
          break;
        case FAILED:
        case PARTIAL_FAILED:
          label = t.getErrorMessage() == null ? "翻译未完成" : t.getErrorMessage();
          button = "重试未完成段落";
          break;
      }
      label += " · " + t.getSuccessCount() + " / " + t.getTotalCount();
    }
    if (graph().demo) label = "模拟演示 · " + label;
    ((TextView) view.findViewById(R.id.task_status)).setText(label);
    ProgressBar bar = view.findViewById(R.id.progress);
    bar.setProgress(
        t == null
            ? 0
            : t.getTotalCount() == 0
                ? 100
                : Math.round(t.getSuccessCount() * 100f / t.getTotalCount()));
    if (t != null) label += " · 失败 " + t.getFailedCount();
    ((TextView) view.findViewById(R.id.task_status)).setText(label);
    ((Button) view.findViewById(R.id.translate)).setText(button);
    view.findViewById(R.id.translate)
        .setEnabled(
            !active && vm.location.getValue() != null && vm.location.getValue().data != null);
    view.findViewById(R.id.cancel).setVisibility(active ? View.VISIBLE : View.GONE);
    adapter.running(active);
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
    list = null;
    lastSettings = null;
    super.onDestroyView();
  }

  @Override
  public void onResume() {
    super.onResume();
    host().page("双语阅读", true);
  }
}
