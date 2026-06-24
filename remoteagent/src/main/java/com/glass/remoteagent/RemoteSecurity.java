package com.glass.remoteagent;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

public final class RemoteSecurity {
    private static final String PREFS = "glass_agent_remote";
    private static final String KEY_TOKEN = "token";
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private RemoteSecurity() {
    }

    public static String getToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, "");
        if (token.length() == 0) {
            token = newToken();
            prefs.edit().putString(KEY_TOKEN, token).apply();
        }
        return token;
    }

    public static boolean isAuthorized(Context context, String candidate) {
        return constantTimeEquals(getToken(context), candidate == null ? "" : candidate.trim());
    }

    private static String newToken() {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                builder.append('-');
            }
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        int diff = a.length() ^ b.length();
        int length = Math.min(a.length(), b.length());
        for (int i = 0; i < length; i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
