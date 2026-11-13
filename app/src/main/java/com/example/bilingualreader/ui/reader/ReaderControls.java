package com.example.bilingualreader.ui.reader;

import android.content.SharedPreferences;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

/** Overlay controls never resize the reading viewport. */
final class ReaderControls {
  private final ReaderFragment owner;
  private final View root;
  private final ReaderLocalStore store;
  private final SharedPreferences prefs;
  private final LinearLayout panel;
  private final MaterialToolbar toolbar;
  private boolean visible;
  private String page = "";
  private final OnBackPressedCallback back;

  ReaderControls(ReaderFragment owner, View root) {
    this.owner = owner;
    this.root = root;
    store = new ReaderLocalStore(owner.requireContext());
    prefs = store.preferences();
    panel = root.findViewById(R.id.reader_panel);
    toolbar = root.findViewById(R.id.reader_toolbar);
    toolbar.setNavigationIcon(R.drawable.reader_back);
    toolbar.setNavigationContentDescription("返回目录");
    toolbar.setNavigationOnClickListener(
        v -> owner.requireActivity().getOnBackPressedDispatcher().onBackPressed());
    toolbar.setTitleTextAppearance(
        owner.requireContext(), androidx.appcompat.R.style.TextAppearance_AppCompat_Small);
    toolbar
        .getMenu()
        .add(0, R.id.reader_favorite, 0, "收藏")
        .setIcon(R.drawable.reader_star)
        .setShowAsAction(2);
    toolbar
        .getMenu()
        .add(0, R.id.reader_bookmarks, 1, "书签")
        .setIcon(R.drawable.reader_bookmark)
        .setShowAsAction(2);
    toolbar
        .getMenu()
        .add(0, R.id.reader_translation, 2, "翻译本章")
        .setIcon(R.drawable.ic_translate)
        .setShowAsAction(2);
    toolbar.setOnMenuItemClickListener(
        item -> {
          if (item.getItemId() == R.id.reader_translation) owner.showTranslationActions();
          else if (item.getItemId() == R.id.reader_favorite) {
            store.favorite(owner.bookId(), !store.favorite(owner.bookId()));
            updateFavorite();
            Toast.makeText(
                    owner.requireContext(),
                    store.favorite(owner.bookId()) ? "已收藏本书" : "已取消收藏",
                    Toast.LENGTH_SHORT)
                .show();
          } else bookmarks();
          return true;
        });
    root.findViewById(R.id.reader_settings).setOnClickListener(v -> show("settings"));
    root.findViewById(R.id.reader_background).setOnClickListener(v -> show("background"));
    root.findViewById(R.id.reader_chapters).setOnClickListener(v -> show("chapters"));
    back =
        new OnBackPressedCallback(false) {
          public void handleOnBackPressed() {
            hide();
          }
        };
    owner
        .requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(owner.getViewLifecycleOwner(), back);
    // Back arrow always leaves the reader, whereas system Back first closes controls.
    toolbar.setNavigationOnClickListener(
        v -> {
          hide();
          owner.requireActivity().getOnBackPressedDispatcher().onBackPressed();
        });
    updateFavorite();
  }

  void title(String value) {
    toolbar.setTitle(value);
    ((TextView) root.findViewById(R.id.reader_chapter_label)).setText(value);
  }

  boolean isVisible() {
    return visible;
  }

  String page() {
    return page;
  }

  void toggle() {
    if (visible) hide();
    else show("");
  }

  void hide() {
    visible = false;
    page = "";
    root.findViewById(R.id.reader_book_name).setVisibility(View.VISIBLE);
    root.findViewById(R.id.reader_footer).setVisibility(View.VISIBLE);
    toolbar.setVisibility(View.GONE);
    root.findViewById(R.id.reader_bottom).setVisibility(View.GONE);
    back.setEnabled(false);
  }

  void show(String target) {
    page = target;
    root.findViewById(R.id.reader_book_name).setVisibility(View.INVISIBLE);
    root.findViewById(R.id.reader_footer).setVisibility(View.INVISIBLE);
    visible = true;
    toolbar.setVisibility(View.VISIBLE);
    root.findViewById(R.id.reader_bottom).setVisibility(View.VISIBLE);
    back.setEnabled(true);
    panel.removeAllViews();
    View scroll = (View) panel.getParent();
    ViewGroup.LayoutParams size = scroll.getLayoutParams();
    size.height =
        ("chapters".equals(page) || page.isEmpty())
            ? 0
            : Math.min(
                dp("settings".equals(page) ? 254 : 180),
                Math.max(dp(100), root.getHeight() - dp(160)));
    scroll.setLayoutParams(size);
    root.findViewById(R.id.chapter_switch)
        .setVisibility("chapters".equals(page) ? View.VISIBLE : View.GONE);
    if ("settings".equals(page)) settings();
    if ("background".equals(page)) background();
    for (int id : new int[] {R.id.reader_settings, R.id.reader_background, R.id.reader_chapters}) {
      boolean active =
          id == R.id.reader_settings && "settings".equals(page)
              || id == R.id.reader_background && "background".equals(page)
              || id == R.id.reader_chapters && "chapters".equals(page);
      root.findViewById(id).setAlpha(page.isEmpty() || active ? 1f : 0.55f);
      root.findViewById(id).setSelected(active);
    }
  }

  private void updateFavorite() {
    boolean selected = store.favorite(owner.bookId());
    MenuItem item = toolbar.getMenu().findItem(R.id.reader_favorite);
    item.setTitle(selected ? "已收藏，点击取消" : "收藏本书");
    android.graphics.drawable.Drawable icon =
        androidx.core.content.ContextCompat.getDrawable(
                owner.requireContext(), R.drawable.reader_star)
            .mutate();
    icon.setAlpha(selected ? 255 : 90);
    item.setIcon(icon);
  }

