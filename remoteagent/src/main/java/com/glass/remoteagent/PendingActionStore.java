package com.glass.remoteagent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class PendingActionStore {
    private static final String PREFS = "glass_agent_actions";
    private static final String KEY_ACTIONS = "actions";
    private static final int MAX_ACTIONS = 20;

    private PendingActionStore() {
    }

    public static String add(Context context, String type, String payload, boolean risky) {
        String id = UUID.randomUUID().toString();
        JSONArray actions = read(context);
        JSONObject action = new JSONObject();
        try {
            action.put("id", id);
            action.put("type", emptyTo(type, "message"));
            action.put("payload", payload == null ? "" : payload);
            action.put("risky", risky);
            action.put("createdAt", System.currentTimeMillis());
            actions.put(action);
            while (actions.length() > MAX_ACTIONS) {
                actions = removeAt(actions, 0);
            }
            write(context, actions);
        } catch (JSONException ignored) {
        }
        return id;
    }

    public static JSONObject take(Context context, String id) {
        JSONArray actions = read(context);
        JSONObject found = null;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) {
                continue;
            }
            if (found == null && id != null && id.equals(action.optString("id"))) {
                found = action;
            } else {
                kept.put(action);
            }
        }
        write(context, kept);
        return found;
    }

    public static String json(Context context) {
        return read(context).toString();
    }

    private static JSONArray read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            return new JSONArray(prefs.getString(KEY_ACTIONS, "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private static void write(Context context, JSONArray actions) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIONS, actions.toString())
                .apply();
    }

    private static JSONArray removeAt(JSONArray source, int removeIndex) {
        JSONArray target = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            if (i != removeIndex) {
                target.put(source.opt(i));
            }
        }
        return target;
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }
}
