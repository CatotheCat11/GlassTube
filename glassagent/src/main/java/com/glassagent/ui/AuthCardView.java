package com.glassagent.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.glassagent.auth.DeviceCodeResponse;
import com.glassagent.config.ProviderConfig;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class AuthCardView extends LinearLayout {
    private final TextView title;
    private final TextView uri;
    private final TextView code;
    private final TextView status;
    private final ImageView qr;

    public AuthCardView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(Color.BLACK);
        int pad = dp(18);
        setPadding(pad, pad, pad, pad);

        title = text(context, 20f, Color.WHITE);
        uri = text(context, 13f, Color.rgb(150, 245, 255));
        code = text(context, 24f, Color.WHITE);
        status = text(context, 12f, Color.rgb(190, 190, 190));
        qr = new ImageView(context);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(6), dp(6), dp(6), dp(6));

        addView(title, lp());
        addView(qr, new LayoutParams(dp(136), dp(136)));
        addView(uri, lp());
        addView(code, lp());
        addView(status, lp());

        setWaiting();
    }

    public void setWaiting() {
        title.setText("Pair Device");
        uri.setText("Requesting code");
        code.setText("");
        status.setText("");
        qr.setImageBitmap(null);
        qr.setVisibility(GONE);
    }

    public void setDeviceCode(DeviceCodeResponse deviceCode, ProviderConfig config) {
        title.setText("Pair Device");
        uri.setText(config.verificationUriLabel == null || config.verificationUriLabel.isEmpty()
                ? deviceCode.verificationUri : config.verificationUriLabel);
        code.setText(deviceCode.userCode);
        status.setText("Authorize on phone, then wait here");
        qr.setVisibility(VISIBLE);
        qr.setImageBitmap(qrBitmap(deviceCode.verificationUri));
    }

    public void setError(String message) {
        title.setText("Auth Failed");
        uri.setText("");
        code.setText("");
        status.setText(message == null || message.isEmpty() ? "Tap to retry" : message);
        qr.setImageBitmap(null);
        qr.setVisibility(GONE);
    }

    private TextView text(Context context, float size, int color) {
        TextView view = new TextView(context);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        view.setMaxLines(5);
        return view;
    }

    private LayoutParams lp() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Bitmap qrBitmap(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            int size = 124;
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (WriterException e) {
            return null;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
