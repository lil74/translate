package com.example.bilingualreader.ui.lookup;

import android.content.*;
import java.util.*;
import org.json.*;

/** Local successful searches only. Never sent to a translation provider. */
final class LookupHistory {
  private final SharedPreferences prefs;

  LookupHistory(Context context) {
    prefs = context.getSharedPreferences("lookup_history", Context.MODE_PRIVATE);
  }

  static final class Entry {
    final String query, translated;
    final boolean demo;

    Entry(String query, String translated, boolean demo) {
      this.query = query;
      this.translated = translated;
      this.demo = demo;
    }
  }

  List<Entry> rows() {
    List<Entry> out = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(prefs.getString("rows", "[]"));
      for (int i = 0; i < Math.min(array.length(), 30); i++) {
        JSONObject o = array.getJSONObject(i);
        out.add(new Entry(o.getString("query"), o.getString("translated"), o.optBoolean("demo")));
      }
    } catch (JSONException ignored) {
    }
    return out;
  }

  void record(String query, String translated, boolean demo) {
    List<Entry> rows = rows();
    rows.removeIf(row -> row.query.equals(query));
    rows.add(0, new Entry(query, translated, demo));
    JSONArray array = new JSONArray();
    for (Entry row : rows.subList(0, Math.min(rows.size(), 30)))
      try {
        JSONObject o = new JSONObject();
        o.put("query", row.query);
        o.put("translated", row.translated);
        o.put("demo", row.demo);
        array.put(o);
      } catch (JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    prefs.edit().putString("rows", array.toString()).apply();
  }

  void clear() {
    prefs.edit().remove("rows").apply();
  }
}
