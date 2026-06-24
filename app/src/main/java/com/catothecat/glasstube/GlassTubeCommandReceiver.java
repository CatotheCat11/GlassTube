package com.catothecat.glasstube;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class GlassTubeCommandReceiver extends BroadcastReceiver {
    private static final String TAG = "GlassTubeCommand";
    public static final String ACTION = "com.catothecat.glasstube.AGENT_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TEXT = "text";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        String command = intent.getStringExtra(EXTRA_COMMAND);
        AppLog.i(context, TAG, "Received command=" + command);
        if ("open".equals(command)) {
            String url = intent.getStringExtra(EXTRA_URL);
            if (url != null && url.length() > 0) {
                Intent openIntent = new Intent(context, MainActivity.class);
                openIntent.setAction(VideoStore.ACTION_OPEN_URL);
                openIntent.putExtra(VideoStore.EXTRA_URL, url);
                openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(openIntent);
            }
            return;
        }
        if ("queue".equals(command)) {
            String url = intent.getStringExtra(EXTRA_URL);
            if (url != null && url.length() > 0) {
                VideoStore.enqueue(context, "Queued video", url);
            }
            return;
        }
        if ("search".equals(command)) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text != null && text.trim().length() > 0) {
                Intent searchIntent = new Intent(context, MainActivity.class);
                searchIntent.setAction(VideoStore.ACTION_SEARCH);
                searchIntent.putExtra(VideoStore.EXTRA_TEXT, text.trim());
                searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(searchIntent);
            }
            return;
        }
        if ("text".equals(command)) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            Intent remoteIntent = new Intent(VideoStore.ACTION_REMOTE_COMMAND);
            remoteIntent.setPackage(context.getPackageName());
            remoteIntent.putExtra(VideoStore.EXTRA_COMMAND, "text");
            remoteIntent.putExtra(VideoStore.EXTRA_TEXT, text);
            context.sendBroadcast(remoteIntent, "com.glass.remoteagent.permission.CONTROL");
            return;
        }
        if (command != null && command.length() > 0) {
            if ("home".equals(command) || "enter".equals(command) || "select".equals(command)
                    || "up".equals(command) || "down".equals(command)
                    || "left".equals(command) || "right".equals(command)
                    || "menu".equals(command) || "search".equals(command)) {
                Intent commandIntent = new Intent(context, MainActivity.class);
                commandIntent.setAction(VideoStore.ACTION_REMOTE_COMMAND);
                commandIntent.putExtra(VideoStore.EXTRA_COMMAND, command);
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (text != null) {
                    commandIntent.putExtra(VideoStore.EXTRA_TEXT, text);
                }
                commandIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(commandIntent);
                return;
            }
            Intent remoteIntent = new Intent(VideoStore.ACTION_REMOTE_COMMAND);
            remoteIntent.setPackage(context.getPackageName());
            remoteIntent.putExtra(VideoStore.EXTRA_COMMAND, command);
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text != null) {
                remoteIntent.putExtra(VideoStore.EXTRA_TEXT, text);
            }
            context.sendBroadcast(remoteIntent, "com.glass.remoteagent.permission.CONTROL");
        }
    }
}
