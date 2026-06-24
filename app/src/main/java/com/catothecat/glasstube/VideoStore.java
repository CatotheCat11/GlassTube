package com.catothecat.glasstube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class VideoStore {
    public static final String ACTION_OPEN_URL = "com.catothecat.glasstube.OPEN_URL";
    public static final String ACTION_SEARCH = "com.catothecat.glasstube.SEARCH";
    public static final String ACTION_REMOTE_COMMAND = "com.catothecat.glasstube.REMOTE_COMMAND";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_TEXT = "text";

    private static final String PREFS = "glasstube_store";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_QUEUE = "queue";
    private static final String KEY_PLAYER_TITLE = "player_title";
    private static final String KEY_PLAYER_URL = "player_url";
    private static final String KEY_PLAYER_STATE = "player_state";
    private static final String KEY_PLAYER_POSITION = "player_position";
    private static final String KEY_PLAYER_DURATION = "player_duration";
    private static final String KEY_APP_ACTIVE = "app_active";
    private static final String KEY_LAST_ACTIVE = "last_active";
    private static final int MAX_ITEMS = 50;

    private VideoStore() {
    }

    public static final class Entry {
        public final String title;
        public final String url;
        public final long time;

        public Entry(String title, String url, long time) {
            this.title = cleanTitle(title, url);
            this.url = url;
            this.time = time;
        }
    }

    public static void addHistory(Context context, String title, String url) {
        add(context, KEY_HISTORY, new Entry(title, url, System.currentTimeMillis()), true);
    }

    public static void addFavorite(Context context, String title, String url) {
        add(context, KEY_FAVORITES, new Entry(title, url, System.currentTimeMillis()), true);
    }

    public static boolean isFavorite(Context context, String url) {
        for (Entry entry : getFavorites(context)) {
            if (entry.url.equals(url)) {
                return true;
            }
        }
        return false;
    }

    public static void removeFavorite(Context context, String url) {
        List<Entry> entries = read(context, KEY_FAVORITES);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).url.equals(url)) {
                entries.remove(i);
            }
        }
        write(context, KEY_FAVORITES, entries);
    }

    public static void enqueue(Context context, String title, String url) {
        add(context, KEY_QUEUE, new Entry(title, url, System.currentTimeMillis()), false);
    }

    public static Entry pollQueue(Context context) {
        List<Entry> entries = read(context, KEY_QUEUE);
        if (entries.isEmpty()) {
            return null;
        }
        Entry entry = entries.remove(0);
        write(context, KEY_QUEUE, entries);
        return entry;
    }

    public static List<Entry> getHistory(Context context) {
        return read(context, KEY_HISTORY);
    }

    public static List<Entry> getFavorites(Context context) {
        return read(context, KEY_FAVORITES);
    }

    public static List<Entry> getQueue(Context context) {
        return read(context, KEY_QUEUE);
    }

    public static void clearQueue(Context context) {
        write(context, KEY_QUEUE, new ArrayList<Entry>());
    }

    public static void savePlaybackState(Context context, String title, String url, String state,
                                         long positionMs, long durationMs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PLAYER_TITLE, cleanTitle(title, url))
                .putString(KEY_PLAYER_URL, url == null ? "" : url)
                .putString(KEY_PLAYER_STATE, state == null ? "idle" : state)
                .putLong(KEY_PLAYER_POSITION, Math.max(0, positionMs))
                .putLong(KEY_PLAYER_DURATION, Math.max(0, durationMs))
                .apply();
    }

    public static void setAppActive(Context context, boolean active) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_APP_ACTIVE, active)
                .putLong(KEY_LAST_ACTIVE, System.currentTimeMillis())
                .apply();
    }

    public static boolean isAppActive(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_APP_ACTIVE, false);
    }

    public static long getLastActive(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_ACTIVE, 0);
    }

    public static String getPlaybackJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject object = new JSONObject();
        try {
            object.put("title", prefs.getString(KEY_PLAYER_TITLE, ""));
            object.put("url", prefs.getString(KEY_PLAYER_URL, ""));
            object.put("state", prefs.getString(KEY_PLAYER_STATE, "idle"));
            object.put("positionMs", prefs.getLong(KEY_PLAYER_POSITION, 0));
            object.put("durationMs", prefs.getLong(KEY_PLAYER_DURATION, 0));
        } catch (JSONException ignored) {
        }
        return object.toString();
    }

    private static void add(Context context, String key, Entry entry, boolean newestFirst) {
        if (entry.url == null || entry.url.trim().length() == 0) {
            return;
        }
        List<Entry> entries = read(context, key);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entry.url.equals(entries.get(i).url)) {
                entries.remove(i);
            }
        }
        if (newestFirst) {
            entries.add(0, entry);
        } else {
            entries.add(entry);
        }
        while (entries.size() > MAX_ITEMS) {
            entries.remove(entries.size() - 1);
        }
        write(context, key, entries);
    }

    private static List<Entry> read(Context context, String key) {
        ArrayList<Entry> entries = new ArrayList<Entry>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(key, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String url = object.optString("url", "");
                if (url.length() == 0) {
                    continue;
                }
                entries.add(new Entry(object.optString("title", url), url, object.optLong("time", 0)));
            }
        } catch (JSONException ignored) {
        }
        return entries;
    }

    private static void write(Context context, String key, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("title", entry.title);
                object.put("url", entry.url);
                object.put("time", entry.time);
                array.put(object);
            } catch (JSONException ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key, array.toString())
                .apply();
    }

    private static String cleanTitle(String title, String url) {
        if (title != null && title.trim().length() > 0) {
            return title.trim();
        }
        return url == null ? "YouTube video" : url;
    }
}
