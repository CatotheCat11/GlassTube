package com.catothecat.glasstube;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.touchpad.Gesture;
import com.google.android.glass.touchpad.GestureDetector;
import com.google.android.glass.widget.Slider;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.android.exoplayer2.source.TrackGroupArray;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class VideoActivity extends Activity {
    private ExoPlayer player;
    private PlayerView playerView;
    private GestureDetector mGestureDetector;
    private Slider mSlider;
    private Slider.Indeterminate mIndeterminate;
    private SubtitleView subtitleView;
    private boolean subtitlesEnabled = false;
    private static final String TAG = "VideoActivity";
    private static final OkHttpClient HLS_CLIENT = new OkHttpClient();
    String videoUrl = "";
    String videoTitle = "YouTube video";
    Boolean playlist;
    AsyncTask<Void, Void, StreamInfo> task;
    private String backgroundResolvedHlsUrl;
    private ImageView captionIcon;
    private LinearLayout controlBar;
    private boolean controlsVisible = true;
    private boolean twoFingerScrubbing = false;
    private boolean volumeMode = false;
    private float twoFingerStartX = 0;
    private long twoFingerStartPositionMs = 0;
    private DefaultTrackSelector trackSelector;
    private boolean audioOverrideApplied = false;
    private int tapCount = 0;
    private final Handler stateHandler = new Handler();
    private final Runnable singleTapRunnable = new Runnable() {
        @Override
        public void run() {
            tapCount = 0;
            togglePlayPause();
        }
    };
    private final Runnable stateTicker = new Runnable() {
        @Override
        public void run() {
            if (player == null) {
                return;
            }
            savePlayerState(stateToString(player.getPlaybackState()));
            stateHandler.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver remoteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VideoStore.ACTION_REMOTE_COMMAND.equals(intent.getAction())) {
                handleRemoteCommand(intent.getStringExtra(VideoStore.EXTRA_COMMAND));
            }
        }
    };
    private final Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            if (controlBar != null) {
                controlBar.setVisibility(View.GONE);
                controlsVisible = false;
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        VideoStore.setAppActive(this, true);
        setContentView(R.layout.video_view);
        mGestureDetector = createGestureDetector(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        registerRemoteReceiver();
        playerView = findViewById(R.id.playerView);
        playerView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
        mSlider = Slider.from(playerView);
        mIndeterminate = mSlider.startIndeterminate();
        Intent intent = getIntent();
        videoUrl = intent.getStringExtra("url");
        videoTitle = intent.getStringExtra("title");
        if (videoTitle == null || videoTitle.length() == 0) {
            videoTitle = "YouTube video";
        }
        playlist = intent.getBooleanExtra("playlist", false);
        videoUrl = normalizeYouTubeUrl(videoUrl);
        AppLog.i(this, TAG, "Playing video=" + videoUrl);
        VideoStore.savePlaybackState(this, videoTitle, videoUrl, "loading", 0, 0);
        task = new FetchVideoStreamsTask().execute();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRemoteReceiver() {
        registerReceiver(remoteReceiver, new IntentFilter(VideoStore.ACTION_REMOTE_COMMAND),
                "com.glass.remoteagent.permission.CONTROL", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        VideoStore.setAppActive(this, true);
        if (playerView != null) {
            playerView.onResume();
        }
    }

    @Override
    protected void onPause() {
        VideoStore.setAppActive(this, false);
        if (playerView != null) {
            playerView.onPause();
        }
        super.onPause();
    }

    public class FetchVideoStreamsTask extends AsyncTask<Void, Void, StreamInfo> {

        @Override
        protected StreamInfo doInBackground(Void... voids) {
            try {
                NewPipe.init(DownloaderTestImpl.getInstance());
                StreamingService youtubeService = ServiceList.YouTube;

                Exception lastError = null;
                for (String candidate : extractorCandidates(videoUrl)) {
                    try {
                        AppLog.d(VideoActivity.this, TAG, "Fetching stream info=" + candidate);
                        StreamInfo info = StreamInfo.getInfo(youtubeService, candidate);
                        backgroundResolvedHlsUrl = null;
                        if (info != null
                                && StreamType.LIVE_STREAM.equals(info.getStreamType())
                                && info.getHlsUrl() != null
                                && info.getHlsUrl().length() > 0) {
                            backgroundResolvedHlsUrl = chooseGlassFriendlyHlsVariant(info.getHlsUrl());
                        }
                        return info;
                    } catch (Exception e) {
                        lastError = e;
                        AppLog.w(VideoActivity.this, TAG, "Stream info candidate failed=" + candidate, e);
                    }
                }
                if (lastError instanceof IOException) {
                    throw (IOException) lastError;
                }
                if (lastError instanceof ExtractionException) {
                    throw (ExtractionException) lastError;
                }
                throw new ExtractionException("No stream candidate worked");
            } catch (Exception e) {
                AppLog.e(VideoActivity.this, TAG, "Error fetching stream info=" + videoUrl, e);
                playGlassSound(Sounds.ERROR);
                return null;
            }
        }

        @Override
        protected void onPostExecute(StreamInfo streamInfo) {
            if (streamInfo == null) {
                AppLog.e(VideoActivity.this, TAG, "Failed to fetch stream info");
                VideoStore.savePlaybackState(VideoActivity.this, videoTitle, videoUrl, "error", 0, 0);
                playError();
                finish();
                return;
            }
            String videoStreamUrl = null;
            boolean hlsStream = false;

            try {
                if (streamInfo.getName() != null && streamInfo.getName().length() > 0) {
                    videoTitle = streamInfo.getName();
                }
                VideoStore.addHistory(VideoActivity.this, videoTitle, videoUrl);
                // Check if it's a live stream
                if (streamInfo.getStreamType().equals(StreamType.LIVE_STREAM)) {
                    // For live streams, use the HLS manifest URL if available
                    String manifestUrl = streamInfo.getHlsUrl();
                    if (manifestUrl != null && !manifestUrl.isEmpty()) {
                        AppLog.i(VideoActivity.this, TAG, "Playing HLS stream=" + manifestUrl);
                        videoStreamUrl = backgroundResolvedHlsUrl == null ? manifestUrl : backgroundResolvedHlsUrl;
                        hlsStream = true;
                    } else {
                        AppLog.w(VideoActivity.this, TAG, "Live stream has no HLS URL; trying regular streams");
                    }
                }

                // Fall back to regular video streams if not live or HLS not available
                if (videoStreamUrl == null) {
                    List<VideoStream> videoStreams = streamInfo.getVideoStreams();
                    videoStreamUrl = chooseBestStream(videoStreams);
                }
                if (videoStreamUrl != null) {
                    // Log the total number of available streams
                    AppLog.i(VideoActivity.this, TAG, "Selected stream=" + videoStreamUrl);
                } else {
                    AppLog.e(VideoActivity.this, TAG, "No video streams available");
                    VideoStore.savePlaybackState(VideoActivity.this, videoTitle, videoUrl, "unsupported", 0, 0);
                    playError();
                    finish();
                    return;
                }
                // Build the MediaItem with subtitles
                MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
                        .setUri(videoStreamUrl);
                if (hlsStream) {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
                }
                List<SubtitlesStream> subtitleStreams = streamInfo.getSubtitles();
                if (subtitleStreams != null && !subtitleStreams.isEmpty()) {
                    for (SubtitlesStream subtitle : subtitleStreams) {
                        if (subtitle.getUrl() == null || subtitle.getUrl().length() == 0) {
                            continue;
                        }
                        MediaItem.SubtitleConfiguration subtitleConfig = new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.getUrl()))
                                .setLanguage(subtitle.getLanguageTag())
                                .setMimeType(subtitle.getFormat().getMimeType())
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build();
                        mediaItemBuilder.setSubtitleConfigurations(List.of(subtitleConfig));
                        break; // For now, just add the first subtitle track
                    }
                }
                playVideo(mediaItemBuilder.build(), false);
            } catch (Exception e) {
                AppLog.e(VideoActivity.this, TAG, "Error processing stream info", e);
                VideoStore.savePlaybackState(VideoActivity.this, videoTitle, videoUrl, "error", 0, 0);
                playError();
                finish();
            }
        }
    }

    private String chooseBestStream(List<VideoStream> videoStreams) {
        if (videoStreams == null || videoStreams.isEmpty()) {
            return null;
        }
        VideoStream videoOnlyFallback = null;
        VideoStream best = null;
        int bestScore = Integer.MAX_VALUE;
        for (VideoStream stream : videoStreams) {
            if (stream == null || stream.getUrl() == null || stream.getUrl().length() == 0) {
                continue;
            }
            if (stream.isVideoOnly()) {
                if (videoOnlyFallback == null) {
                    videoOnlyFallback = stream;
                }
                continue;
            }
            int height = parseHeight(stream.getResolution());
            int score;
            if (height <= 0) {
                score = 10000;
            } else if (height <= 360) {
                score = 360 - height;
            } else if (height <= 720) {
                score = 1000 + height;
            } else {
                score = 5000 + height;
            }
            if (score < bestScore) {
                bestScore = score;
                best = stream;
            }
        }
        if (best != null) {
            AppLog.i(this, TAG, "Selected muxed stream " + best.getResolution() + " codec " + best.getCodec());
            return best.getUrl();
        }
        if (videoOnlyFallback != null) {
            AppLog.w(this, TAG, "Only video-only streams were available; refusing silent playback");
        }
        return null;
    }

    private int parseHeight(String resolution) {
        if (resolution == null) {
            return -1;
        }
        String digits = resolution.replaceAll("[^0-9]", "");
        if (digits.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String chooseGlassFriendlyHlsVariant(String manifestUrl) {
        try {
            Request request = new Request.Builder().url(manifestUrl).build();
            Response response = HLS_CLIENT.newCall(request).execute();
            try {
                if (!response.isSuccessful() || response.body() == null) {
                    return manifestUrl;
                }
                String manifest = response.body().string();
                if (manifestHasSeparateAudio(manifest)) {
                    AppLog.d(this, TAG, "Using HLS master manifest to preserve audio rendition");
                    return manifestUrl;
                }
                String[] lines = manifest.split("\\r?\\n");
                String bestUrl = null;
                int bestScore = Integer.MAX_VALUE;
                for (int i = 0; i < lines.length - 1; i++) {
                    String info = lines[i];
                    if (!info.startsWith("#EXT-X-STREAM-INF")) {
                        continue;
                    }
                    String candidate = lines[i + 1].trim();
                    if (candidate.length() == 0 || candidate.startsWith("#")) {
                        continue;
                    }
                    int score = hlsScore(hlsHeight(info), hlsInt(info, "BANDWIDTH="),
                            hlsFrameRate(info), hlsHasAudioCodec(info));
                    if (score < bestScore) {
                        bestScore = score;
                        bestUrl = absolutize(manifestUrl, candidate);
                    }
                }
                if (bestUrl != null) {
                    AppLog.d(this, TAG, "Selected HLS variant=" + bestUrl);
                    return bestUrl;
                }
            } finally {
                response.close();
            }
        } catch (Exception e) {
            AppLog.w(this, TAG, "Unable to select HLS variant; using master", e);
        }
        return manifestUrl;
    }

    private boolean manifestHasSeparateAudio(String manifest) {
        if (manifest == null) {
            return false;
        }
        String upper = manifest.toUpperCase(Locale.ROOT);
        return upper.contains("#EXT-X-MEDIA") && upper.contains("TYPE=AUDIO")
                || upper.contains("AUDIO=");
    }

    private int hlsScore(int height, int bandwidth, int frameRate, boolean hasAudioCodec) {
        int score = bandwidth <= 0 ? 10000000 : bandwidth;
        if (!hasAudioCodec) {
            score += 20000000;
        }
        if (height > 360) {
            score += 10000000 + height * 1000;
        } else if (height > 0) {
            score += Math.abs(360 - height) * 1000;
        }
        if (frameRate > 30) {
            score += 5000000;
        }
        return score;
    }

    private boolean hlsHasAudioCodec(String info) {
        int marker = info.indexOf("CODECS=");
        if (marker < 0) {
            return true;
        }
        String lower = info.substring(marker).toLowerCase(Locale.ROOT);
        return lower.contains("mp4a") || lower.contains("aac") || lower.contains("ac-3")
                || lower.contains("ec-3");
    }

    private int hlsHeight(String info) {
        int marker = info.indexOf("RESOLUTION=");
        if (marker < 0) {
            return -1;
        }
        int start = marker + "RESOLUTION=".length();
        int end = info.indexOf(',', start);
        String resolution = end < 0 ? info.substring(start) : info.substring(start, end);
        int x = resolution.indexOf('x');
        if (x < 0 || x + 1 >= resolution.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(resolution.substring(x + 1).replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private int hlsInt(String info, String key) {
        int marker = info.indexOf(key);
        if (marker < 0) {
            return -1;
        }
        int start = marker + key.length();
        int end = info.indexOf(',', start);
        String raw = end < 0 ? info.substring(start) : info.substring(start, end);
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private int hlsFrameRate(String info) {
        int marker = info.indexOf("FRAME-RATE=");
        if (marker < 0) {
            return -1;
        }
        int start = marker + "FRAME-RATE=".length();
        int end = info.indexOf(',', start);
        String raw = end < 0 ? info.substring(start) : info.substring(start, end);
        try {
            return Math.round(Float.parseFloat(raw));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String absolutize(String base, String candidate) throws Exception {
        return new URL(new URL(base), candidate).toString();
    }

    private void playVideo(MediaItem mediaItem, Boolean sphereView) {
        if (sphereView) {
            setContentView(R.layout.video_view_360);
            mGestureDetector = createGestureDetector(this);
            playerView = findViewById(R.id.playerView);
        }
        captionIcon = playerView.findViewById(R.id.exo_caption_icon);
        controlBar = playerView.findViewById(R.id.control_bar);

        LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        2000,
                        5000,
                        1000,
                        1000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .setMaxVideoSize(640, 360)
                        .setMaxAudioChannelCount(2)
                        .setPreferredAudioLanguage("en")
                        .build());

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();

        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setReleaseTimeoutMs(5000)
                // Limit video resolution to match Glass display
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                .build();
        player.setAudioAttributes(audioAttributes, true);
        player.setVolume(1.0f);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == ExoPlayer.STATE_READY || state == ExoPlayer.STATE_ENDED) {
                    hideBufferingIndicator();
                }
                if (state == ExoPlayer.STATE_ENDED && playlist) {
                    setResult(RESULT_OK);
                    finish();
                } else if (state == ExoPlayer.STATE_ENDED) {
                    VideoStore.Entry next = VideoStore.pollQueue(VideoActivity.this);
                    if (next != null) {
                        Intent intent = new Intent(VideoActivity.this, VideoActivity.class);
                        intent.putExtra("url", next.url);
                        intent.putExtra("title", next.title);
                        startActivity(intent);
                        finish();
                    }
                }
                savePlayerState(stateToString(state));
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    hideBufferingIndicator();
                }
                savePlayerState(isPlaying ? "playing" : "paused");
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                AppLog.e(VideoActivity.this, TAG, "Playback error", error);
                savePlayerState("error");
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                logAudioTracks(tracks);
                forceAacLcAudioTrack();
            }
        });

        playerView.setPlayer(player);
        subtitleView = playerView.getSubtitleView();
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        subtitleView.setVisibility(View.GONE);
        playerView.setControllerShowTimeoutMs(0);
        playerView.setControllerHideOnTouch(false);
        showControlsTemporarily();
        player.setMediaItem(mediaItem);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.prepare();
        player.play();
        playerView.onResume();
        stateHandler.removeCallbacks(stateTicker);
        stateHandler.post(stateTicker);
    }

    private void hideBufferingIndicator() {
        if (mIndeterminate != null) {
            mIndeterminate.hide();
            mIndeterminate = null;
        }
    }

    private void forceAacLcAudioTrack() {
        if (audioOverrideApplied || trackSelector == null) {
            return;
        }
        MappingTrackSelector.MappedTrackInfo mapped = trackSelector.getCurrentMappedTrackInfo();
        if (mapped == null) {
            return;
        }
        for (int rendererIndex = 0; rendererIndex < mapped.getRendererCount(); rendererIndex++) {
            if (mapped.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            TrackGroupArray groups = mapped.getTrackGroups(rendererIndex);
            int bestGroupIndex = -1;
            int bestTrackIndex = -1;
            int bestScore = Integer.MAX_VALUE;
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                for (int trackIndex = 0; trackIndex < groups.get(groupIndex).length; trackIndex++) {
                    Format format = groups.get(groupIndex).getFormat(trackIndex);
                    String codecs = format.codecs == null ? "" : format.codecs.toLowerCase(Locale.ROOT);
                    if (!codecs.contains("mp4a.40.2")) {
                        continue;
                    }
                    int support = mapped.getTrackSupport(rendererIndex, groupIndex, trackIndex);
                    if (support == C.FORMAT_UNSUPPORTED_TYPE
                            || support == C.FORMAT_UNSUPPORTED_SUBTYPE
                            || support == C.FORMAT_UNSUPPORTED_DRM) {
                        continue;
                    }
                    int channelCount = format.channelCount <= 0 ? 2 : format.channelCount;
                    int bitrate = format.bitrate <= 0 ? 128000 : format.bitrate;
                    int score = Math.abs(channelCount - 1) * 1000000 + bitrate;
                    if (channelCount > 2) {
                        score += 5000000;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        bestGroupIndex = groupIndex;
                        bestTrackIndex = trackIndex;
                    }
                }
            }
            if (bestGroupIndex >= 0) {
                Format format = groups.get(bestGroupIndex).getFormat(bestTrackIndex);
                trackSelector.setParameters(
                        trackSelector.buildUponParameters()
                                .setMaxAudioChannelCount(2)
                                .setSelectionOverride(
                                        rendererIndex,
                                        groups,
                                        new DefaultTrackSelector.SelectionOverride(bestGroupIndex, bestTrackIndex))
                                .build());
                audioOverrideApplied = true;
                AppLog.i(this, TAG, "Forced bone-conduction friendly AAC-LC audio track renderer=" + rendererIndex
                        + " group=" + bestGroupIndex + " track=" + bestTrackIndex
                        + " channels=" + format.channelCount
                        + " bitrate=" + format.bitrate
                        + " mime=" + format.sampleMimeType + " codecs=" + format.codecs);
                return;
            }
        }
    }

    private void logAudioTracks(Tracks tracks) {
        if (tracks == null) {
            return;
        }
        int audioGroups = 0;
        int selectedAudio = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            audioGroups++;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                boolean selected = group.isTrackSelected(i);
                if (selected) {
                    selectedAudio++;
                }
                AppLog.d(this, TAG, "Audio track " + i
                        + " selected=" + selected
                        + " supported=" + group.isTrackSupported(i)
                        + " mime=" + format.sampleMimeType
                        + " codecs=" + format.codecs
                        + " channels=" + format.channelCount
                        + " bitrate=" + format.bitrate
                        + " lang=" + format.language);
            }
        }
        AppLog.d(this, TAG, "Audio track groups=" + audioGroups + " selected=" + selectedAudio);
    }
    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(remoteReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (controlBar != null) {
            controlBar.removeCallbacks(hideControlsRunnable);
        }
        if (playerView != null) {
            playerView.removeCallbacks(singleTapRunnable);
        }
        stateHandler.removeCallbacks(stateTicker);
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        if (player != null) {
            VideoStore.savePlaybackState(this, videoTitle, videoUrl, "idle", 0, 0);
            player.release();
            player = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        VideoStore.setAppActive(this, false);
        super.onDestroy();
    }
    private GestureDetector createGestureDetector(Context context) {
        GestureDetector gestureDetector = new GestureDetector(context);
        //Create a base listener for generic gestures
        gestureDetector.setBaseListener( new GestureDetector.BaseListener() {
            @Override
            public boolean onGesture(Gesture gesture) {
                if (controlBar != null) {
                    if (!controlsVisible) {
                        controlBar.setVisibility(View.VISIBLE);
                        controlsVisible = true;
                        // Auto-hide after delay
                        showControlsTemporarily();
                    }
                }
                if (player == null) {
                    return false;
                }
                if (gesture == Gesture.TAP) {
                    handleTapGesture();
                    return true;
                } else if (gesture == Gesture.SWIPE_RIGHT) {
                    if (volumeMode) {
                        adjustPlaybackVolume(AudioManager.ADJUST_RAISE);
                        return true;
                    }
                    player.seekForward();
                    return true;
                } else if (gesture == Gesture.SWIPE_LEFT) {
                    if (volumeMode) {
                        adjustPlaybackVolume(AudioManager.ADJUST_LOWER);
                        return true;
                    }
                    player.seekBack();
                    return true;
                } else if (gesture == Gesture.SWIPE_DOWN) {
                    returnToGlassTube();
                    return true;
                } else if (gesture == Gesture.TWO_SWIPE_RIGHT) {
                    player.seekTo(clamp(player.getCurrentPosition() + 60000, 0, player.getDuration()));
                    showControlsTemporarily();
                    return true;
                } else if (gesture == Gesture.TWO_SWIPE_LEFT) {
                    player.seekTo(clamp(player.getCurrentPosition() - 60000, 0, player.getDuration()));
                    showControlsTemporarily();
                    return true;
                } else if (gesture == Gesture.LONG_PRESS) {
                    toggleFavorite();
                    return true;
                } else if (gesture ==  Gesture.TWO_TAP) {
                    toggleCaptions();
                    return true;
                }
                return false;
            }
        });
        return gestureDetector;
    }

    private void handleTapGesture() {
        if (playerView == null) {
            togglePlayPause();
            return;
        }
        tapCount++;
        playerView.removeCallbacks(singleTapRunnable);
        if (tapCount >= 3) {
            tapCount = 0;
            volumeMode = !volumeMode;
            showVolumeMode();
            return;
        }
        playerView.postDelayed(singleTapRunnable, 280);
    }

    private void togglePlayPause() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            if (playerView != null) {
                playerView.onPause();
            }
        } else {
            player.play();
            if (playerView != null) {
                playerView.onResume();
            }
        }
    }

    private void showVolumeMode() {
        String text = volumeMode ? "Volume: swipe to adjust" : "Volume dismissed";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        playGlassSound(Sounds.TAP);
        showControlsTemporarily();
    }

    private void adjustPlaybackVolume(int direction) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        }
        showControlsTemporarily();
    }

    private void playError() {
        playGlassSound(Sounds.ERROR);
    }

    private void handleRemoteCommand(String command) {
        if (command == null || player == null) {
            return;
        }
        if ("play_pause".equals(command) || "play".equals(command) || "pause".equals(command)) {
            if ("play".equals(command) || !player.isPlaying()) {
                player.play();
            } else {
                player.pause();
            }
        } else if ("seek_forward".equals(command)) {
            seekBy(10000);
        } else if ("seek_back".equals(command)) {
            seekBy(-10000);
        } else if ("seek_forward_60".equals(command)) {
            seekBy(60000);
        } else if ("seek_back_60".equals(command)) {
            seekBy(-60000);
        } else if (command.startsWith("seek_to:")) {
            seekTo(command.substring("seek_to:".length()));
        } else if ("next".equals(command)) {
            playNextQueuedOrFinish();
        } else if ("captions".equals(command)) {
            toggleCaptions();
        } else if ("favorite".equals(command)) {
            toggleFavorite();
        } else if ("home".equals(command)) {
            returnToGlassTube();
        } else if ("exit".equals(command) || "back".equals(command)) {
            returnToGlassTube();
        }
        showControlsTemporarily();
        savePlayerState(player.isPlaying() ? "playing" : "paused");
    }

    private void returnToGlassTube() {
        if (isTaskRoot()) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }
        finish();
    }

    private void seekBy(long deltaMs) {
        long duration = player.getDuration();
        long max = duration <= 0 ? Long.MAX_VALUE : duration;
        player.seekTo(clamp(player.getCurrentPosition() + deltaMs, 0, max));
    }

    private void seekTo(String millisText) {
        try {
            long duration = player.getDuration();
            if (duration <= 0 || duration == C.TIME_UNSET) {
                return;
            }
            long target = Long.parseLong(millisText);
            player.seekTo(clamp(target, 0, duration));
        } catch (NumberFormatException ignored) {
        }
    }

    private void playNextQueuedOrFinish() {
        VideoStore.Entry next = VideoStore.pollQueue(this);
        if (next == null) {
            finish();
            return;
        }
        Intent intent = new Intent(this, VideoActivity.class);
        intent.putExtra("url", next.url);
        intent.putExtra("title", next.title);
        startActivity(intent);
        finish();
    }

    private void toggleFavorite() {
        if (VideoStore.isFavorite(this, videoUrl)) {
            VideoStore.removeFavorite(this, videoUrl);
        } else {
            VideoStore.addFavorite(this, videoTitle, videoUrl);
        }
        playGlassSound(Sounds.TAP);
    }

    private void toggleCaptions() {
        if (subtitleView == null) {
            return;
        }
        subtitlesEnabled = !subtitlesEnabled;
        subtitleView.setVisibility(subtitlesEnabled ? View.VISIBLE : View.GONE);
        if (captionIcon != null) {
            captionIcon.setImageResource(subtitlesEnabled ?
                    R.drawable.cc_enabled : R.drawable.cc_disabled);
        }
        AppLog.d(this, TAG, "Subtitles " + (subtitlesEnabled ? "enabled" : "disabled"));
    }

    private void savePlayerState(String state) {
        long position = player == null ? 0 : player.getCurrentPosition();
        long duration = player == null || player.getDuration() < 0 ? 0 : player.getDuration();
        VideoStore.savePlaybackState(this, videoTitle, videoUrl, state, position, duration);
    }

    private String stateToString(int state) {
        if (state == ExoPlayer.STATE_BUFFERING) {
            return "buffering";
        }
        if (state == ExoPlayer.STATE_READY) {
            return player != null && player.isPlaying() ? "playing" : "paused";
        }
        if (state == ExoPlayer.STATE_ENDED) {
            return "ended";
        }
        return "idle";
    }

    /* Send generic motion events to the gesture detector */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mGestureDetector != null) {
            return mGestureDetector.onMotionEvent(event);
        }
        return false;
    }

    /* Forward touch events to the gesture detector */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleTwoFingerScrub(event)) {
            return true;
        }
        return onGenericMotionEvent(event);
    }

    private boolean handleTwoFingerScrub(MotionEvent event) {
        if (player == null || player.getDuration() <= 0) {
            return false;
        }
        int pointerCount = event.getPointerCount();
        int action = event.getActionMasked();
        if (pointerCount >= 2 && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_DOWN)) {
            twoFingerScrubbing = true;
            twoFingerStartX = averageX(event);
            twoFingerStartPositionMs = player.getCurrentPosition();
            showControlsTemporarily();
            return true;
        }
        if (twoFingerScrubbing && pointerCount >= 2 && action == MotionEvent.ACTION_MOVE) {
            float delta = averageX(event) - twoFingerStartX;
            long seekDeltaMs = (long) (delta * 250);
            long target = clamp(twoFingerStartPositionMs + seekDeltaMs, 0, player.getDuration());
            player.seekTo(target);
            showControlsTemporarily();
            return true;
        }
        if (twoFingerScrubbing && (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            twoFingerScrubbing = false;
            return true;
        }
        return false;
    }

    private float averageX(MotionEvent event) {
        float total = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            total += event.getX(i);
        }
        return total / event.getPointerCount();
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private void showControlsTemporarily() {
        if (controlBar == null) {
            return;
        }
        controlBar.setVisibility(View.VISIBLE);
        controlsVisible = true;
        controlBar.removeCallbacks(hideControlsRunnable);
        controlBar.postDelayed(hideControlsRunnable, 3000);
    }

    private List<String> extractorCandidates(String rawUrl) {
        ArrayList<String> candidates = new ArrayList<String>();
        addCandidate(candidates, rawUrl);
        String videoId = extractYouTubeId(rawUrl);
        if (videoId != null) {
            addCandidate(candidates, "https://www.youtube.com/watch?v=" + videoId);
            addCandidate(candidates, "https://m.youtube.com/watch?v=" + videoId);
            addCandidate(candidates, "https://youtu.be/" + videoId);
        }
        return candidates;
    }

    private void addCandidate(List<String> candidates, String value) {
        if (value == null || value.length() == 0 || candidates.contains(value)) {
            return;
        }
        candidates.add(value);
    }

    private String normalizeYouTubeUrl(String rawUrl) {
        String videoId = extractYouTubeId(rawUrl);
        if (videoId == null) {
            return rawUrl == null ? "" : rawUrl;
        }
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    private String extractYouTubeId(String rawUrl) {
        if (rawUrl == null || rawUrl.length() == 0) {
            return null;
        }
        Uri uri = Uri.parse(rawUrl);
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith("youtu.be")) {
            String path = uri.getPath();
            return cleanVideoId(path == null ? null : path.replace("/", ""));
        }
        String queryId = uri.getQueryParameter("v");
        if (queryId != null) {
            return cleanVideoId(queryId);
        }
        List<String> segments = uri.getPathSegments();
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            if (("live".equals(segment) || "shorts".equals(segment) || "embed".equals(segment))
                    && i + 1 < segments.size()) {
                return cleanVideoId(segments.get(i + 1));
            }
        }
        return null;
    }

    private String cleanVideoId(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        int cut = clean.indexOf('?');
        if (cut >= 0) {
            clean = clean.substring(0, cut);
        }
        cut = clean.indexOf('&');
        if (cut >= 0) {
            clean = clean.substring(0, cut);
        }
        return clean.length() == 11 ? clean : null;
    }

    @SuppressLint("WrongConstant")
    private void playGlassSound(int sound) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.playSoundEffect(sound);
        }
    }
}
