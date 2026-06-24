package com.catothecat.glasstube;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppLog {
    public static final String LOG_DIR = "GlassTube";
    public static final String LOG_FILE = "glasstube.log";
    private static final long MAX_BYTES = 512 * 1024;
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private AppLog() {
    }

    public static void d(Context context, String tag, String message) {
        Log.d(tag, message);
        write(context, "D", tag, message, null);
    }

    public static void i(Context context, String tag, String message) {
        Log.i(tag, message);
        write(context, "I", tag, message, null);
    }

    public static void w(Context context, String tag, String message) {
        Log.w(tag, message);
        write(context, "W", tag, message, null);
    }

    public static void w(Context context, String tag, String message, Throwable throwable) {
        Log.w(tag, message, throwable);
        write(context, "W", tag, message, throwable);
    }

    public static void e(Context context, String tag, String message) {
        Log.e(tag, message);
        write(context, "E", tag, message, null);
    }

    public static void e(Context context, String tag, String message, Throwable throwable) {
        Log.e(tag, message, throwable);
        write(context, "E", tag, message, throwable);
    }

    private static synchronized void write(Context context, String level, String tag,
                                           String message, Throwable throwable) {
        writeToFile(externalLogFile(), level, tag, message, throwable);
        writeToFile(privateLogFile(context), level, tag, message, throwable);
    }

    private static void writeToFile(File file, String level, String tag,
                                    String message, Throwable throwable) {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (file.exists() && file.length() > MAX_BYTES) {
                file.delete();
            }
            PrintWriter printWriter = new PrintWriter(new FileWriter(file, true));
            printWriter.print(FORMAT.format(new Date()));
            printWriter.print(" ");
            printWriter.print(level);
            printWriter.print("/");
            printWriter.print(tag);
            printWriter.print(": ");
            printWriter.println(message == null ? "" : message);
            if (throwable != null) {
                throwable.printStackTrace(printWriter);
            }
            printWriter.close();
        } catch (Exception ignored) {
        }
    }

    public static synchronized String read(Context context) {
        String text = readFile(privateLogFile(context));
        if (text.length() > 0) {
            return text;
        }
        text = readFile(externalLogFile());
        if (text.length() > 0) {
            return text;
        }
        return "GlassyTube log is empty.\n";
    }

    private static String readFile(File file) {
        if (file == null || !file.exists()) {
            return "";
        }
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            reader.close();
            return builder.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static File externalLogFile() {
        File external = Environment.getExternalStorageDirectory();
        if (external != null) {
            return new File(new File(external, LOG_DIR), LOG_FILE);
        }
        return null;
    }

    private static File privateLogFile(Context context) {
        if (context == null) {
            return null;
        }
        return new File(context.getFilesDir(), LOG_FILE);
    }
}
