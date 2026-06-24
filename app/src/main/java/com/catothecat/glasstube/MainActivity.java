package com.catothecat.glasstube;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.glass.media.Sounds;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int SPEECH_REQUEST = 10;
    private static final String GLASS_EXTRA_INPUT_SPEECH = "input_speech";

    private final List<CardBuilder> cards = new ArrayList<CardBuilder>();
    private final List<CardAction> actions = new ArrayList<CardAction>();
    private final List<AsyncTask<?, ?, ?>> tasks = new ArrayList<AsyncTask<?, ?, ?>>();

    private CardScrollView cardScrollView;
    private CardAdapter adapter;
    private String playlistUrl = "";
    private boolean remoteReceiverRegistered;
    private SpeechRecognizer speechRecognizer;
    private boolean listeningForSearch;
    private ScreenMode screenMode = ScreenMode.HOME;
    private final Handler handler = new Handler();
    private final BroadcastReceiver remoteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!VideoStore.ACTION_REMOTE_COMMAND.equals(intent.getAction())) {
                return;
            }
            String command = intent.getStringExtra(VideoStore.EXTRA_COMMAND);
            AppLog.i(MainActivity.this, TAG, "Remote command=" + command);
            if ("text".equals(command)) {
                handleRemoteText(intent.getStringExtra(VideoStore.EXTRA_TEXT));
            } else {
                handleRemoteCommand(command, intent.getStringExtra(VideoStore.EXTRA_TEXT));
            }
        }
    };

    private enum ActionType {
        SEARCH, QUEUE, RECENT, FAVORITES, RECEIVER, PLAY_URL, PLAYLIST_URL, CHANNEL_URL, NONE
    }

    private enum ScreenMode {
        HOME, LOADING, RESULTS, LIST, RECEIVER, ERROR
    }

    private static final class CardAction {
        final ActionType type;
        final String title;
        final String url;

        CardAction(ActionType type, String title, String url) {
            this.type = type;
            this.title = title;
            this.url = url;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.i(this, TAG, "onCreate action=" + (getIntent() == null ? "" : getIntent().getAction()));
        VideoStore.setAppActive(this, true);

        Intent intent = getIntent();
        if (handleLaunchIntent(intent)) {
            return;
        }
        VideoStore.savePlaybackState(this, "", "", "idle", 0, 0);
        setupCards();
        String voiceQuery = getVoiceQuery(intent);
        if (voiceQuery != null) {
            AppLog.i(this, TAG, "Voice search=" + voiceQuery);
            runSearch(voiceQuery);
            return;
        }
        if ("com.google.android.glass.action.VOICE_TRIGGER".equals(intent.getAction())) {
            showHome();
            return;
        }
        showHome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        AppLog.i(this, TAG, "onNewIntent action=" + (intent == null ? "" : intent.getAction()));
        VideoStore.setAppActive(this, true);
        setIntent(intent);
        if (handleLaunchIntent(intent)) {
            return;
        }
        String voiceQuery = getVoiceQuery(intent);
        if (voiceQuery != null) {
            setupCards();
            AppLog.i(this, TAG, "Voice search=" + voiceQuery);
            runSearch(voiceQuery);
        } else if ("com.google.android.glass.action.VOICE_TRIGGER".equals(intent.getAction())) {
            setupCards();
            showHome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        VideoStore.setAppActive(this, true);
        registerRemoteReceiver();
    }

    @Override
    protected void onPause() {
        unregisterRemoteReceiver();
        VideoStore.setAppActive(this, false);
        super.onPause();
    }

    private boolean handleLaunchIntent(Intent intent) {
        if (VideoStore.ACTION_REMOTE_COMMAND.equals(intent.getAction())) {
            String command = intent.getStringExtra(VideoStore.EXTRA_COMMAND);
            if (cardScrollView == null) {
                setupCards();
                showHome();
            }
            handleRemoteCommand(command, intent.getStringExtra(VideoStore.EXTRA_TEXT));
            return true;
        }
        String pushedUrl = intent.getStringExtra(VideoStore.EXTRA_URL);
        if (VideoStore.ACTION_OPEN_URL.equals(intent.getAction()) && pushedUrl != null) {
            openVideo(pushedUrl, "Pushed video", false, -1);
            return true;
        }
        if (VideoStore.ACTION_SEARCH.equals(intent.getAction())) {
            String text = intent.getStringExtra(VideoStore.EXTRA_TEXT);
            if (text != null && text.trim().length() > 0) {
                setupCards();
                runSearch(text.trim());
                return true;
            }
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            openIncomingUrl(intent.getData().toString());
            return true;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            String url = extractYouTubeUrl(sharedText);
            if (url != null) {
                openIncomingUrl(url);
                return true;
            }
        }
        return false;
    }

    private String getVoiceQuery(Intent intent) {
        if (intent == null || intent.getExtras() == null
                || (!intent.getExtras().containsKey(RecognizerIntent.EXTRA_RESULTS)
                && !intent.getExtras().containsKey(GLASS_EXTRA_INPUT_SPEECH))) {
            return null;
        }
        Object rawResults = intent.getExtras().containsKey(RecognizerIntent.EXTRA_RESULTS)
                ? intent.getExtras().get(RecognizerIntent.EXTRA_RESULTS)
                : intent.getExtras().get(GLASS_EXTRA_INPUT_SPEECH);
        if (rawResults instanceof ArrayList) {
            ArrayList<?> voiceResults = (ArrayList<?>) rawResults;
            if (!voiceResults.isEmpty() && voiceResults.get(0) != null) {
                String firstResult = voiceResults.get(0).toString().trim();
                if (firstResult.length() > 0) {
                    return firstResult;
                }
            }
        }
        if (rawResults instanceof String[]) {
            String[] voiceResults = (String[]) rawResults;
            if (voiceResults.length > 0 && voiceResults[0] != null
                    && voiceResults[0].trim().length() > 0) {
                return voiceResults[0].trim();
            }
        }
        if (rawResults instanceof String) {
            String singleResult = ((String) rawResults).trim();
            return singleResult.length() == 0 ? null : singleResult;
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        unregisterRemoteReceiver();
        stopVoiceSearch();
        for (AsyncTask<?, ?, ?> task : tasks) {
            task.cancel(true);
        }
        tasks.clear();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SPEECH_REQUEST && resultCode == RESULT_OK) {
            String voiceQuery = getVoiceQuery(data);
            if (voiceQuery != null) {
                AppLog.i(this, TAG, "Voice search result=" + voiceQuery);
                runSearch(voiceQuery);
            } else {
                showError("No speech result", "Try search again.");
            }
        } else if (resultCode == RESULT_OK) {
            VideoStore.Entry next = VideoStore.pollQueue(this);
            if (next != null) {
                openVideo(next.url, next.title, false, -1);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void setupCards() {
        cardScrollView = new CardScrollView(this);
        adapter = new CardAdapter();
        cardScrollView.setAdapter(adapter);
        cardScrollView.activate();
        cardScrollView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                playTap();
                if (position < 0 || position >= actions.size()) {
                    return;
                }
                handleAction(actions.get(position), position);
            }
        });
        setContentView(cardScrollView);
    }

    private void showHome() {
        screenMode = ScreenMode.HOME;
        clearCards();
        String ip = NetworkUtils.getWifiIp(this);
        addCard("Search YouTube", "Tap to speak a search", ActionType.SEARCH, null, null);
        addCard("Queue", VideoStore.getQueue(this).size() + " videos waiting", ActionType.QUEUE, null, null);
        addCard("Recent", VideoStore.getHistory(this).size() + " watched/opened videos", ActionType.RECENT, null, null);
        addCard("Favorites", VideoStore.getFavorites(this).size() + " saved videos", ActionType.FAVORITES, null, null);
        addCard("Send to Glass", receiverText(ip), ActionType.RECEIVER, null, null);
        notifyCards();
        selectFirstCard();
    }

    private String receiverText(String ip) {
        if (ip.length() == 0) {
            return "Connect Glass to Wi-Fi, then reopen this card.";
        }
        return "Open http://" + ip + ":" + GlassTubeServer.PORT;
    }

    private void handleAction(CardAction action, int position) {
        switch (action.type) {
            case SEARCH:
                startVoiceSearch();
                break;
            case QUEUE:
                showStoredList("Queue", VideoStore.getQueue(this), true);
                break;
            case RECENT:
                showStoredList("Recent", VideoStore.getHistory(this), false);
                break;
            case FAVORITES:
                showStoredList("Favorites", VideoStore.getFavorites(this), false);
                break;
            case RECEIVER:
                showReceiverSetup();
                break;
            case PLAY_URL:
                openVideo(action.url, action.title, playlistUrl.contains("list="), position);
                break;
            case PLAYLIST_URL:
                openIncomingUrl(action.url);
                break;
            case CHANNEL_URL:
                openChannel(action.url);
                break;
            default:
                break;
        }
    }

    private void startVoiceSearch() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError("Voice search unavailable", "Send a search from the remote.");
            return;
        }
        stopVoiceSearch();
        showLoading("Listening...");
        Intent recognizeIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizeIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Search YouTube");
        recognizeIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizeIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizeIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizeIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000);
        recognizeIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
        recognizeIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800);
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                showLoading("Listening...");
            }

            @Override
            public void onBeginningOfSpeech() {
                showLoading("Listening...");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                showLoading("Searching...");
            }

            @Override
            public void onError(int error) {
                if (!listeningForSearch) {
                    return;
                }
                AppLog.w(MainActivity.this, TAG, "Voice search error=" + error);
                stopVoiceSearch();
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    showError("Voice search busy", "Wait a moment, then tap again or use the remote.");
                } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    showError("Voice search offline", "Check Wi-Fi or send a search from the remote.");
                } else {
                    showError("No voice search heard", "Tap to try again or use the remote.");
                }
            }

            @Override
            public void onResults(Bundle results) {
                if (!listeningForSearch) {
                    AppLog.i(MainActivity.this, TAG, "Ignoring late voice result");
                    return;
                }
                listeningForSearch = false;
                String query = firstSpeechResult(results);
                if (query == null) {
                    stopVoiceSearch();
                    showError("No speech result", "Tap to try again or use the remote.");
                    return;
                }
                AppLog.i(MainActivity.this, TAG, "Voice search result=" + query);
                stopVoiceSearch();
                runSearch(query);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (!listeningForSearch) {
                    return;
                }
                String partial = firstSpeechResult(partialResults);
                if (partial != null) {
                    showLoading("Listening: " + partial);
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
        try {
            listeningForSearch = true;
            speechRecognizer.startListening(recognizeIntent);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (listeningForSearch && speechRecognizer != null) {
                        AppLog.w(MainActivity.this, TAG, "Voice search timed out");
                        stopVoiceSearch();
                        showError("No voice search heard", "Tap to try again or use the remote.");
                    }
                }
            }, 9000);
        } catch (Exception e) {
            AppLog.w(this, TAG, "Voice search failed to start", e);
            stopVoiceSearch();
            showError("Voice search unavailable", "Send a search from the remote.");
        }
    }

    private String firstSpeechResult(Bundle results) {
        if (results == null) {
            return null;
        }
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) {
            matches = results.getStringArrayList(RecognizerIntent.EXTRA_RESULTS);
        }
        if (matches == null || matches.isEmpty() || matches.get(0) == null) {
            return null;
        }
        String first = matches.get(0).trim();
        return first.length() == 0 ? null : first;
    }

    private void stopVoiceSearch() {
        listeningForSearch = false;
        handler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
            }
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }


    private void showReceiverSetup() {
        screenMode = ScreenMode.RECEIVER;
        clearCards();
        String ip = NetworkUtils.getWifiIp(this);
        addCard("Remote page", ip.length() == 0 ? "Connect Glass to Wi-Fi" :
                "http://" + ip + ":" + GlassTubeServer.PORT, ActionType.NONE, null, null);
        addCard(RemoteSecurity.getToken(this), "Shortcut token", ActionType.NONE, null, null);
        addCard("/open or /queue", "Add ?token=code for iOS Shortcut sends.", ActionType.NONE, null, null);
        notifyCards();
    }

    private void showStoredList(String title, List<VideoStore.Entry> entries, boolean queue) {
        screenMode = ScreenMode.LIST;
        clearCards();
        if (entries.isEmpty()) {
            addCard(title, "Nothing here yet.", ActionType.NONE, null, null);
        } else {
            for (VideoStore.Entry entry : entries) {
                addCard(entry.title, entry.url, ActionType.PLAY_URL, entry.title, entry.url);
            }
            if (queue) {
                VideoStore.clearQueue(this);
            }
        }
        notifyCards();
    }

    private void openIncomingUrl(String url) {
        if (url == null || url.length() == 0) {
            showError("Bad link", "No URL was provided.");
            return;
        }
        playlistUrl = url;
        if (url.contains("list=")) {
            setupCards();
            showLoading("Loading playlist...");
            PlaylistTask task = new PlaylistTask();
            tasks.add(task);
            task.execute(url);
        } else if (isChannelUrl(url)) {
            setupCards();
            openChannel(url);
        } else {
            openVideo(url, "YouTube video", false, -1);
        }
    }

    private boolean isChannelUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("youtube.com/channel/")
                || lower.contains("youtube.com/c/")
                || lower.contains("youtube.com/user/")
                || lower.contains("youtube.com/@");
    }

    private void handleRemoteText(String text) {
        if (text == null || text.trim().length() == 0) {
            return;
        }
        String clean = text.trim();
        String url = extractYouTubeUrl(clean);
        Toast.makeText(this, "Remote text received", Toast.LENGTH_SHORT).show();
        if (url != null) {
            openIncomingUrl(url);
        }
    }

    private void handleRemoteCommand(String command, String text) {
        if (command == null || cardScrollView == null || actions.isEmpty()) {
            return;
        }
        int selected = cardScrollView.getSelectedItemPosition();
        if (selected < 0) {
            selected = 0;
        }
        if ("up".equals(command) || "left".equals(command)) {
            int target = Math.max(0, selected - 1);
            cardScrollView.animate(target, CardScrollView.Animation.NAVIGATION);
        } else if ("down".equals(command) || "right".equals(command)) {
            int target = Math.min(actions.size() - 1, selected + 1);
            cardScrollView.animate(target, CardScrollView.Animation.NAVIGATION);
        } else if ("enter".equals(command) || "select".equals(command)) {
            activateSelectedCard(selected);
        } else if ("back".equals(command)) {
            navigateBackWithinApp();
        } else if ("home".equals(command)) {
            showHome();
        } else if ("menu".equals(command)) {
            showReceiverSetup();
        } else if ("search".equals(command)) {
            if (text != null && text.trim().length() > 0) {
                AppLog.i(this, TAG, "Remote search=" + text.trim());
                setupCards();
                runSearch(text.trim());
            } else {
                showError("Search needs text", "Use the remote Search button.");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (navigateBackWithinApp()) {
            return;
        }
        super.onBackPressed();
    }

    private boolean navigateBackWithinApp() {
        stopVoiceSearch();
        if (screenMode != ScreenMode.HOME) {
            showHome();
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            int selected = cardScrollView == null ? 0 : cardScrollView.getSelectedItemPosition();
            if (selected < 0) {
                selected = 0;
            }
            if (activateSelectedCard(selected)) {
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean activateSelectedCard(int selected) {
        if (selected < 0) {
            selected = 0;
        }
        if (selected >= actions.size()) {
            AppLog.w(this, TAG, "Card activate ignored selected=" + selected + " actions=" + actions.size());
            return false;
        }
        CardAction action = actions.get(selected);
        AppLog.i(this, TAG, "Card activate selected=" + selected + " type=" + action.type + " title=" + action.title);
        playTap();
        handleAction(action, selected);
        return true;
    }

    private void selectFirstCard() {
        if (cardScrollView == null || actions.isEmpty()) {
            return;
        }
        cardScrollView.post(new Runnable() {
            @Override
            public void run() {
                if (cardScrollView != null && !actions.isEmpty()) {
                    cardScrollView.setSelection(0);
                }
            }
        });
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRemoteReceiver() {
        if (remoteReceiverRegistered) {
            return;
        }
        registerReceiver(remoteReceiver, new IntentFilter(VideoStore.ACTION_REMOTE_COMMAND),
                "com.glass.remoteagent.permission.CONTROL", null);
        remoteReceiverRegistered = true;
    }

    private void unregisterRemoteReceiver() {
        if (!remoteReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(remoteReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        remoteReceiverRegistered = false;
    }

    private void openChannel(String url) {
        if (url == null || url.length() == 0) {
            showError("Bad channel", "No channel URL was provided.");
            return;
        }
        showLoading("Loading channel...");
        ChannelTask task = new ChannelTask();
        tasks.add(task);
        task.execute(url);
    }

    private void runSearch(String query) {
        if (query == null || query.trim().length() == 0) {
            showError("Empty search", "Try speaking a video search again.");
            return;
        }
        showLoading("Searching for " + query);
        SearchTask task = new SearchTask();
        tasks.add(task);
        task.execute(query);
    }

    private void showLoading(String text) {
        screenMode = ScreenMode.LOADING;
        clearCards();
        addCard(text, "Please wait", ActionType.NONE, null, null);
        notifyCards();
    }

    private void showError(String title, String detail) {
        screenMode = ScreenMode.ERROR;
        setupCards();
        clearCards();
        addCard(title, detail, ActionType.SEARCH, null, null);
        notifyCards();
    }

    private void openVideo(String url, String title, boolean playlist, int requestCode) {
        VideoStore.addHistory(this, title, url);
        Intent videoIntent = new Intent(this, VideoActivity.class);
        videoIntent.putExtra("url", url);
        videoIntent.putExtra("title", title);
        videoIntent.putExtra("playlist", playlist);
        startActivityForResult(videoIntent, requestCode < 0 ? 1000 : requestCode);
    }

    private void addVideoCard(StreamInfoItem item) {
        String title = item.getName();
        String url = item.getUrl();
        String uploaderName = safe(item.getUploaderName(), "Unknown channel");
        String viewCount = item.getViewCount() < 0 ? "?" : round(item.getViewCount());
        String duration = item.getDuration() == -1 ? "Live" : formatDuration(item.getDuration());
        CardBuilder card = new CardBuilder(this, CardBuilder.Layout.CAPTION)
                .setText(title)
                .setFootnote(uploaderName + " - " + viewCount + " views")
                .setTimestamp(duration);
        cards.add(card);
        actions.add(new CardAction(ActionType.PLAY_URL, title, url));
        loadImage(card, firstThumbnail(item.getThumbnails()), false);
        List<Image> avatars = item.getUploaderAvatars();
        if (avatars != null && !avatars.isEmpty()) {
            loadImage(card, avatars.get(0).getUrl(), true);
        }
    }

    private void addPlaylistCard(PlaylistInfoItem item) {
        String title = item.getName();
        String uploaderName = safe(item.getUploaderName(), "Playlist");
        String streamCount = item.getStreamCount() < 0 ? "?" : round(item.getStreamCount());
        CardBuilder card = new CardBuilder(this, CardBuilder.Layout.CAPTION)
                .setText(title)
                .setFootnote(uploaderName + " - " + streamCount + " videos");
        cards.add(card);
        actions.add(new CardAction(ActionType.PLAYLIST_URL, title, item.getUrl()));
        loadImage(card, firstThumbnail(item.getThumbnails()), false);
    }

    private void addChannelCard(ChannelInfoItem item) {
        String title = item.getName();
        String subscribers = item.getSubscriberCount() < 0 ? "?" : round(item.getSubscriberCount());
        String streams = item.getStreamCount() < 0 ? "?" : round(item.getStreamCount());
        CardBuilder card = new CardBuilder(this, CardBuilder.Layout.CAPTION)
                .setText(title)
                .setFootnote(subscribers + " subscribers - " + streams + " videos")
                .setTimestamp(item.isVerified() ? "Verified" : "Channel");
        cards.add(card);
        actions.add(new CardAction(ActionType.CHANNEL_URL, title, item.getUrl()));
        loadImage(card, firstThumbnail(item.getThumbnails()), false);
    }

    private void loadImage(final CardBuilder card, String url, final boolean icon) {
        if (url == null || url.length() == 0 || isFinishing()) {
            return;
        }
        ImageTask task = new ImageTask(card, icon);
        tasks.add(task);
        task.execute(url);
    }

    private String firstThumbnail(List<Image> images) {
        if (images == null || images.isEmpty()) {
            return "";
        }
        return images.get(0).getUrl();
    }

    private void clearCards() {
        cards.clear();
        actions.clear();
    }

    private void addCard(String text, String footnote, ActionType action, String title, String url) {
        cards.add(new CardBuilder(this, CardBuilder.Layout.TEXT)
                .setText(text)
                .setFootnote(footnote));
        actions.add(new CardAction(action, title == null ? text : title, url));
    }

    private void notifyCards() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void playTap() {
        playGlassSound(Sounds.TAP);
    }

    @SuppressLint("WrongConstant")
    private void playGlassSound(int sound) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.playSoundEffect(sound);
        }
    }

    private String extractYouTubeUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(https?://\\S+|vnd\\.youtube:\\S+)").matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.contains("youtube.com") || lower.contains("youtu.be") || lower.startsWith("vnd.youtube:")) {
                return candidate;
            }
        }
        return null;
    }

    private String safe(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }

    private String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds);
    }

    public static String round(long val) {
        if (val >= 1000000) {
            return String.format(Locale.ROOT, "%.1fM", val / 1000000.0);
        } else if (val >= 1000) {
            return String.format(Locale.ROOT, "%.1fK", val / 1000.0);
        }
        return Long.toString(val);
    }

    private final class SearchTask extends AsyncTask<String, Void, List<InfoItem>> {
        private Exception error;

        @Override
        protected List<InfoItem> doInBackground(String... query) {
            try {
                NewPipe.init(DownloaderTestImpl.getInstance());
                StreamingService youtubeService = ServiceList.YouTube;
                SearchInfo searchInfo = SearchInfo.getInfo(youtubeService,
                        youtubeService.getSearchQHFactory().fromQuery(query[0]));
                return searchInfo.getRelatedItems();
            } catch (Exception e) {
                error = e;
                AppLog.e(MainActivity.this, TAG, "Search failed", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<InfoItem> results) {
            tasks.remove(this);
            showResults(results, error == null ? "No results" : "Search failed");
        }
    }

    private final class PlaylistTask extends AsyncTask<String, Void, List<StreamInfoItem>> {
        private Exception error;

        @Override
        protected List<StreamInfoItem> doInBackground(String... urls) {
            try {
                NewPipe.init(DownloaderTestImpl.getInstance());
                return PlaylistInfo.getInfo(urls[0]).getRelatedItems();
            } catch (Exception e) {
                error = e;
                AppLog.e(MainActivity.this, TAG, "Playlist failed", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<StreamInfoItem> results) {
            tasks.remove(this);
            if (results == null || results.isEmpty()) {
                showError(error == null ? "Empty playlist" : "Playlist failed", "Tap to search instead.");
                return;
            }
            clearCards();
            for (StreamInfoItem item : results) {
                addVideoCard(item);
            }
            notifyCards();
            if (playlistUrl.contains("index=")) {
                Matcher matcher = Pattern.compile("[?&]index=(\\d+)").matcher(playlistUrl);
                int index = matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
                if (index > 0 && index <= cards.size()) {
                    cardScrollView.animate(index - 1, CardScrollView.Animation.NAVIGATION);
                }
            }
        }
    }

    private final class ChannelTask extends AsyncTask<String, Void, List<InfoItem>> {
        private Exception error;
        private String channelName = "Channel";

        @Override
        protected List<InfoItem> doInBackground(String... urls) {
            try {
                NewPipe.init(DownloaderTestImpl.getInstance());
                StreamingService youtubeService = ServiceList.YouTube;
                ChannelInfo channelInfo = ChannelInfo.getInfo(youtubeService, urls[0]);
                if (channelInfo.getName() != null && channelInfo.getName().length() > 0) {
                    channelName = channelInfo.getName();
                }
                List<ListLinkHandler> tabs = channelInfo.getTabs();
                if (tabs == null || tabs.isEmpty()) {
                    return new ArrayList<InfoItem>();
                }
                ListLinkHandler selected = tabs.get(0);
                for (ListLinkHandler tab : tabs) {
                    List<String> filters = tab.getContentFilters();
                    if (filters != null && filters.toString().toLowerCase(Locale.ROOT).contains("video")) {
                        selected = tab;
                        break;
                    }
                }
                return ChannelTabInfo.getInfo(youtubeService, selected).getRelatedItems();
            } catch (Exception e) {
                error = e;
                AppLog.e(MainActivity.this, TAG, "Channel failed", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<InfoItem> results) {
            tasks.remove(this);
            showResults(results, error == null ? channelName : "Channel failed");
        }
    }

    private final class ImageTask extends AsyncTask<String, Void, Bitmap> {
        private final CardBuilder card;
        private final boolean icon;

        ImageTask(CardBuilder card, boolean icon) {
            this.card = card;
            this.icon = icon;
        }

        @Override
        protected Bitmap doInBackground(String... urls) {
            HttpURLConnection connection = null;
            InputStream stream = null;
            try {
                URL imageUrl = new URL(urls[0]);
                connection = (HttpURLConnection) imageUrl.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setInstanceFollowRedirects(true);
                stream = connection.getInputStream();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                options.inSampleSize = icon ? 2 : 1;
                return BitmapFactory.decodeStream(stream, null, options);
            } catch (Exception e) {
                AppLog.w(MainActivity.this, TAG, "Image load failed");
                return null;
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (Exception ignored) {
                    }
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            tasks.remove(this);
            if (bitmap == null || isFinishing()) {
                return;
            }
            if (icon) {
                card.setIcon(bitmap);
            } else {
                card.addImage(bitmap);
            }
            notifyCards();
        }
    }

    private void showResults(List<InfoItem> results, String emptyTitle) {
        screenMode = ScreenMode.RESULTS;
        clearCards();
        if (results == null || results.isEmpty()) {
            addCard(emptyTitle, "Tap to search again.", ActionType.SEARCH, null, null);
        } else {
            for (InfoItem item : results) {
                if (item instanceof StreamInfoItem) {
                    addVideoCard((StreamInfoItem) item);
                } else if (item instanceof PlaylistInfoItem) {
                    addPlaylistCard((PlaylistInfoItem) item);
                } else if (item instanceof ChannelInfoItem) {
                    addChannelCard((ChannelInfoItem) item);
                }
            }
            if (cards.isEmpty()) {
            addCard("No playable results", "Try a different search.", ActionType.SEARCH, null, null);
            }
        }
        notifyCards();
        selectFirstCard();
    }

    private final class CardAdapter extends CardScrollAdapter {
        @Override
        public int getPosition(Object item) {
            return cards.indexOf(item);
        }

        @Override
        public int getCount() {
            return cards.size();
        }

        @Override
        public Object getItem(int position) {
            return cards.get(position);
        }

        @Override
        public int getViewTypeCount() {
            return CardBuilder.getViewTypeCount();
        }

        @Override
        public int getItemViewType(int position) {
            return cards.get(position).getItemViewType();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = cards.get(position).getView(convertView, parent);
            centerCardText(view);
            return view;
        }
    }

    private void centerCardText(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setGravity(Gravity.CENTER);
            textView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                centerCardText(group.getChildAt(i));
            }
        }
    }
}
