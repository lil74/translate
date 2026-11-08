package com.example.bilingualreader.ui.toc;

import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.TocEntry;
import java.util.*;

public final class TocAdapter extends ListAdapter<TocEntry, TocAdapter.Holder> {
  public interface OnOpen {
    void open(TocEntry e);
  }

  private final OnOpen open;
  private final Map<String, TocEntry> parents = new HashMap<>();

  public TocAdapter(OnOpen open) {
    super(
        new DiffUtil.ItemCallback<TocEntry>() {
          public boolean areItemsTheSame(@NonNull TocEntry a, @NonNull TocEntry b) {
            return a.getTocId().equals(b.getTocId());
          }

          public boolean areContentsTheSame(@NonNull TocEntry a, @NonNull TocEntry b) {
            return a.getTitle().equals(b.getTitle())
                && Objects.equals(a.getParentTocId(), b.getParentTocId())
                && a.getChapterId().equals(b.getChapterId())
                && Objects.equals(a.getParagraphId(), b.getParagraphId());
          }
        });
    this.open = open;
  }

  public void submit(List<TocEntry> rows) {
    parents.clear();
    for (TocEntry e : rows) parents.put(e.getTocId(), e);
    submitList(new ArrayList<>(rows));
  }

  @NonNull
  public Holder onCreateViewHolder(@NonNull ViewGroup p, int type) {
    return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_toc, p, false));
  }

  public void onBindViewHolder(@NonNull Holder h, int pos) {
    TocEntry e = getItem(pos);
    String parent = e.getParentTocId();
    int depth = 0;
    Set<String> seen = new HashSet<>();
    while (parent != null && seen.add(parent) && parents.containsKey(parent) && depth < 8) {
      depth++;
      parent = parents.get(parent).getParentTocId();
    }
    h.title.setText(e.getTitle());
    h.title.setPaddingRelative(
        Math.round(depth * 16 * h.itemView.getResources().getDisplayMetrics().density),
        h.title.getPaddingTop(),
        h.title.getPaddingEnd(),
        h.title.getPaddingBottom());
    h.itemView.setOnClickListener(v -> open.open(e));
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final TextView title;

    Holder(View v) {
      super(v);
      title = v.findViewById(R.id.toc_title);
    }
  }
}
