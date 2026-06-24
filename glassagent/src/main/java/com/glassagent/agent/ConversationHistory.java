package com.glassagent.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ConversationHistory {
    private static final String SYSTEM_PROMPT =
            "You are a voice assistant running on smart glasses. "
                    + "Responses must be concise, 1-3 sentences maximum unless the user explicitly asks for detail. "
                    + "No markdown. No lists. Speak as if dictating to someone walking. "
                    + "If asked for directions, code, or long-form content, acknowledge and offer to send it to their phone.";

    private final int maxTokenEstimate;
    private final List<Message> messages = new ArrayList<>();

    public ConversationHistory(int maxTokenEstimate) {
        this.maxTokenEstimate = maxTokenEstimate;
    }

    public synchronized void add(String role, String content) {
        if (content == null || content.trim().length() == 0) {
            return;
        }
        messages.add(new Message(role, content.trim()));
        pruneIfNeeded();
    }

    public synchronized JSONArray toApiMessages() throws JSONException {
        JSONArray array = new JSONArray();
        array.put(message("system", SYSTEM_PROMPT));
        for (Message item : messages) {
            array.put(message(item.role, item.content));
        }
        return array;
    }

    public synchronized void clear() {
        messages.clear();
    }

    private void pruneIfNeeded() {
        while (estimateTokens() > maxTokenEstimate && !messages.isEmpty()) {
            messages.remove(0);
        }
    }

    private int estimateTokens() {
        int chars = SYSTEM_PROMPT.length();
        for (Message item : messages) {
            chars += item.content.length();
        }
        return chars / 4;
    }

    private JSONObject message(String role, String content) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("role", role);
        object.put("content", content);
        return object;
    }

    private static final class Message {
        final String role;
        final String content;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
