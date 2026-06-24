package com.glassagent.auth;

import com.glassagent.config.ProviderConfig;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class DeviceAuthManager {
    private final ProviderConfig config;
    private final OkHttpClient client = new OkHttpClient();

    public DeviceAuthManager(ProviderConfig config) {
        this.config = config;
    }

    public DeviceCodeResponse requestDeviceCode() throws AuthException {
        FormBody.Builder body = new FormBody.Builder()
                .add("client_id", config.clientId)
                .add("scope", config.scope);
        if (config.clientSecret != null) {
            body.add("client_secret", config.clientSecret);
        }
        JSONObject json = postJson(config.deviceEndpoint, body.build());
        return new DeviceCodeResponse(
                json.optString("device_code"),
                json.optString("user_code"),
                json.optString("verification_uri", json.optString("verification_url")),
                json.optInt("expires_in"),
                Math.max(1, json.optInt("interval", 5))
        );
    }

    public TokenResponse pollForToken(DeviceCodeResponse device) throws AuthException {
        long expiresAt = System.currentTimeMillis() + (device.expiresIn * 1000L);
        int interval = Math.max(1, device.interval);
        while (System.currentTimeMillis() < expiresAt) {
            sleep(interval * 1000L);
            FormBody.Builder body = new FormBody.Builder()
                    .add("client_id", config.clientId)
                    .add("device_code", device.deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            if (config.clientSecret != null) {
                body.add("client_secret", config.clientSecret);
            }
            TokenAttempt attempt = postToken(config.tokenEndpoint, body.build());
            if (attempt.token != null) {
                return attempt.token;
            }
            if ("authorization_pending".equals(attempt.error)) {
                continue;
            }
            if ("slow_down".equals(attempt.error)) {
                interval += 5;
                continue;
            }
            if ("expired_token".equals(attempt.error) || "access_denied".equals(attempt.error)) {
                throw new AuthException(attempt.error);
            }
            throw new AuthException(attempt.error.length() == 0 ? "Token polling failed" : attempt.error);
        }
        throw new AuthException("expired_token");
    }

    public TokenResponse refreshToken(String refreshToken) throws AuthException {
        FormBody.Builder body = new FormBody.Builder()
                .add("client_id", config.clientId)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token");
        if (config.clientSecret != null) {
            body.add("client_secret", config.clientSecret);
        }
        JSONObject json = postJson(config.tokenEndpoint, body.build());
        return parseToken(json);
    }

    private JSONObject postJson(String url, RequestBody body) throws AuthException {
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = client.newCall(request).execute()) {
            String text = read(response.body());
            JSONObject json = text.length() == 0 ? new JSONObject() : new JSONObject(text);
            if (!response.isSuccessful()) {
                String message = json.optString("error", "HTTP " + response.code());
                throw new AuthException(message);
            }
            return json;
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("Network error", e);
        }
    }

    private TokenAttempt postToken(String url, RequestBody body) throws AuthException {
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = client.newCall(request).execute()) {
            String text = read(response.body());
            JSONObject json = text.length() == 0 ? new JSONObject() : new JSONObject(text);
            if (response.isSuccessful()) {
                return new TokenAttempt(parseToken(json), "");
            }
            return new TokenAttempt(null, json.optString("error", "HTTP " + response.code()));
        } catch (Exception e) {
            throw new AuthException("Network error", e);
        }
    }

    private TokenResponse parseToken(JSONObject json) {
        return new TokenResponse(
                json.optString("access_token"),
                json.optString("refresh_token", null),
                json.optInt("expires_in")
        );
    }

    private String read(ResponseBody body) throws IOException {
        return body == null ? "" : body.string();
    }

    private void sleep(long ms) throws AuthException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Interrupted", e);
        }
    }

    private static final class TokenAttempt {
        final TokenResponse token;
        final String error;

        TokenAttempt(TokenResponse token, String error) {
            this.token = token;
            this.error = error == null ? "" : error;
        }
    }
}
