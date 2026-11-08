package com.example.bilingualreader.ui.reader;

import android.graphics.Typeface;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.*;
import java.util.*;

public final class ParagraphAdapter extends RecyclerView.Adapter<ParagraphAdapter.Holder> {
  private List<ParagraphItem> items = Collections.emptyList();
  private ReaderSettings settings = ReaderSettings.defaults();
  private boolean running;

  public interface OnSelectionTranslate {
    void translate(Paragraph paragraph, int start, int end);
  }

  private OnSelectionTranslate selection;

  public void setOnSelectionTranslate(OnSelectionTranslate selection) {
    this.selection = selection;
  }

  public void submit(List<ParagraphItem> rows) {
    items = new ArrayList<>(rows);
    notifyDataSetChanged();
  }

  public void settings(ReaderSettings value) {
    settings = value;
    notifyDataSetChanged();
  }

  public void running(boolean value) {
    if (value != running) {
      running = value;
      notifyDataSetChanged();
    }
  }

  public int indexOf(String id) {
    for (int i = 0; i < items.size(); i++)
      if (items.get(i).getParagraph().getParagraphId().equals(id)) return i;
    return 0;
  }

  public String idAt(int index) {
    return index >= 0 && index < items.size()
        ? items.get(index).getParagraph().getParagraphId()
        : null;
  }

  @NonNull
  public Holder onCreateViewHolder(@NonNull ViewGroup p, int type) {
    return new Holder(
        LayoutInflater.from(p.getContext()).inflate(R.layout.item_paragraph, p, false));
  }

  public void onBindViewHolder(@NonNull Holder h, int index) {
    ParagraphItem row = items.get(index);
    Paragraph p = row.getParagraph();
    Translation t = row.getTranslation();
    if (!p.getOriginalText().contentEquals(h.original.getText()))
      h.original.setText(p.getOriginalText());
    h.original.setTextIsSelectable(true);
    h.original.setCustomSelectionActionModeCallback(
        new ActionMode.Callback() {
          public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            menu.add(0, R.id.translate_selection, 1, R.string.translate_selection)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            return true;
          }

          public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
          }

          public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() != R.id.translate_selection) return false;
            int start = Math.min(h.original.getSelectionStart(), h.original.getSelectionEnd());
            int end = Math.max(h.original.getSelectionStart(), h.original.getSelectionEnd());
            if (selection != null
                && start >= 0
                && end > start
                && end <= p.getOriginalText().length()
                && p.getOriginalText().contentEquals(h.original.getText()))
              selection.translate(p, start, end);
            mode.finish();
            return true;
          }

          public void onDestroyActionMode(ActionMode mode) {}
        });
    h.original.setTextSize(
        settings.getFontSizeSp() + (p.getBlockType() == BlockType.HEADING ? 4 : 0));
    h.original.setTypeface(
        Typeface.SERIF, p.getBlockType() == BlockType.HEADING ? Typeface.BOLD : Typeface.NORMAL);
    h.original.setLineSpacing(0, settings.getLineSpacingMultiplier());
    h.translated.setTextSize(Math.max(14, settings.getFontSizeSp() - 2));
    h.translated.setLineSpacing(0, settings.getLineSpacingMultiplier());
    boolean valid =
        t != null
            && p.getParagraphId().equals(t.getParagraphId())
            && p.getSourceHash().equals(t.getSourceHash())
            && "en".equals(t.getSourceLanguage())
            && "zh-CN".equals(t.getTargetLanguage());
    h.translated.setText(
        valid && t.getStatus() == TranslationStatus.SUCCESS
            ? t.getTranslatedText()
            : valid && t.getStatus() == TranslationStatus.FAILED
                ? "翻译失败 · 可重试本章"
                : running || valid && t.getStatus() == TranslationStatus.RUNNING
                    ? "翻译中…"
                    : "未翻译 · 点击上方翻译本章");
  }

  public int getItemCount() {
    return items.size();
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final TextView original, translated;

    Holder(View v) {
      super(v);
      original = v.findViewById(R.id.original);
      translated = v.findViewById(R.id.translated);
    }
  }
}
