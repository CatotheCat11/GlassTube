package com.glassagent.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class HudOverlay extends FrameLayout {
    public enum HudState {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING,
        ERROR
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView title;
    private final TextView body;
    private final TextView hint;
    private int tick;
    private HudState state = HudState.IDLE;

    private final Runnable animation = new Runnable() {
        @Override
        public void run() {
            tick++;
            render();
            if (state == HudState.LISTENING || state == HudState.THINKING || state == HudState.SPEAKING) {
                handler.postDelayed(this, 350L);
            }
        }
    };

    public HudOverlay(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);

        LinearLayout stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER);
        int pad = dp(24);
        stack.setPadding(pad, pad, pad, pad);

        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);
        title.setAllCaps(true);

        body = new TextView(context);
        body.setTextColor(Color.rgb(150, 245, 255));
        body.setTextSize(18f);
        body.setGravity(Gravity.CENTER);
        body.setMaxLines(4);
        body.setPadding(0, dp(12), 0, dp(12));

        hint = new TextView(context);
        hint.setTextColor(Color.rgb(180, 180, 180));
        hint.setTextSize(12f);
        hint.setGravity(Gravity.CENTER);

        stack.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stack.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stack.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(stack, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        setState(HudState.IDLE, null);
    }

    public void setState(HudState nextState, String message) {
        state = nextState;
        tick = 0;
        body.setTag(message);
        handler.removeCallbacks(animation);
        render();
        if (state == HudState.LISTENING || state == HudState.THINKING || state == HudState.SPEAKING) {
            handler.postDelayed(animation, 350L);
        }
    }

    private void render() {
        String dots = new String(new char[(tick % 3) + 1]).replace('\0', '.');
        String message = body.getTag() instanceof String ? (String) body.getTag() : "";
        switch (state) {
            case LISTENING:
                title.setText("Listening" + dots);
                body.setText(message == null || message.isEmpty() ? "Speak now" : message);
                hint.setText("Tap again to cancel");
                break;
            case THINKING:
                title.setText("Thinking" + dots);
                body.setText(message == null || message.isEmpty() ? "Working" : message);
                hint.setText("");
                break;
            case SPEAKING:
                title.setText("Reply");
                body.setText(message == null || message.isEmpty() ? "Speaking" : message);
                hint.setText("Tap to stop");
                break;
            case ERROR:
                title.setText("Error");
                body.setText(message == null || message.isEmpty() ? "Something failed" : message);
                hint.setText("Tap to retry");
                break;
            case IDLE:
            default:
                title.setText("Glass Agent");
                body.setText("Tap to speak");
                hint.setText("Long press clears this session");
                break;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
