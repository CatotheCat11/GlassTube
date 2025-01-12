package com.catothecat.glasstube;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.glass.touchpad.Gesture;
import com.google.android.glass.touchpad.GestureDetector;
import com.google.android.glass.widget.Slider;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.List;

public class VideoActivity extends Activity {
    private ExoPlayer player;
    private PlayerView playerView;
    private GestureDetector mGestureDetector;
    private Slider mSlider;
    private Slider.Indeterminate mIndeterminate;
    private SubtitleView subtitleView;
    private boolean subtitlesEnabled = false;
    private static final String TAG = "VideoActivity";
    String videoUrl = "";
    AsyncTask<Void, Void, StreamInfo> task;
    private ImageView captionIcon;
    private LinearLayout controlBar;
    private boolean controlsVisible = true;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.video_view);
        mGestureDetector = createGestureDetector(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );
        playerView = findViewById(R.id.playerView);
        mSlider = Slider.from(playerView);
        mIndeterminate = mSlider.startIndeterminate();
        videoUrl = getIntent().getStringExtra("url");
        if (videoUrl == null) {
            // No URL passed as extra; check for app picker intent
            Intent intent = getIntent();
            if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                Uri data = intent.getData();
                if (data != null) {
                    videoUrl = data.toString();
                }
            }
        }
        task = new FetchVideoStreamsTask().execute();
    }

    public class FetchVideoStreamsTask extends AsyncTask<Void, Void, StreamInfo> {

        @Override
        protected StreamInfo doInBackground(Void... voids) {
            try {
                NewPipe.init(DownloaderTestImpl.getInstance());
                StreamingService youtubeService = ServiceList.YouTube;

                // Get stream info directly instead of just the video streams
                return StreamInfo.getInfo(youtubeService, videoUrl);
            } catch (IOException | ExtractionException e) {
                Log.e(TAG, "Error fetching stream info", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(StreamInfo streamInfo) {
            if (streamInfo == null) {
                Log.e(TAG, "Failed to fetch stream info");
                finish();
            }
            String videoStreamUrl = null;

            try {
                // Check if it's a live stream
                if (streamInfo.getStreamType().equals(StreamType.LIVE_STREAM)) {
                    // For live streams, use the HLS manifest URL if available
                    String manifestUrl = streamInfo.getHlsUrl();
                    if (manifestUrl != null && !manifestUrl.isEmpty()) {
                        Log.d(TAG, "Playing HLS stream: " + manifestUrl);
                        videoStreamUrl = manifestUrl;
                        return;
                    }
                }

                // Fall back to regular video streams if not live or HLS not available
                List<VideoStream> videoStreams = streamInfo.getVideoStreams();
                if (videoStreams != null && !videoStreams.isEmpty()) {
                    // Log the total number of available streams
                    Log.d(TAG, "Total available streams: " + videoStreams.size());

                    // Log available streams
                    for (VideoStream stream : videoStreams) {
                        Log.d(TAG, String.format("Stream: %s, Resolution: %s, Format: %s",
                                stream.getUrl(), stream.getResolution(), stream.getFormat().getName()));
                    }
                    videoStreamUrl = videoStreams.get(0).getUrl();
                } else {
                    Log.e(TAG, "No video streams available");
                }
                // Build the MediaItem with subtitles
                MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
                        .setUri(videoStreamUrl);
                List<SubtitlesStream> subtitleStreams = streamInfo.getSubtitles();
                if (subtitleStreams != null && !subtitleStreams.isEmpty()) {
                    for (SubtitlesStream subtitle : subtitleStreams) {
                        MediaItem.SubtitleConfiguration subtitleConfig = new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.getUrl()))
                                .setLanguage(subtitle.getLanguageTag())
                                .setMimeType(subtitle.getFormat().getMimeType())
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build();
                        mediaItemBuilder.setSubtitleConfigurations(List.of(subtitleConfig));
                        break; // For now, just add the first subtitle track
                    }
                }
                playVideo(mediaItemBuilder.build(), false); //TODO: How to detect if video is 360?
            } catch (Exception e) {
                Log.e(TAG, "Error processing stream info", e);
                finish();
            }
        }
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

        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .setReleaseTimeoutMs(5000)
                // Limit video resolution to match Glass display
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state != ExoPlayer.STATE_BUFFERING && mIndeterminate != null) {
                    mIndeterminate.hide();
                    mIndeterminate = null;
                    player.removeListener(this);
                }
            }
        });

        playerView.setPlayer(player);
        subtitleView = playerView.getSubtitleView();
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        subtitleView.setVisibility(View.GONE);
        playerView.setControllerShowTimeoutMs(0);
        playerView.setControllerHideOnTouch(false);
        controlBar.postDelayed(() -> {
            controlBar.setVisibility(View.GONE);
            controlsVisible = false;
        }, 3000);
        player.setMediaItem(mediaItem);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.prepare();
        player.play();
        playerView.onResume();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        task.cancel(true);
        if (player != null) {
            player.release();
        }
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
                        controlBar.postDelayed(() -> {
                            controlBar.setVisibility(View.GONE);
                            controlsVisible = false;
                        }, 3000);
                    }
                }
                if (gesture == Gesture.TAP) {
                    if (player.isPlaying()) {
                        player.pause();
                        playerView.onPause();
                    } else {
                        player.play();
                    }
                    return true;
                } else if (gesture == Gesture.SWIPE_RIGHT) {
                    player.seekForward();
                    return true;
                } else if (gesture == Gesture.SWIPE_LEFT) {
                    player.seekBack();
                    return true;
                } else if (gesture ==  Gesture.TWO_TAP) {
                    // Toggle subtitles on double tap
                    subtitlesEnabled = !subtitlesEnabled;
                    subtitleView.setVisibility(subtitlesEnabled ? View.VISIBLE : View.GONE);
                    captionIcon.setImageResource(subtitlesEnabled ?
                            R.drawable.cc_enabled : R.drawable.cc_disabled);
                    Log.d(TAG, "Subtitles " + (subtitlesEnabled ? "enabled" : "disabled"));
                    return true;
                }
                return false;
            }
        });
        return gestureDetector;
    }
    /*
     * Send generic motion events to the gesture detector
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mGestureDetector != null) {
            return mGestureDetector.onMotionEvent(event);
        }
        return false;
    }
}
