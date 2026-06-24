package com.glass.remoteagent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, AgentService.class));
        String ip = NetworkUtils.getWifiIp(this);
        String token = RemoteSecurity.getToken(this);
        String address = ip.length() == 0 ? "Connect Glass to Wi-Fi" :
                "http://" + ip + ":" + AgentServer.PORT;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 22, 32, 22);
        layout.setBackgroundColor(Color.BLACK);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("GlassyTube Remote", 42, Color.WHITE);
        title.setGravity(Gravity.CENTER);

        TextView code = text("Code " + token, 25, Color.WHITE);
        code.setGravity(Gravity.CENTER);
        code.setPadding(0, 30, 0, 6);

        TextView url = text(address, 23, Color.rgb(190, 196, 204));
        url.setGravity(Gravity.CENTER);

        TextView hint = text("Open the remote on your phone", 18, Color.rgb(150, 156, 164));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 22, 0, 0);

        layout.addView(title, fullWidth());
        layout.addView(code, fullWidth());
        layout.addView(url, fullWidth());
        layout.addView(hint, fullWidth());
        setContentView(layout);
    }

    private TextView text(String value, int sp, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setSingleLine(false);
        textView.setIncludeFontPadding(false);
        return textView;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
