package com.glassagent.config;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public final class ProviderConfig {
    public static final String AUTH_DEVICE_CODE = "device_code";
    public static final String AUTH_API_KEY = "api_key";

    public final String authType;
    public final String deviceEndpoint;
    public final String tokenEndpoint;
    public final String clientId;
    public final String clientSecret;
    public final String scope;
    public final String apiBase;
    public final String model;
    public final String verificationUriLabel;
    public final String inlineApiKey;

    private ProviderConfig(String authType, String deviceEndpoint, String tokenEndpoint, String clientId,
                           String clientSecret, String scope, String apiBase, String model,
                           String verificationUriLabel, String inlineApiKey) {
        this.authType = authType;
        this.deviceEndpoint = deviceEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.apiBase = trimTrailingSlash(apiBase);
        this.model = model;
        this.verificationUriLabel = verificationUriLabel == null ? "" : verificationUriLabel;
        this.inlineApiKey = inlineApiKey;
    }

    public static ProviderConfig load(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "config.json");
        if (!file.exists()) {
            throw new IllegalStateException("config.json not found in app files dir");
        }
        JSONObject json = new JSONObject(readFile(file));
        String authType = optional(json, "auth_type");
        if (authType == null) {
            authType = AUTH_DEVICE_CODE;
        }
        if (!AUTH_DEVICE_CODE.equals(authType) && !AUTH_API_KEY.equals(authType)) {
            throw new IllegalStateException("auth_type must be device_code or api_key");
        }

        String deviceEndpoint = null;
        String tokenEndpoint = null;
        String clientId = null;
        String clientSecret = null;
        String scope = null;
        String apiKey = null;

        if (AUTH_DEVICE_CODE.equals(authType)) {
            deviceEndpoint = required(json, "device_endpoint");
            tokenEndpoint = required(json, "token_endpoint");
            clientId = required(json, "client_id");
            clientSecret = optional(json, "client_secret");
            scope = required(json, "scope");
        } else {
            apiKey = optional(json, "api_key");
        }

        return new ProviderConfig(
                authType,
                deviceEndpoint,
                tokenEndpoint,
                clientId,
                clientSecret,
                scope,
                required(json, "api_base"),
                required(json, "model"),
                optional(json, "verification_uri_label"),
                apiKey
        );
    }

    public static void stripInlineApiKey(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "config.json");
        JSONObject json = new JSONObject(readFile(file));
        if (!json.has("api_key")) {
            return;
        }
        json.remove("api_key");
        FileOutputStream output = new FileOutputStream(file, false);
        try {
            output.write(json.toString(2).getBytes("UTF-8"));
        } finally {
            output.close();
        }
    }

    private static String required(JSONObject json, String key) throws Exception {
        String value = json.optString(key, "").trim();
        if (value.length() == 0) {
            throw new IllegalStateException(key + " missing from config.json");
        }
        return value;
    }

    private static String optional(JSONObject json, String key) {
        if (!json.has(key) || json.isNull(key)) {
            return null;
        }
        String value = json.optString(key, "").trim();
        return value.length() == 0 ? null : value;
    }

    private static String readFile(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        } finally {
            input.close();
        }
    }

    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
