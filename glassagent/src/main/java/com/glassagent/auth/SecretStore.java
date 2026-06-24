package com.glassagent.auth;

import android.content.Context;
import android.content.SharedPreferences;

public final class SecretStore {
    private static final String PREFS = "secret_store";
    private static final String KEY_ALIAS = "glass_agent_secret_key";
    private static final String API_KEY = "api_key";

    private final SharedPreferences prefs;
    private final CryptoBoxes.Box crypto;

    public SecretStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.crypto = CryptoBoxes.create(context, prefs, KEY_ALIAS);
    }

    public void saveApiKey(String apiKey) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("api_key missing from config.json");
        }
        prefs.edit().putString(API_KEY, crypto.encrypt(apiKey.trim())).apply();
    }

    public String loadApiKey() {
        try {
            return crypto.decrypt(prefs.getString(API_KEY, ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean hasApiKey() {
        String apiKey = loadApiKey();
        return apiKey != null && apiKey.length() > 0;
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
