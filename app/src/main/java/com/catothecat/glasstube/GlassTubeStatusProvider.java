package com.catothecat.glasstube;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class GlassTubeStatusProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"json"});
        if (uri != null && uri.getPath() != null && uri.getPath().contains("logs")) {
            cursor.addRow(new Object[]{AppLog.read(getContext())});
            return cursor;
        }
        String body = "{\"installed\":true,\"queue\":" + VideoStore.getQueue(getContext()).size()
                + ",\"active\":" + VideoStore.isAppActive(getContext())
                + ",\"lastActiveMs\":" + VideoStore.getLastActive(getContext())
                + ",\"player\":" + VideoStore.getPlaybackJson(getContext()) + "}";
        cursor.addRow(new Object[]{body});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
