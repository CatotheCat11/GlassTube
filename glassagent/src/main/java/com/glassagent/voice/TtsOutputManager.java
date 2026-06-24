package com.glassagent.voice;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

public class TtsOutputManager {
    public interface DoneListener {
        void onDone();
    }

    private TextToSpeech tts;
    private boolean ready;
    private DoneListener doneListener;
    private String activeUtteranceId;

    public TtsOutputManager(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (ready && tts != null) {
                tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.05f);
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                if (utteranceId != null && utteranceId.equals(activeUtteranceId) && doneListener != null) {
                    doneListener.onDone();
                }
            }

            @Override
            public void onError(String utteranceId) {
                if (utteranceId != null && utteranceId.equals(activeUtteranceId) && doneListener != null) {
                    doneListener.onDone();
                }
            }
        });
    }

    public void setDoneListener(DoneListener doneListener) {
        this.doneListener = doneListener;
    }

    public void speak(String text) {
        if (!ready || text == null || text.trim().isEmpty()) {
            if (doneListener != null) {
                doneListener.onDone();
            }
            return;
        }
        activeUtteranceId = "utterance-" + UUID.randomUUID();
        HashMap<String, String> params = new HashMap<String, String>();
        params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, activeUtteranceId);
        tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, params);
    }

    public void stop() {
        tts.stop();
    }

    public void destroy() {
        tts.stop();
        tts.shutdown();
    }
}