  private int dp(int value) {
    return Math.round(value * owner.getResources().getDisplayMetrics().density);
  }

  private TextView text(String label) {
    TextView t = new TextView(owner.requireContext());
    t.setText(label);
    t.setTextSize(14);
    t.setGravity(android.view.Gravity.CENTER);
    return t;
  }

  private void slider(
      String left, String right, int max, int initial, java.util.function.IntConsumer change) {
    LinearLayout row = new LinearLayout(owner.requireContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    TextView a = text(left), b = text(right);
    a.setMinWidth(dp(48));
    b.setMinWidth(dp(48));
    row.addView(a);
    SeekBar bar = new SeekBar(owner.requireContext());
    bar.setMax(max);
    bar.setProgress(initial);
    row.addView(bar, new LinearLayout.LayoutParams(0, dp(52), 1));
    row.addView(b);
    panel.addView(row);
    bar.setContentDescription(left + "调节");
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int p, boolean user) {
            if (user) change.accept(p);
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
  }

  private void settings() {
    TextView heading = text("正文排版 · 拖动即时生效");
    heading.setPadding(0, dp(14), 0, dp(8));
    panel.addView(heading);
    ReaderSettings current = owner.readerSettings();
    slider(
        "A−",
        "A+",
        16,
        current.getFontSizeSp() - 14,
        p -> owner.saveReaderSettings(14 + p, null, null));
    slider(
        "紧凑",
        "疏朗",
        8,
        Math.round((current.getLineSpacingMultiplier() - 1.2f) * 10),
        p -> owner.saveReaderSettings(null, 1.2f + p / 10f, null));
    slider(
        "窄边距",
        "宽边距",
        32,
        prefs.getInt("margin", 24) - 12,
        p -> {
          prefs.edit().putInt("margin", 12 + p).apply();
          owner.applyTypography();
        });
    slider(
        "细 A",
        "粗 A",
        1,
        prefs.getBoolean("bold", false) ? 1 : 0,
        p -> {
          prefs.edit().putBoolean("bold", p == 1).apply();
          owner.applyTypography();
        });
  }

  private void background() {
    LinearLayout row = new LinearLayout(owner.requireContext());
    row.setGravity(Gravity.CENTER);
    for (boolean night : new boolean[] {false, true}) {
      Button button = new Button(owner.requireContext());
      button.setText(
          ((owner.readerSettings().getTheme() == ReaderTheme.NIGHT) == night ? "✓ " : "")
              + (night ? "☾ 夜间方案" : "☼ 日间方案"));
      button.setSelected((owner.readerSettings().getTheme() == ReaderTheme.NIGHT) == night);
      row.addView(button, new LinearLayout.LayoutParams(0, dp(64), 1));
      button.setOnClickListener(v -> owner.saveReaderSettings(null, null, night));
    }
    panel.addView(row);
    androidx.appcompat.widget.SwitchCompat system =
        new androidx.appcompat.widget.SwitchCompat(owner.requireContext());
    slider(
        "暗",
        "亮",
        95,
        prefs.getInt("brightness", 50) - 5,
        p -> {
          prefs.edit().putInt("brightness", p + 5).putBoolean("systemBrightness", false).apply();
          system.setChecked(false);
          owner.applyBrightness();
        });
    system.setText("亮度跟随系统");
    system.setChecked(prefs.getBoolean("systemBrightness", true));
    panel.addView(system, new LinearLayout.LayoutParams(-1, dp(48)));
    system.setOnCheckedChangeListener(
        (v, on) -> {
          prefs.edit().putBoolean("systemBrightness", on).apply();
          owner.applyBrightness();
        });
  }

  private void bookmarks() {
    List<ReaderLocalStore.Bookmark> rows = store.bookmarks(owner.bookId());
    List<String> labels = new ArrayList<>();
    labels.add("＋ 添加当前位置书签");
    for (ReaderLocalStore.Bookmark row : rows) labels.add(row.title + "\n" + row.excerpt);
    new MaterialAlertDialogBuilder(owner.requireContext())
        .setTitle("本书书签")
        .setItems(
            labels.toArray(new String[0]),
            (d, index) -> {
              if (index == 0) {
                ReaderLocalStore.Bookmark mark = owner.currentBookmark();
                if (mark == null) {
                  Toast.makeText(owner.requireContext(), "正文加载后再添加书签", Toast.LENGTH_SHORT).show();
                  return;
                }
                rows.removeIf(
                    r -> r.chapter.equals(mark.chapter) && r.paragraph.equals(mark.paragraph));
                rows.add(0, mark);
                store.save(owner.bookId(), rows);
                Toast.makeText(owner.requireContext(), "书签已保存", Toast.LENGTH_SHORT).show();
              } else {
                ReaderLocalStore.Bookmark mark = rows.get(index - 1);
                new MaterialAlertDialogBuilder(owner.requireContext())
                    .setTitle(mark.title)
                    .setMessage(mark.excerpt)
                    .setPositiveButton(
                        "跳转",
                        (a, b) -> {
                          owner.jumpBookmark(mark);
                          hide();
                        })
                    .setNeutralButton(
                        "删除",
                        (a, b) -> {
                          rows.remove(mark);
                          store.save(owner.bookId(), rows);
                          bookmarks();
                        })
                    .setNegativeButton("取消", null)
                    .show();
              }
            })
        .setNegativeButton("关闭", null)
        .show();
  }
}
