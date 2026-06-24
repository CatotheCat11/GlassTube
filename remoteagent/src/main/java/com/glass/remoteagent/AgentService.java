package com.glass.remoteagent;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class AgentService extends Service {
    private static final String TAG = "AgentService";
    private AgentServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
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
            server = new AgentServer(this);
            Log.i(TAG, "GlassyTube Remote listening on port " + AgentServer.PORT);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start GlassyTube Remote server", e);
        }
    }
}
