package com.example.clipboardsync;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ClipboardHistoryStore {
    public static final int LIMIT = 20;

    private static final String PREFS_NAME = "clipboardmig";
    private static final String KEY_PHONE_HISTORY = "phone_history";

    private final SharedPreferences prefs;

    public ClipboardHistoryStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public Entry add(String text) {
        String cleanText = text == null ? "" : text.trim();
        if (cleanText.isEmpty()) {
            return null;
        }

        Entry entry = new Entry(cleanText, formatTime(new Date()));
        List<Entry> history = getHistory();
        List<Entry> updated = new ArrayList<>();
        updated.add(entry);

        for (Entry item : history) {
            if (!item.text.equals(cleanText) && updated.size() < LIMIT) {
                updated.add(item);
            }
        }

        saveHistory(updated);
        return entry;
    }

    public List<Entry> getHistory() {
        List<Entry> history = new ArrayList<>();
        String rawHistory = prefs.getString(KEY_PHONE_HISTORY, "[]");

        try {
            JSONArray array = new JSONArray(rawHistory);
            for (int i = 0; i < array.length() && history.size() < LIMIT; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }

                String text = object.optString("text", "").trim();
                if (!text.isEmpty()) {
                    history.add(new Entry(text, object.optString("time", "")));
                }
            }
        } catch (JSONException ignored) {
        }

        return history;
    }

    public int count() {
        return getHistory().size();
    }

    public String createClipboardTextPayload(Entry entry) {
        JSONObject object = new JSONObject();

        try {
            object.put("source", "clipboardmig");
            object.put("type", "clipboardText");
            object.put("text", entry.text);
            object.put("time", entry.time);
        } catch (JSONException ignored) {
        }

        return object.toString();
    }

    public String createHistorySyncPayload() {
        JSONObject object = new JSONObject();
        JSONArray items = new JSONArray();

        try {
            for (Entry entry : getHistory()) {
                JSONObject item = new JSONObject();
                item.put("text", entry.text);
                item.put("time", entry.time);
                items.put(item);
            }

            object.put("source", "clipboardmig");
            object.put("type", "historySync");
            object.put("items", items);
        } catch (JSONException ignored) {
        }

        return object.toString();
    }

    private void saveHistory(List<Entry> history) {
        JSONArray array = new JSONArray();

        try {
            for (Entry entry : history) {
                JSONObject object = new JSONObject();
                object.put("text", entry.text);
                object.put("time", entry.time);
                array.put(object);
            }
        } catch (JSONException ignored) {
        }

        prefs.edit().putString(KEY_PHONE_HISTORY, array.toString()).apply();
    }

    private String formatTime(Date date) {
        return new SimpleDateFormat("M/d/yyyy, h:mm:ss a", Locale.getDefault()).format(date);
    }

    public static class Entry {
        public final String text;
        public final String time;

        public Entry(String text, String time) {
            this.text = text;
            this.time = time;
        }
    }
}

