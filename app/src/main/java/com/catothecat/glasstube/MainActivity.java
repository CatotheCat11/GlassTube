package com.catothecat.glasstube;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private List<CardBuilder> mCards;
    private CardScrollView mCardScrollView;
    private ExampleCardScrollAdapter mAdapter;
    private List<String> videoResults = new ArrayList<String>();
    private static final int SPEECH_REQUEST = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCards = new ArrayList<CardBuilder>();
        mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                .setText("Loading..."));
        mCardScrollView = new CardScrollView(this);
        mAdapter = new ExampleCardScrollAdapter();
        mCardScrollView.setAdapter(mAdapter);
        mCardScrollView.activate();
        setupClickListener();
        setContentView(mCardScrollView);
        if (getIntent().getExtras() != null) {
            ArrayList<String> voiceResults = getIntent().getExtras()
                    .getStringArrayList(RecognizerIntent.EXTRA_RESULTS);
            String voiceResult = voiceResults.get(0);
            Log.d("MainActivity", "Voice result: " + voiceResult);
            new videoSearch().execute(voiceResult);
        } else {
            Log.d(TAG, "No voice result");
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Search for a video");
            startActivityForResult(intent, SPEECH_REQUEST);
        }
    }

    private class ExampleCardScrollAdapter extends CardScrollAdapter {

        @Override
        public int getPosition(Object item) {
            return mCards.indexOf(item);
        }

        @Override
        public int getCount() {
            return mCards.size();
        }

        @Override
        public Object getItem(int position) {
            return mCards.get(position);
        }

        @Override
        public int getViewTypeCount() {
            return CardBuilder.getViewTypeCount();
        }

        @Override
        public int getItemViewType(int position){
            return mCards.get(position).getItemViewType();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return mCards.get(position).getView(convertView, parent);
        }
    }
    private void setupClickListener() {
        mCardScrollView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.playSoundEffect(Sounds.TAP);
                Intent intent = new Intent(MainActivity.this, VideoActivity.class);
                intent.putExtra("url", videoResults.get(position));
                startActivity(intent);
            }
        });
    }
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SPEECH_REQUEST && resultCode == RESULT_OK) {
            List<String> results = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS);
            String spokenText = results.get(0);
            new videoSearch().execute(spokenText);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public class videoSearch extends AsyncTask<String, Void, List<InfoItem>> {
        private static final String TAG = "VideoSearch";

        @Override
        protected List<InfoItem> doInBackground(String... query) {
            try {
                // Initialize NewPipe if you haven't already
                NewPipe.init(DownloaderTestImpl.getInstance());

                // Get YouTube service
                StreamingService youtubeService = ServiceList.YouTube;

                // Create search info for the query
                SearchInfo searchInfo = SearchInfo.getInfo(youtubeService,
                        youtubeService.getSearchQHFactory().fromQuery(query[0]));

                mCards.remove(0);
                runOnUiThread(() -> {
                    mAdapter.notifyDataSetChanged();
                });

                // Get search results
                return searchInfo.getRelatedItems();

            } catch (Exception e) {
                Log.e(TAG, "Error searching for videos", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<InfoItem> results) {
            if (results != null) {
                for (InfoItem item : results) {
                    // Check if the item is a video
                    if (item instanceof StreamInfoItem) {
                        StreamInfoItem videoItem = (StreamInfoItem) item;

                        String title = videoItem.getName();
                        String url = videoItem.getUrl();
                        videoResults.add(url);
                        String uploaderName = videoItem.getUploaderName();
                        String viewCount = round(videoItem.getViewCount());
                        String duration = "";
                        if (videoItem.getDuration() != -1) {
                            duration = formatDuration(videoItem.getDuration());
                        } else {
                            duration = "\uD83D\uDD34 Live";
                        }
                        String thumbnailUrl = videoItem.getThumbnails().get(0).getUrl();
                        String avatarUrl = videoItem.getUploaderAvatars().get(0).getUrl();
                        CardBuilder card = new CardBuilder(MainActivity.this, CardBuilder.Layout.CAPTION)
                                .setText(title)
                                .setFootnote(uploaderName + " • " + viewCount + " views")
                                .setTimestamp(duration);
                        mCards.add(card);
                        Glide.with(MainActivity.this)
                                .asBitmap()
                                .override(640, 360)
                                .load(thumbnailUrl)
                                    .into(new CustomTarget<Bitmap>() { // Use CustomTarget to handle the Bitmap directly
                                        @Override
                                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                                            card.addImage(resource);
                                        }

                                        @Override
                                        public void onLoadCleared(@Nullable Drawable placeholder) {

                                        }

                                        @Override
                                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                            super.onLoadFailed(errorDrawable);
                                            Log.e(TAG, "Image load failed");
                                        }
                                    });
                        Glide.with(MainActivity.this)
                                .asBitmap()
                                .load(avatarUrl)
                                .into(new CustomTarget<Bitmap>() { // Use CustomTarget to handle the Bitmap directly
                                    @Override
                                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                                        card.setIcon(resource);
                                    }

                                    @Override
                                    public void onLoadCleared(@Nullable Drawable placeholder) {

                                    }

                                    @Override
                                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                        super.onLoadFailed(errorDrawable);
                                        Log.e(TAG, "Image load failed");
                                    }
                                });

                        Log.d(TAG, "Found video: " + title + " by " + uploaderName + " at " + url);
                    }
                }
                runOnUiThread(() -> {
                    mAdapter.notifyDataSetChanged();
                });
            }
        }
    }
    private String formatDuration(long seconds) {
        if (seconds < 0) {
            return "Invalid duration";
        }

        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;

        return String.format("%02d:%02d", minutes, remainingSeconds);
    }
    public static String round(Long val) {
        if (val >= 1000000) {
            return String.format("%.1fM", val / 1000000.0);
        } else if (val >= 1000) {
            return String.format("%.1fK", val / 1000.0);
        } else {
            return val.toString();
        }
    }
}
