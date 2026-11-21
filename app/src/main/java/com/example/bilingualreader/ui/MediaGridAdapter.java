package com.example.bilingualreader.ui;

import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.recyclerview.widget.*;
import com.example.bilingualreader.R;
import java.util.*;
import java.util.concurrent.*;

/** Bounded image cache and recycled-holder checks prevent cross-item or late image updates. */
public final class MediaGridAdapter<T> extends RecyclerView.Adapter<MediaGridAdapter.Holder> {
  public interface Presenter<T> {
    String key(T value);

    String title(T value);

    String image(T value);

    default String detail(T value) {
      return null;
    }

    void click(T value);

    void longClick(T value);
  }

  private final Presenter<T> presenter;
  private final boolean video;
  private List<T> rows = Collections.emptyList();
  private final ExecutorService images = Executors.newFixedThreadPool(2);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LruCache<String, Bitmap> cache =
      new LruCache<String, Bitmap>(8192) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
          return Math.max(1, value.getByteCount() / 1024);
        }
      };
  private boolean closed;

  public MediaGridAdapter(Presenter<T> presenter, boolean video) {
    this.presenter = presenter;
    this.video = video;
  }

  public void submit(List<T> values) {
    rows = new ArrayList<>(values);
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
    return new Holder(
        LayoutInflater.from(parent.getContext()).inflate(R.layout.item_media_cover, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull Holder holder, int position) {
    T value = rows.get(position);
    if (holder.job != null) holder.job.cancel(true);
    String source = presenter.image(value), key = presenter.key(value) + ":" + source;
    holder.key = key;
    ((CoverFrame) holder.itemView.findViewById(R.id.media_cover_frame))
        .ratio(video ? 16f / 9f : 2f / 3f);
    holder.image.setImageDrawable(null);
    holder.image.setScaleType(
        video ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
    holder.placeholder.setText(presenter.title(value));
    holder.placeholder.setVisibility(View.VISIBLE);
    ((TextView) holder.itemView.findViewById(R.id.media_title)).setText(presenter.title(value));
    TextView detail = holder.itemView.findViewById(R.id.media_detail);
    String description = presenter.detail(value);
    detail.setText(description);
    detail.setVisibility(description == null ? View.GONE : View.VISIBLE);
    holder.itemView.findViewById(R.id.media_play).setVisibility(video ? View.VISIBLE : View.GONE);
    holder.itemView.setContentDescription(
        presenter.title(value) + (description == null ? "" : "，" + description));
    holder.itemView.setOnClickListener(v -> presenter.click(value));
    holder.itemView.setOnLongClickListener(
        v -> {
          presenter.longClick(value);
          return true;
        });
    if (source == null || source.isEmpty()) return;
    Bitmap hit = cache.get(key);
    if (hit != null) {
      holder.image.setImageBitmap(hit);
      holder.placeholder.setVisibility(View.GONE);
      return;
    }
    android.content.Context context = holder.itemView.getContext().getApplicationContext();
    holder.job =
        images.submit(
            () -> {
              Bitmap bitmap = null;
              try {
                if (video) {
                  MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                  try {
                    retriever.setDataSource(context, Uri.parse(source));
                    if (Build.VERSION.SDK_INT >= 27)
                      bitmap =
                          retriever.getScaledFrameAtTime(
                              0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270);
                    else {
                      Bitmap original =
                          retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                      if (original != null) {
                        bitmap = Bitmap.createScaledBitmap(original, 480, 270, true);
                        if (bitmap != original) original.recycle();
                      }
                    }
                  } finally {
                    retriever.release();
                  }
                } else {
                  BitmapFactory.Options bounds = new BitmapFactory.Options();
                  bounds.inJustDecodeBounds = true;
                  BitmapFactory.decodeFile(source, bounds);
                  BitmapFactory.Options options = new BitmapFactory.Options();
                  options.inSampleSize = 1;
                  while (bounds.outWidth / options.inSampleSize > 600
                      || bounds.outHeight / options.inSampleSize > 900) options.inSampleSize *= 2;
                  bitmap = BitmapFactory.decodeFile(source, options);
                }
              } catch (Exception ignored) {
              }
              Bitmap ready = bitmap;
              if (ready == null) return;
              main.post(
                  () -> {
                    if (closed) return;
                    cache.put(key, ready);
                    if (key.equals(holder.key)) {
                      holder.image.setImageBitmap(ready);
                      holder.placeholder.setVisibility(View.GONE);
                    }
                  });
            });
  }

  @Override
  public void onViewRecycled(@NonNull Holder holder) {
    holder.key = null;
    if (holder.job != null) holder.job.cancel(true);
    holder.image.setImageDrawable(null);
  }

  @Override
  public int getItemCount() {
    return rows.size();
  }

  public void close() {
    closed = true;
    images.shutdownNow();
    cache.evictAll();
  }

  public static void layout(RecyclerView list, boolean video) {
    GridLayoutManager manager = new GridLayoutManager(list.getContext(), video ? 2 : 3);
    list.setLayoutManager(manager);
    list.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          int min = Math.round((video ? 150 : 100) * v.getResources().getDisplayMetrics().density);
          int columns =
              Math.max(
                  2,
                  Math.min(
                      video ? 4 : 5,
                      (r - l - list.getPaddingLeft() - list.getPaddingRight()) / Math.max(1, min)));
          if (manager.getSpanCount() != columns) manager.setSpanCount(columns);
        });
  }

  public static final class Holder extends RecyclerView.ViewHolder {
    final ImageView image;
    final TextView placeholder;
    String key;
    Future<?> job;

    Holder(View view) {
      super(view);
      image = view.findViewById(R.id.media_cover);
      placeholder = view.findViewById(R.id.media_placeholder);
    }
  }
}
