package com.glassagent.agent;

import com.glassagent.auth.DeviceAuthManager;
import com.glassagent.auth.SecretStore;
import com.glassagent.auth.TokenResponse;
import com.glassagent.auth.TokenStore;
import com.glassagent.config.ProviderConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class AgentController {
    public interface ChunkCallback {
        void onChunk(String text);
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ProviderConfig config;
    private final TokenStore tokenStore;
    private final SecretStore secretStore;
    private final DeviceAuthManager authManager;
    private final ConversationHistory history;
    private final OkHttpClient client = new OkHttpClient();

    public AgentController(ProviderConfig config, TokenStore tokenStore,
                           SecretStore secretStore, DeviceAuthManager authManager,
                           ConversationHistory history) {
        this.config = config;
        this.tokenStore = tokenStore;
        this.secretStore = secretStore;
        this.authManager = authManager;
        this.history = history;
    }

    public String query(String userText, ChunkCallback onChunk) throws AgentException {
        history.add("user", userText);
        try {
            return queryInternal(onChunk, true);
        } catch (AgentException e) {
            throw e;
        }
    }

    public void clearHistory() {
        history.clear();
    }

    private String queryInternal(ChunkCallback onChunk, boolean allowRefresh) throws AgentException {
        String bearer = bearerToken();
        try {
            JSONObject body = new JSONObject();
            body.put("model", config.model);
            body.put("messages", history.toApiMessages());
            body.put("stream", true);
            body.put("max_tokens", 300);

            Request request = new Request.Builder()
                    .url(config.apiBase + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + bearer)
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();

            Response response = client.newCall(request).execute();
            TokenResponse token = tokenStore == null ? null : tokenStore.load();
            if (ProviderConfig.AUTH_DEVICE_CODE.equals(config.authType)
                    && response.code() == 401
                    && allowRefresh
                    && token != null
                    && token.refreshToken != null) {
                response.close();
                TokenResponse refreshed = authManager.refreshToken(token.refreshToken);
                tokenStore.save(refreshed);
                return queryInternal(onChunk, false);
            }
            if (response.code() == 429) {
                response.close();
                throw new AgentException("Rate limit, try again shortly");
            }
            if (!response.isSuccessful()) {
                String message = response.body() == null ? "Request failed" : response.body().string();
                response.close();
                throw new AgentException(message);
            }

            String full = readStream(response, onChunk);
            history.add("assistant", full);
            return full;
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("No connection", e);
        }
    }

    private String bearerToken() throws AgentException {
        if (ProviderConfig.AUTH_API_KEY.equals(config.authType)) {
            String apiKey = secretStore == null ? null : secretStore.loadApiKey();
            if (apiKey == null || apiKey.length() == 0) {
                throw new AgentException("API key missing");
            }
            return apiKey;
        }

        TokenResponse token = tokenStore == null ? null : tokenStore.load();
        if (token == null || token.accessToken.length() == 0) {
            throw new AgentException("Not authorised");
        }
        return token.accessToken;
    }

    private String readStream(Response response, ChunkCallback onChunk) throws Exception {
        StringBuilder full = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) {
                break;
            }
            String chunk = parseChunk(data);
            if (chunk.length() > 0) {
                full.append(chunk);
                if (onChunk != null) {
                    onChunk.onChunk(chunk);
                }
            }
        }
        response.close();
        return full.toString().trim();
    }

    private String parseChunk(String data) {
        try {
            JSONObject object = new JSONObject(data);
            JSONArray choices = object.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return "";
            }
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) {
                return "";
            }
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null) {
                return "";
            }
            return delta.optString("content", "");
        } catch (Exception ignored) {
            return "";
        }
    }
}
