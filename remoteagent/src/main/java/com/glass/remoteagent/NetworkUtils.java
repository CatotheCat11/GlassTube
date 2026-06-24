package com.glass.remoteagent;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

public final class NetworkUtils {
    private NetworkUtils() {
    }

    public static String getWifiIp(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            return "";
        }
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) {
            return "";
        }
        int ip = info.getIpAddress();
        if (ip == 0) {
            return "";
        }
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }
}
