package com.example.bilingualreader.ui;

import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.bilingualreader.R;
import java.util.*;

public final class EntryAdapter<T> extends RecyclerView.Adapter<EntryAdapter.Holder> {
  public interface Presenter<T> {
    String title(T item);

    String subtitle(T item);

    void click(T item);

    default void longClick(T item) {}
  }

  private List<T> items = Collections.emptyList();
  private final Presenter<T> presenter;

  public EntryAdapter(Presenter<T> presenter) {
    this.presenter = presenter;
  }

  public void submit(List<T> list) {
    items = new ArrayList<>(list);
    notifyDataSetChanged();
  }

  @NonNull
  public Holder onCreateViewHolder(@NonNull ViewGroup p, int type) {
    return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_entry, p, false));
  }

  public void onBindViewHolder(@NonNull Holder h, int position) {
    T item = items.get(position);
    h.title.setText(presenter.title(item));
    h.subtitle.setText(presenter.subtitle(item));
    h.itemView.setOnClickListener(v -> presenter.click(item));
    h.itemView.setOnLongClickListener(
        v -> {
          presenter.longClick(item);
          return true;
        });
  }

  public int getItemCount() {
    return items.size();
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final TextView title, subtitle;

    Holder(View view) {
      super(view);
      title = view.findViewById(R.id.title);
      subtitle = view.findViewById(R.id.subtitle);
    }
  }
}
