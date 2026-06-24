package com.glassagent.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

public class VoiceInputManager {
    public interface ResultCallback {
        void onResult(String text);
    }

    public interface ErrorCallback {
        void onError(String message);
    }

    private final Context context;
    private SpeechRecognizer recognizer;

    public VoiceInputManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public void startListening(final ResultCallback resultCallback, final ErrorCallback errorCallback) {
        stopListening();
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                if (errorCallback != null) {
                    errorCallback.onError(errorMessage(error));
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results == null ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches == null || matches.isEmpty() || matches.get(0) == null
                        || matches.get(0).trim().isEmpty()) {
                    if (errorCallback != null) {
                        errorCallback.onError("No speech heard");
                    }
                    return;
                }
                if (resultCallback != null) {
                    resultCallback.onResult(matches.get(0).trim());
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        recognizer.startListening(intent);
    }

    public void stopListening() {
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (RuntimeException ignored) {
            }
            recognizer = null;
        }
    }

    public void destroy() {
        stopListening();
    }

    private static String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_NETWORK:
                return "Speech network error";
            case SpeechRecognizer.ERROR_AUDIO:
                return "Microphone error";
            case SpeechRecognizer.ERROR_SERVER:
                return "Speech service error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Speech cancelled";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No speech heard";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Speech recognizer busy";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission needed";
            default:
                return "Speech failed";
        }
    }
}
