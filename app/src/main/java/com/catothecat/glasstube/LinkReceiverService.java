package com.catothecat.glasstube;

import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

public class LinkReceiverService extends Service {
    private static final String TAG = "LinkReceiverService";
    private GlassTubeServer server;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void onCreate() {
        super.onCreate();
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServer();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startServer() {
        if (server != null) {
            return;
        }
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("GlassTubeLinkReceiver");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            server = new GlassTubeServer(this);
            Log.i(TAG, "Link receiver listening on port " + GlassTubeServer.PORT);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start link receiver", e);
        }
    }
}
