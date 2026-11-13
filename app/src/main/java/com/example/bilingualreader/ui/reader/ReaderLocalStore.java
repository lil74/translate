package com.example.bilingualreader.ui.reader;

import android.content.*;
import java.util.*;
import org.json.*;

/** UI-owned local annotations; chapter translations and Room contracts stay independent. */
public final class ReaderLocalStore {
  private final SharedPreferences prefs;

  public ReaderLocalStore(Context context) {
    prefs = context.getSharedPreferences("reader_controls", Context.MODE_PRIVATE);
  }

  public SharedPreferences preferences() {
    return prefs;
  }

  public boolean favorite(String book) {
    return prefs.getBoolean("favorite:" + book, false);
  }

  public void favorite(String book, boolean value) {
    prefs.edit().putBoolean("favorite:" + book, value).apply();
  }

  public static final class Bookmark {
    public final String chapter, paragraph, title, excerpt;
    public final int offset;

    public Bookmark(String chapter, String paragraph, String title, String excerpt, int offset) {
      this.chapter = chapter;
      this.paragraph = paragraph;
      this.title = title;
      this.excerpt = excerpt;
      this.offset = offset;
    }
  }

  public List<Bookmark> bookmarks(String book) {
    List<Bookmark> out = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(prefs.getString("bookmarks:" + book, "[]"));
      for (int i = 0; i < array.length(); i++) {
        JSONObject o = array.getJSONObject(i);
        out.add(
            new Bookmark(
                o.getString("chapter"),
                o.getString("paragraph"),
                o.getString("title"),
                o.optString("excerpt"),
                o.optInt("offset")));
      }
    } catch (JSONException ignored) {
    }
    return out;
  }

  public void save(String book, List<Bookmark> rows) {
    JSONArray array = new JSONArray();
    for (Bookmark row : rows)
      try {
        JSONObject o = new JSONObject();
        o.put("chapter", row.chapter);
        o.put("paragraph", row.paragraph);
        o.put("title", row.title);
        o.put("excerpt", row.excerpt);
        o.put("offset", row.offset);
        array.put(o);
      } catch (JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    prefs.edit().putString("bookmarks:" + book, array.toString()).apply();
  }
}
