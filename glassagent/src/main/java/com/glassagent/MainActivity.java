package com.glassagent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.glassagent.agent.AgentController;
import com.glassagent.agent.AgentException;
import com.glassagent.agent.ConversationHistory;
import com.glassagent.auth.DeviceAuthManager;
import com.glassagent.auth.DeviceCodeResponse;
import com.glassagent.auth.SecretStore;
import com.glassagent.auth.TokenResponse;
import com.glassagent.auth.TokenStore;
import com.glassagent.config.ProviderConfig;
import com.glassagent.ui.AuthCardView;
import com.glassagent.ui.HudOverlay;
import com.glassagent.voice.TtsOutputManager;
import com.glassagent.voice.VoiceInputManager;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 42;
    private static final String ACTION_VOICE_TRIGGER = "com.google.android.glass.action.VOICE_TRIGGER";
    private static final String GLASS_EXTRA_INPUT_SPEECH = "input_speech";
    private static final long LISTEN_TIMEOUT_MS = 12000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Runnable listenTimeout = new Runnable() {
        @Override
        public void run() {
            if (!listening || voiceInput == null || hud == null) {
                return;
            }
            listening = false;
            voiceInput.stopListening();
            hud.setState(HudOverlay.HudState.ERROR, "No speech heard");
        }
    };

    private ProviderConfig config;
    private TokenStore tokenStore;
    private SecretStore secretStore;
    private DeviceAuthManager authManager;
    private ConversationHistory history;
    private AgentController agentController;
    private VoiceInputManager voiceInput;
    private TtsOutputManager ttsOutput;
    private HudOverlay hud;
    private AuthCardView authCard;
    private boolean ready;
    private boolean listening;
    private boolean pendingVoiceListen;
    private String pendingVoiceText;
    private long centerDownAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        captureVoiceLaunch(getIntent());

        if (android.os.Build.VERSION.SDK_INT >= 23
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            showMessage("Permission Needed", "Allow microphone, then reopen");
            return;
        }

        boot();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureVoiceLaunch(intent);
        consumePendingVoiceLaunch();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_RECORD_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            boot();
        } else {
            showMessage("Permission Needed", "Microphone is required");
        }
    }

    private void boot() {
        try {
            config = ProviderConfig.load(this);
            tokenStore = new TokenStore(this);
            secretStore = new SecretStore(this);
            authManager = new DeviceAuthManager(config);
            history = new ConversationHistory(2000);
            agentController = new AgentController(config, tokenStore, secretStore, authManager, history);
            voiceInput = new VoiceInputManager(this);
            ttsOutput = new TtsOutputManager(this);
            ttsOutput.setDoneListener(() -> main.post(() -> {
                if (hud != null && ready) {
                    hud.setState(HudOverlay.HudState.IDLE, null);
                }
            }));
        } catch (Exception e) {
            showConfigError(e.getMessage());
            return;
        }

        if (ProviderConfig.AUTH_API_KEY.equals(config.authType)) {
            bootApiKeyMode();
            return;
        }

        if (tokenStore.isTokenValid()) {
            showReady();
            return;
        }

        TokenResponse token = tokenStore.load();
        if (token != null && token.refreshToken != null && token.refreshToken.length() > 0) {
            showMessage("Refreshing", "Checking saved login");
            executor.execute(() -> {
                try {
                    TokenResponse refreshed = authManager.refreshToken(token.refreshToken);
                    tokenStore.save(refreshed);
                    main.post(this::showReady);
                } catch (Exception e) {
                    main.post(this::showAuth);
                }
            });
        } else {
            showAuth();
        }
    }

    private void bootApiKeyMode() {
        executor.execute(() -> {
            try {
                if (config.inlineApiKey != null && config.inlineApiKey.length() > 0) {
                    secretStore.saveApiKey(config.inlineApiKey);
                    ProviderConfig.stripInlineApiKey(this);
                }
                if (!secretStore.hasApiKey()) {
                    throw new IllegalStateException("api_key missing from config.json");
                }
                main.post(this::showReady);
            } catch (Exception e) {
                main.post(() -> showConfigError(cleanError(e.getMessage())));
            }
        });
    }

    private void showReady() {
        ready = true;
        listening = false;
        hud = new HudOverlay(this);
        setContentView(hud);
        hud.setState(HudOverlay.HudState.IDLE, null);
        if (!voiceInput.isAvailable()) {
            hud.setState(HudOverlay.HudState.ERROR, "Speech service unavailable");
        }
        consumePendingVoiceLaunch();
    }

    private void showAuth() {
        ready = false;
        authCard = new AuthCardView(this);
        setContentView(authCard);
        authCard.setWaiting();
        executor.execute(() -> {
            try {
                DeviceCodeResponse deviceCode = authManager.requestDeviceCode();
                main.post(() -> authCard.setDeviceCode(deviceCode, config));
                TokenResponse token = authManager.pollForToken(deviceCode);
                tokenStore.save(token);
                main.post(this::showReady);
            } catch (Exception e) {
                main.post(() -> authCard.setError(cleanError(e.getMessage())));
            }
        });
    }

    private void startListening() {
        if (!ready || listening) {
            return;
        }
        listening = true;
        ttsOutput.stop();
        hud.setState(HudOverlay.HudState.LISTENING, null);
        main.removeCallbacks(listenTimeout);
        main.postDelayed(listenTimeout, LISTEN_TIMEOUT_MS);
        voiceInput.startListening(text -> main.post(() -> handleVoiceText(text)),
                message -> main.post(() -> {
                    main.removeCallbacks(listenTimeout);
                    listening = false;
                    hud.setState(HudOverlay.HudState.ERROR, cleanError(message));
                }));
    }

    private void handleVoiceText(String text) {
        main.removeCallbacks(listenTimeout);
        if (voiceInput != null) {
            voiceInput.stopListening();
        }
        if (text == null || text.trim().isEmpty()) {
            startListening();
            return;
        }
        text = text.trim();
        listening = false;
        hud.setState(HudOverlay.HudState.THINKING, text);
        final String queryText = text;
        executor.execute(() -> {
            StringBuilder visible = new StringBuilder();
            try {
                String response = agentController.query(queryText, chunk -> main.post(() -> {
                    visible.append(chunk);
                    hud.setState(HudOverlay.HudState.SPEAKING, trimForHud(visible.toString()));
                }));
                String spoken = response == null || response.trim().isEmpty()
                        ? "No response" : response.trim();
                main.post(() -> {
                    hud.setState(HudOverlay.HudState.SPEAKING, trimForHud(spoken));
                    ttsOutput.speak(spoken);
                });
            } catch (AgentException e) {
                main.post(() -> hud.setState(HudOverlay.HudState.ERROR, cleanError(e.getMessage())));
            }
        });
    }

    private void captureVoiceLaunch(Intent intent) {
        if (intent == null || !ACTION_VOICE_TRIGGER.equals(intent.getAction())) {
            return;
        }
        String voiceText = getVoiceText(intent);
        if (voiceText != null) {
            pendingVoiceText = voiceText;
            pendingVoiceListen = false;
        } else {
            pendingVoiceListen = true;
        }
    }

    private void consumePendingVoiceLaunch() {
        if (!ready || hud == null) {
            return;
        }
        final String voiceText = pendingVoiceText;
        final boolean shouldListen = pendingVoiceListen;
        pendingVoiceText = null;
        pendingVoiceListen = false;
        if (voiceText != null && voiceText.trim().length() > 0) {
            if (listening && voiceInput != null) {
                voiceInput.stopListening();
                listening = false;
            }
            handleVoiceText(voiceText);
        } else if (shouldListen) {
            main.postDelayed(this::startListening, 250L);
        }
    }

    private String getVoiceText(Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return null;
        }
        Object rawResults = intent.getExtras().containsKey(RecognizerIntent.EXTRA_RESULTS)
                ? intent.getExtras().get(RecognizerIntent.EXTRA_RESULTS)
                : intent.getExtras().get(GLASS_EXTRA_INPUT_SPEECH);
        if (rawResults instanceof ArrayList) {
            ArrayList<?> values = (ArrayList<?>) rawResults;
            if (!values.isEmpty() && values.get(0) != null) {
                String first = values.get(0).toString().trim();
                return first.length() == 0 ? null : first;
            }
        }
        if (rawResults instanceof String[]) {
            String[] values = (String[]) rawResults;
            if (values.length > 0 && values[0] != null) {
                String first = values[0].trim();
                return first.length() == 0 ? null : first;
            }
        }
        if (rawResults instanceof String) {
            String value = ((String) rawResults).trim();
            return value.length() == 0 ? null : value;
        }
        return null;
    }

    private void cancelOrStop() {
        if (listening) {
            listening = false;
            main.removeCallbacks(listenTimeout);
            voiceInput.stopListening();
            hud.setState(HudOverlay.HudState.IDLE, null);
            return;
        }
        ttsOutput.stop();
        if (hud != null) {
            hud.setState(HudOverlay.HudState.IDLE, null);
        }
    }

    private void clearSession() {
        if (agentController != null) {
            agentController.clearHistory();
        }
        ttsOutput.stop();
        if (hud != null) {
            hud.setState(HudOverlay.HudState.SPEAKING, "Session cleared");
        }
        ttsOutput.speak("Session cleared");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) {
                centerDownAt = System.currentTimeMillis();
            }
            if (event.isLongPress()) {
                clearSession();
                return true;
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            long held = System.currentTimeMillis() - centerDownAt;
            if (held > 1200L) {
                clearSession();
            } else if (!ready && authCard != null) {
                showAuth();
            } else if (hud != null && listening) {
                cancelOrStop();
            } else if (hud != null) {
                startListening();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void showConfigError(String detail) {
        showMessage("Config Missing",
                "Put config.json in /data/data/com.glassagent/files\n" + cleanError(detail));
    }

    private void showMessage(String title, String body) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        TextView text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setTextSize(16f);
        text.setText(title + "\n\n" + body);
        text.setGravity(android.view.Gravity.CENTER);
        text.setPadding(dp(18), dp(18), dp(18), dp(18));
        frame.addView(text, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
    }

    private String trimForHud(String value) {
        if (value == null) {
            return "";
        }
        value = value.replace('\n', ' ').trim();
        return value.length() > 180 ? value.substring(value.length() - 180) : value;
    }

    private String cleanError(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "Request failed";
        }
        String cleaned = message.replace('\n', ' ').trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) : cleaned;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        ready = false;
        main.removeCallbacks(listenTimeout);
        if (agentController != null) {
            agentController.clearHistory();
        }
        if (voiceInput != null) {
            voiceInput.destroy();
        }
        if (ttsOutput != null) {
            ttsOutput.destroy();
        }
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
