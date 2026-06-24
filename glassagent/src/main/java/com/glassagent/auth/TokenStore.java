package com.glassagent.auth;

import android.content.Context;
import android.content.SharedPreferences;

public final class TokenStore {
    private static final String PREFS = "token_store";
    private static final String KEY_ALIAS = "glass_agent_key";
    private static final String ACCESS = "access_token";
    private static final String REFRESH = "refresh_token";
    private static final String EXPIRES = "expires_at";

    private final SharedPreferences prefs;
    private final CryptoBoxes.Box crypto;

    public TokenStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.crypto = CryptoBoxes.create(context, prefs, KEY_ALIAS);
    }

    public void save(TokenResponse token) throws Exception {
        prefs.edit()
                .putString(ACCESS, crypto.encrypt(token.accessToken))
                .putString(REFRESH, token.refreshToken == null ? "" : crypto.encrypt(token.refreshToken))
                .putString(EXPIRES, crypto.encrypt(Long.toString(token.expiresAt)))
                .apply();
    }

    public TokenResponse load() {
        try {
            String access = crypto.decrypt(prefs.getString(ACCESS, ""));
            if (access == null || access.length() == 0) {
                return null;
            }
            String refreshRaw = prefs.getString(REFRESH, "");
            String refresh = refreshRaw.length() == 0 ? null : crypto.decrypt(refreshRaw);
            String expires = crypto.decrypt(prefs.getString(EXPIRES, ""));
            long expiresAt = Long.parseLong(expires);
            int expiresIn = (int) Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000L);
            return new TokenResponse(access, refresh, expiresIn, expiresAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean isTokenValid() {
        TokenResponse token = load();
        return token != null && token.accessToken.length() > 0
                && token.expiresAt > System.currentTimeMillis() + 60000L;
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
