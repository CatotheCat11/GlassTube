package com.glass.remoteagent;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;

public class AgentServer extends NanoHTTPD {
    private static final String TAG = "AgentServer";
    public static final int PORT = 8765;
    private static final String GLASSTUBE_COMMAND = "com.catothecat.glasstube.AGENT_COMMAND";
    private static final String GLASSTUBE_PACKAGE = "com.catothecat.glasstube";
    private static final Uri GLASSTUBE_STATUS =
            Uri.parse("content://com.catothecat.glasstube.status/status");
    private static final Uri GLASSTUBE_LOGS =
            Uri.parse("content://com.catothecat.glasstube.status/logs");
    private static final ExecutorService STATUS_EXECUTOR = Executors.newCachedThreadPool();

    private final Context context;

    public AgentServer(Context context) throws IOException {
        super(PORT);
        this.context = context.getApplicationContext();
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        if (Method.GET.equals(method) && ("/".equals(uri) || "/remote".equals(uri))) {
            return html(remotePage());
        }
        if (Method.GET.equals(method) && "/status".equals(uri)) {
            return json(statusJson());
        }
        if (Method.GET.equals(method) && ("/logs".equals(uri) || "/glasstube/logs".equals(uri))) {
            if (!isAuthorized(session)) {
                return response(Response.Status.UNAUTHORIZED, "application/json",
                        "{\"ok\":false,\"error\":\"Pairing token required\"}");
            }
            return response(Response.Status.OK, "text/plain", readGlassTubeLog());
        }
        if (!Method.POST.equals(method)) {
            return notFound();
        }
        String body = readBody(session);
        if (!isAuthorized(session)) {
            return response(Response.Status.UNAUTHORIZED, "application/json",
                    "{\"ok\":false,\"error\":\"Pairing token required\"}");
        }
        if ("/system/key".equals(uri)) {
            return handleSystemKey(session, body);
        }
        if ("/system/text".equals(uri)) {
            return handleSystemText(session, body);
        }
        if ("/glasstube/open".equals(uri) || "/open".equals(uri)) {
            return handleGlassTubeOpen(session, body);
        }
        if ("/glasstube/queue".equals(uri) || "/queue".equals(uri)) {
            return handleGlassTubeQueue(session, body);
        }
        if ("/glasstube/wake".equals(uri) || "/wake".equals(uri)) {
            return handleGlassTubeWake();
        }
        if ("/glasstube/control".equals(uri) || "/control".equals(uri)) {
            return handleGlassTubeControl(session, body);
        }
        if ("/glasstube/text".equals(uri) || "/text".equals(uri)) {
            return handleGlassTubeText(session, body);
        }
        if ("/glasstube/search".equals(uri) || "/search".equals(uri)) {
            return handleGlassTubeSearch(session, body);
        }
        return notFound();
    }

    private Response handleSystemKey(IHTTPSession session, String body) {
        String key = firstNonEmpty(session.getParms().get("key"), session.getParms().get("code"), body);
        String keyCode = normalizeKeyCode(key);
        if (keyCode == null) {
            return jsonError("Unsupported key.");
        }
        boolean ok = handleSafeSystemKey(key == null ? "" : key.trim().toLowerCase(Locale.ROOT), keyCode);
        return json("{\"ok\":" + ok + ",\"key\":\"" + escapeJson(keyCode) + "\"}");
    }

    private Response handleSystemText(IHTTPSession session, String body) {
        String text = readText(session, body);
        if (text == null || text.length() == 0) {
            return jsonError("Expected text.");
        }
        boolean ok = runRootInputCommand("input text '" + escapeShellSingleQuoted(toAndroidInputText(text)) + "'");
        return json("{\"ok\":" + ok + ",\"systemText\":true}");
    }

    private Response handleGlassTubeOpen(IHTTPSession session, String body) {
        String url = readUrl(session, body);
        if (!isYouTubeUrl(url)) {
            return jsonError("Expected YouTube URL.");
        }
        sendGlassTube("open", url, null);
        return json("{\"ok\":true,\"opened\":true}");
    }

    private Response handleGlassTubeWake() {
        sendGlassTube("open", null, null);
        return json("{\"ok\":true,\"waking\":true}");
    }

    private Response handleGlassTubeQueue(IHTTPSession session, String body) {
        String url = readUrl(session, body);
        if (!isYouTubeUrl(url)) {
            return jsonError("Expected YouTube URL.");
        }
        sendGlassTube("queue", url, null);
        return json("{\"ok\":true,\"queued\":true}");
    }

    private Response handleGlassTubeControl(IHTTPSession session, String body) {
        String command = firstNonEmpty(session.getParms().get("cmd"), session.getParms().get("command"), body);
        if (command == null || command.trim().length() == 0) {
            return jsonError("Expected command.");
        }
        command = command.trim().toLowerCase(Locale.ROOT);
        if ("volume_up".equals(command) || "volume_down".equals(command) || "mute".equals(command)) {
            adjustVolume(command);
        }
        sendGlassTube(command, null, null);
        return json("{\"ok\":true,\"command\":\"" + escapeJson(command) + "\"}");
    }

    private Response handleGlassTubeText(IHTTPSession session, String body) {
        String text = readText(session, body);
        if (text == null || text.trim().length() == 0) {
            return jsonError("Expected text.");
        }
        sendGlassTube("text", null, text.trim());
        return json("{\"ok\":true,\"text\":true}");
    }

    private Response handleGlassTubeSearch(IHTTPSession session, String body) {
        String text = readText(session, body);
        if (text == null || text.trim().length() == 0) {
            return jsonError("Expected search text.");
        }
        sendGlassTube("search", null, text.trim());
        return json("{\"ok\":true,\"search\":true}");
    }

    private String statusJson() {
        String glassTube = glassTubeStatus();
        boolean glassTubeTop = isGlassTubeTop();
        if (glassTubeTop) {
            glassTube = forceActive(glassTube);
        } else {
            glassTube = forceInactiveIdle(glassTube);
        }
        return "{\"ok\":true,\"app\":\"GlassyTube Remote\",\"ip\":\"" + escapeJson(NetworkUtils.getWifiIp(context))
                + "\",\"volume\":" + volumeJson() + ",\"glasstube\":" + glassTube + "}";
    }

    private String volumeJson() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return "{\"level\":0,\"max\":0,\"percent\":0,\"muted\":false}";
        }
        int level = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int percent = max <= 0 ? 0 : Math.round((level * 100f) / max);
        return "{\"level\":" + level + ",\"max\":" + max + ",\"percent\":" + percent
                + ",\"muted\":" + (level == 0) + "}";
    }

    private String readGlassTubeLog() {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(GLASSTUBE_LOGS, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to read GlassyTube log provider", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        File file = new File(Environment.getExternalStorageDirectory(), "GlassTube/glasstube.log");
        if (!file.exists()) {
            return "GlassyTube log not found: " + file.getAbsolutePath() + "\n";
        }
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to read GlassyTube log", e);
            return "Unable to read GlassyTube log: " + e.getMessage() + "\n";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return builder.toString();
    }

    private String forceActive(String glassTubeJson) {
        try {
            JSONObject object = new JSONObject(glassTubeJson);
            object.put("installed", true);
            object.put("active", true);
            object.remove("stale");
            return object.toString();
        } catch (Exception e) {
            return "{\"installed\":true,\"active\":true,\"player\":{\"state\":\"unknown\"}}";
        }
    }

    private String forceInactiveIdle(String glassTubeJson) {
        try {
            JSONObject object = new JSONObject(glassTubeJson);
            JSONObject player = object.optJSONObject("player");
            if (player == null) {
                player = new JSONObject();
                object.put("player", player);
            }
            player.put("state", "idle");
            player.put("positionMs", 0);
            player.put("durationMs", 0);
            object.put("active", false);
            return object.toString();
        } catch (Exception e) {
            return "{\"installed\":true,\"active\":false,\"player\":{\"state\":\"idle\",\"positionMs\":0,\"durationMs\":0}}";
        }
    }

    private boolean isGlassTubeTop() {
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) {
                return false;
            }
            List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) {
                return false;
            }
            ComponentName top = tasks.get(0).topActivity;
            return top != null && GLASSTUBE_PACKAGE.equals(top.getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "Unable to inspect top activity", e);
            return false;
        }
    }

    private String glassTubeStatus() {
        Future<String> future = STATUS_EXECUTOR.submit(new Callable<String>() {
            @Override
            public String call() {
                return queryGlassTubeStatus();
            }
        });
        try {
            return future.get(5000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            Log.w(TAG, "GlassyTube status timed out", e);
            return "{\"installed\":true,\"active\":false,\"stale\":true,\"player\":{\"state\":\"unknown\"}}";
        }
    }

    private String queryGlassTubeStatus() {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(GLASSTUBE_STATUS, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "GlassyTube status unavailable", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return "{\"installed\":false,\"active\":false}";
    }

    private void sendGlassTube(String command, String url, String text) {
        if ("open".equals(command)) {
            Intent openIntent = new Intent();
            openIntent.setClassName(GLASSTUBE_PACKAGE, GLASSTUBE_PACKAGE + ".MainActivity");
            if (url != null) {
                openIntent.setAction("com.catothecat.glasstube.OPEN_URL");
                openIntent.putExtra("url", url);
            }
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(openIntent);
            return;
        }
        Intent intent = new Intent(GLASSTUBE_COMMAND);
        intent.setPackage(GLASSTUBE_PACKAGE);
        intent.putExtra("command", command);
        if (url != null) {
            intent.putExtra("url", url);
        }
        if (text != null) {
            intent.putExtra("text", text);
        }
        context.sendBroadcast(intent, "com.glass.remoteagent.permission.CONTROL");
    }

    private void adjustVolume(String command) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        if ("volume_up".equals(command)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
        } else if ("volume_down".equals(command)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        } else if ("mute".equals(command)) {
            boolean mute = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0;
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, mute);
        }
    }

    private boolean handleSafeSystemKey(String normalized, String keyCode) {
        if ("volume_up".equals(normalized) || "volup".equals(normalized)) {
            adjustVolume("volume_up");
            return true;
        }
        if ("volume_down".equals(normalized) || "voldown".equals(normalized)) {
            adjustVolume("volume_down");
            return true;
        }
        if ("mute".equals(normalized)) {
            adjustVolume("mute");
            return true;
        }
        if (isMediaKeyCode(keyCode)) {
            return dispatchMediaKey(Integer.parseInt(keyCode));
        }
        return runRootInputCommand("input keyevent " + keyCode);
    }

    private boolean isMediaKeyCode(String keyCode) {
        return "85".equals(keyCode)
                || "87".equals(keyCode)
                || "88".equals(keyCode)
                || "126".equals(keyCode)
                || "127".equals(keyCode);
    }

    private boolean dispatchMediaKey(int keyCode) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
        return true;
    }

    private boolean isAuthorized(IHTTPSession session) {
        String token = session.getHeaders().get("x-glass-agent-token");
        if (RemoteSecurity.isAuthorized(context, token)) {
            return true;
        }
        token = session.getHeaders().get("x-glasstube-token");
        if (RemoteSecurity.isAuthorized(context, token)) {
            return true;
        }
        token = session.getParms().get("token");
        if (RemoteSecurity.isAuthorized(context, token)) {
            return true;
        }
        token = session.getParms().get("t");
        return RemoteSecurity.isAuthorized(context, token);
    }

    private String readBody(IHTTPSession session) {
        try {
            HashMap<String, String> files = new HashMap<String, String>();
            session.parseBody(files);
            String body = files.get("postData");
            return body == null ? "" : body.trim();
        } catch (Exception e) {
            Log.w(TAG, "Unable to parse request body", e);
            return "";
        }
    }

    private String readUrl(IHTTPSession session, String body) {
        Map<String, String> params = session.getParms();
        String url = params.get("url");
        if (url != null && url.trim().length() > 0) {
            return url.trim();
        }
        for (String key : params.keySet()) {
            String value = params.get(key);
            if (isYouTubeUrl(key)) {
                if (value != null && value.trim().length() > 0 && !key.contains("=")) {
                    return (key + "=" + value).trim();
                }
                return key.trim();
            }
            if (isYouTubeUrl(value)) {
                return value.trim();
            }
        }
        return body == null ? "" : body.trim();
    }

    private String readText(IHTTPSession session, String body) {
        String text = firstNonEmpty(session.getParms().get("text"), session.getParms().get("q"), body);
        if (text != null && text.trim().length() > 0) {
            return text.trim();
        }
        for (String key : session.getParms().keySet()) {
            if ("token".equals(key) || "t".equals(key)) {
                continue;
            }
            String value = session.getParms().get(key);
            if (value != null && value.trim().length() > 0) {
                return (key + "=" + value).trim();
            }
            if (key != null && key.trim().length() > 0) {
                return key.trim();
            }
        }
        return "";
    }

    private boolean runRootInputCommand(String command) {
        if (runShell("su", "-c", command)) {
            return true;
        }
        return runShell("sh", "-c", command);
    }

    private boolean runShell(String binary, String flag, String command) {
        Process process = null;
        try {
            process = new ProcessBuilder(binary, flag, command).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            Log.w(TAG, "Input command failed via " + binary, e);
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String normalizeKeyCode(String key) {
        if (key == null) return null;
        String clean = key.trim().toLowerCase(Locale.ROOT);
        if (clean.matches("\\d{1,3}")) return clean;
        if ("home".equals(clean)) return "3";
        if ("back".equals(clean)) return "4";
        if ("menu".equals(clean)) return "82";
        if ("search".equals(clean)) return "84";
        if ("enter".equals(clean) || "select".equals(clean) || "center".equals(clean)) return "66";
        if ("up".equals(clean) || "dpad_up".equals(clean)) return "19";
        if ("down".equals(clean) || "dpad_down".equals(clean)) return "20";
        if ("left".equals(clean) || "dpad_left".equals(clean)) return "21";
        if ("right".equals(clean) || "dpad_right".equals(clean)) return "22";
        if ("volume_up".equals(clean) || "volup".equals(clean)) return "24";
        if ("volume_down".equals(clean) || "voldown".equals(clean)) return "25";
        if ("mute".equals(clean)) return "164";
        if ("play_pause".equals(clean) || "media_play_pause".equals(clean)) return "85";
        if ("play".equals(clean) || "media_play".equals(clean)) return "126";
        if ("pause".equals(clean) || "media_pause".equals(clean)) return "127";
        if ("next".equals(clean) || "media_next".equals(clean)) return "87";
        if ("previous".equals(clean) || "prev".equals(clean) || "media_previous".equals(clean)) return "88";
        if ("delete".equals(clean) || "del".equals(clean) || "backspace".equals(clean)) return "67";
        if ("space".equals(clean)) return "62";
        if ("tab".equals(clean)) return "61";
        if ("escape".equals(clean) || "esc".equals(clean)) return "111";
        return null;
    }

    private String toAndroidInputText(String text) {
        StringBuilder builder = new StringBuilder();
        int max = Math.min(text.length(), 500);
        for (int i = 0; i < max; i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                builder.append("%s");
            } else if (c >= 32 && c <= 126) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private String escapeShellSingleQuoted(String value) {
        return value.replace("'", "'\\''");
    }

    private boolean isYouTubeUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://youtube.com/")
                || lower.startsWith("https://youtube.com/")
                || lower.startsWith("http://www.youtube.com/")
                || lower.startsWith("https://www.youtube.com/")
                || lower.startsWith("http://m.youtube.com/")
                || lower.startsWith("https://m.youtube.com/")
                || lower.startsWith("http://youtu.be/")
                || lower.startsWith("https://youtu.be/")
                || lower.startsWith("vnd.youtube:");
    }

    private String firstNonEmpty(String one, String two, String three) {
        if (one != null && one.trim().length() > 0) return one;
        if (two != null && two.trim().length() > 0) return two;
        if (three != null && three.trim().length() > 0) return three;
        return null;
    }

    private Response notFound() {
        return response(Response.Status.NOT_FOUND, "application/json",
                "{\"ok\":false,\"error\":\"Unknown GlassyTube Remote endpoint\"}");
    }

    private Response json(String body) {
        return response(Response.Status.OK, "application/json", body);
    }

    private Response html(String body) {
        return response(Response.Status.OK, "text/html; charset=utf-8", body);
    }

    private Response jsonError(String error) {
        return response(Response.Status.BAD_REQUEST, "application/json",
                "{\"ok\":false,\"error\":\"" + escapeJson(error) + "\"}");
    }

    private Response response(Response.Status status, String mimeType, String body) {
        Response response = newFixedLengthResponse(status, mimeType, body);
        response.addHeader("Connection", "close");
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private String remotePage() {
        String token = RemoteSecurity.getToken(context);
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<title>GlassyTube Remote</title><style>" + remoteCss() + "</style></head>"
                + "<body><main>" + remoteMarkup() + "</main><script>" + remoteScript(token)
                + "</script></body></html>";
    }

    private String remoteCss() {
        return ":root{color-scheme:dark;--bg:#050607;--panel:#111316;--panel2:#181b20;--button:#22252b;--button2:#2b2f36;--line:#30343b;--text:#f6f7f8;--muted:#a2a8b0;--accent:#f5c542;--green:#25c26e;--red:#e05454}"
                + "*{box-sizing:border-box;min-width:0}html,body{width:100%;height:100%;overflow:hidden}body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;-webkit-tap-highlight-color:transparent}"
                + "main{width:min(100%,430px);height:calc(var(--vh,1vh)*100);margin:0 auto;padding:max(8px,env(safe-area-inset-top)) max(10px,env(safe-area-inset-right)) max(10px,env(safe-area-inset-bottom)) max(10px,env(safe-area-inset-left));display:flex;flex-direction:column;gap:8px;overflow:hidden}"
                + ".top{flex:0 0 auto;border-bottom:1px solid var(--line);padding:2px 0 7px}.bar{display:flex;align-items:center;justify-content:space-between;gap:8px}.brand{font-size:19px;font-weight:800;letter-spacing:0}.pill{border:1px solid var(--line);background:#0c0e11;color:var(--muted);border-radius:999px;padding:5px 8px;font-size:11px;white-space:nowrap;max-width:48%;overflow:hidden;text-overflow:ellipsis}"
                + ".panel{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:10px;flex:0 0 auto}.wake{display:none;text-align:left;padding:16px;margin-top:8px}.wakeTitle{font-size:clamp(22px,7vw,28px);font-weight:850;margin-bottom:8px}.wakeText{color:#d5dae0;line-height:1.34;margin-bottom:14px}.hibernating .wake{display:block}.hibernating .activeOnly{display:none!important}.hibernating main{justify-content:flex-start}.mini{display:none}"
                + ".now{display:grid;grid-template-columns:1fr auto;gap:7px;align-items:center;min-height:62px}.title{font-size:15px;font-weight:800;line-height:1.18;overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}.meta{grid-column:1/-1;color:var(--muted);font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.liveDot{width:8px;height:8px;background:var(--green);border-radius:50%}.toast{color:var(--muted);font-size:10px;min-height:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                + "button,textarea{width:100%;font:inherit;border-radius:8px;border:1px solid var(--line)}button{min-height:0;background:var(--button);color:var(--text);font-weight:800;line-height:1.05;padding:6px;white-space:normal;box-shadow:none}button:active{background:var(--button2);transform:scale(.985)}.primary{background:var(--accent);border-color:var(--accent);color:#141414}.good{background:var(--green);border-color:var(--green);color:#061208}.danger{background:var(--red);border-color:var(--red);color:#fff}"
                + ".tabs{display:grid;grid-template-columns:repeat(3,1fr);gap:5px;background:#090b0e;border:1px solid var(--line);border-radius:999px;padding:4px;flex:0 0 auto}.tabs button{height:32px;font-size:12px;border-radius:999px;background:transparent;border-color:transparent;color:var(--muted)}.modeRemote .tabRemote,.modeKeyboard .tabKeyboard,.modeMore .tabMore{background:var(--button2);border-color:var(--line);color:var(--text)}"
                + ".screen{display:none;flex:1 1 auto;min-height:0}.modeRemote .remoteScreen,.modeKeyboard .keyboardScreen,.modeMore .moreScreen{display:flex;flex-direction:column;gap:8px}.remotePanel{flex:1 1 auto;display:flex;flex-direction:column;gap:8px;align-items:stretch}.remotePad{position:relative;display:grid;grid-template-columns:repeat(3,1fr);grid-template-rows:repeat(3,1fr);aspect-ratio:1/1;width:min(100%,48vh);max-height:calc(var(--vh,1vh)*42);align-self:center;background:#15181d;border:1px solid var(--line);border-radius:50%;padding:7%}.remotePad button{height:100%;border-radius:999px;background:transparent;border-color:transparent;font-size:clamp(20px,7vw,31px)}.remotePad button:active{background:#252931}.remotePad .blank{visibility:hidden}.select{background:#2c3139!important;border:1px solid #454a53!important;font-size:clamp(12px,3.4vw,14px)!important}"
                + ".commandRow{display:grid;grid-template-columns:repeat(4,1fr);gap:7px}.commandRow button{height:38px;font-size:12px;border-radius:999px}.timeline{background:#090b0e;border:1px solid var(--line);border-radius:8px;padding:8px 9px;display:grid;grid-template-columns:auto 1fr auto;gap:9px;align-items:center}.timeLabel{font-size:11px;color:var(--muted);font-weight:800;min-width:34px;text-align:center}.scrub{appearance:none;-webkit-appearance:none;width:100%;height:28px;background:transparent;outline:none}.scrub::-webkit-slider-runnable-track{height:4px;border-radius:999px;background:linear-gradient(90deg,var(--accent) var(--scrub,0%),#343941 var(--scrub,0%))}.scrub::-webkit-slider-thumb{-webkit-appearance:none;width:24px;height:24px;border-radius:50%;background:#f8f8f8;border:3px solid var(--accent);margin-top:-10px}.scrub:disabled{opacity:.35}"
                + ".transport{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:6px;background:#090b0e;border:1px solid var(--line);border-radius:8px;padding:6px}.transport button{height:38px;font-size:12px;border-radius:8px;background:transparent;border-color:transparent}.transport .good{background:var(--accent);border-color:var(--accent);color:#141414}.volumeFront{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px;align-items:center;background:#090b0e;border:1px solid var(--line);border-radius:8px;padding:7px}.volumeFront button{height:38px;border-radius:8px}.volCenter{grid-column:1/-1;display:grid;grid-template-columns:1fr auto;gap:7px;align-items:center;color:var(--muted);font-size:11px}.volBar{grid-column:1/-1;height:4px;border-radius:999px;background:linear-gradient(90deg,var(--accent) var(--vol,0%),#343941 var(--vol,0%))}.quick{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px}.quick button{height:42px;font-size:12px}"
                + "textarea{background:#080a0d;color:var(--text);padding:14px;font-size:17px;line-height:1.28;resize:none;border-color:#363b44;outline:none}textarea:focus{border-color:var(--accent)}.keyboardDeck{flex:1 1 auto;display:flex;flex-direction:column;gap:10px;min-height:0}.inputSurface{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:10px}.queryBox{height:clamp(178px,35vh,270px)}.inputHelp{color:var(--muted);font-size:12px;line-height:1.28;margin:8px 4px 10px}.keyboardActions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.keyboardActions button{height:44px;border-radius:8px}.keyboardActions .wide{grid-column:1/-1}.inputLabel{display:flex;align-items:center;justify-content:space-between;color:var(--muted);font-size:12px;font-weight:750;margin:0 4px 7px}"
                + "@media(max-height:720px){main{gap:6px}.brand{font-size:18px}.pill{padding:5px 8px}.now{min-height:56px}.panel{padding:8px}.tabs button{height:31px}.transport button,.volumeFront button,.quick button,.commandRow button{height:34px}.remotePad{max-height:calc(var(--vh,1vh)*38)}.queryBox{height:32vh;min-height:150px}}@media(max-width:360px){main{padding-left:8px;padding-right:8px}.brand{font-size:17px}.pill{max-width:44%}}";
    }

    private String remoteMarkup() {
        return "<section class='top'><div class='bar'><div class='brand'>GlassyTube Remote</div><div id='state' class='pill'>Connecting</div></div></section>"
                + "<section class='panel wake'><div class='wakeTitle'>GlassyTube is sleeping</div><div class='wakeText'>The full remote is hibernated to save Glass battery. Wake GlassyTube, then controls will appear here.</div><button class='primary' onclick='wakeGlassTube()'>Open GlassyTube</button><div class='mini'>Keep this page saved to your Home Screen for quick control.</div></section>"
                + "<section class='panel now activeOnly'><div><div id='title' class='title'>No video loaded</div></div><div class='liveDot'></div><div id='meta' class='meta'></div><div id='toast' class='toast'></div></section>"
                + "<nav class='tabs activeOnly'><button class='tabRemote' onclick=\"setMode('remote')\">Remote</button><button class='tabKeyboard' onclick=\"setMode('keyboard')\">Keys</button><button class='tabMore' onclick=\"setMode('more')\">More</button></nav>"
                + "<section class='screen remoteScreen activeOnly'><div class='remotePanel'><div class='remotePad'><span class='blank'></span><button onclick=\"gt('up')\">&#9650;</button><span class='blank'></span><button onclick=\"gt('left')\">&#9664;</button><button class='select' onclick=\"gt('enter')\">Select</button><button onclick=\"gt('right')\">&#9654;</button><span class='blank'></span><button onclick=\"gt('down')\">&#9660;</button><span class='blank'></span></div><div class='commandRow'><button onclick=\"gt('back')\">Back</button><button onclick=\"gt('home')\">Home</button><button onclick=\"gt('menu')\">Menu</button><button onclick=\"setMode('keyboard')\">Keys</button></div></div><div class='timeline'><span id='posLabel' class='timeLabel'>0:00</span><input id='scrub' class='scrub' type='range' min='0' max='0' value='0' disabled><span id='durLabel' class='timeLabel'>--:--</span></div><div class='transport'><button onclick=\"seekCmd('seek_back_60')\">-60</button><button onclick=\"seekCmd('seek_back')\">-10</button><button id='playBtn' class='good' onclick='togglePlayPause()'>Play</button><button onclick=\"seekCmd('seek_forward')\">+10</button><button onclick=\"seekCmd('seek_forward_60')\">+60</button></div><div class='volumeFront'><button onclick=\"volumeCmd('volume_down')\">Vol -</button><button onclick=\"volumeCmd('mute')\">Mute</button><button onclick=\"volumeCmd('volume_up')\">Vol +</button><div class='volCenter'><span>Volume</span><span id='volLabel'>--%</span><div id='volBar' class='volBar'></div></div></div></section>"
                + "<section class='screen keyboardScreen activeOnly'><div class='keyboardDeck'><div class='inputSurface'><div class='inputLabel'><span>Smart input</span><span>URL / search / text</span></div><textarea id='query' class='queryBox' placeholder='Paste a YouTube link, search YouTube, or type text for GlassyTube'></textarea><div class='inputHelp'>Search YouTube starts a GlassyTube search. Send Text only types into the currently focused field.</div><div class='keyboardActions'><button class='primary' onclick='sendQuery()'>Open / Search</button><button onclick='sendTextOnly()'>Send Text</button><button onclick='queueQuery()'>Queue Link</button><button onclick='speakInto(\"query\")'>Speak</button><button class='wide' onclick=\"gt('back')\">Back</button></div></div></div></section>"
                + "<section class='screen moreScreen activeOnly'><div class='panel'><div class='quick'><button onclick=\"gt('captions')\">CC</button><button onclick=\"gt('favorite')\">Fav</button><button onclick=\"gt('next')\">Next</button><button onclick=\"gt('home')\">Home</button><button onclick=\"gt('back')\">Back</button><button class='danger' onclick=\"gt('exit')\">Exit</button></div></div></section>";
    }

    private String remoteScript(String token) {
        return "const token='" + escapeJs(token) + "';const qs=id=>document.getElementById(id);let last='',scrubbing=false,pendingSeekUntil=0,pendingControlUntil=0,lastStatusTimer=0;let play={state:'idle',duration:0,basePos:0,baseAt:Date.now()};"
                + "function fitViewport(){document.documentElement.style.setProperty('--vh',((window.visualViewport?visualViewport.height:innerHeight)*.01)+'px')}fitViewport();addEventListener('resize',fitViewport);if(window.visualViewport)visualViewport.addEventListener('resize',fitViewport);"
                + "function clamp(v,min,max){return Math.max(min,Math.min(max,v))}function setToast(t){const el=qs('toast');if(el)el.textContent=t||''}"
                + "async function post(path,body){const sep=path.indexOf('?')>=0?'&':'?';try{const r=await fetch(path+sep+'token='+encodeURIComponent(token),{method:'POST',body:body||''});last=await r.text();setToast(last);return last}catch(e){setToast('Connection failed');return''}}"
                + "function setMode(m){document.body.classList.remove('modeRemote','modeKeyboard','modeMore');document.body.classList.add(m==='keyboard'?'modeKeyboard':m==='more'?'modeMore':'modeRemote');if(m==='keyboard')setTimeout(()=>qs('query').focus(),80)}setMode('remote');"
                + "async function gt(c){return post('/glasstube/control?cmd='+encodeURIComponent(c))}function normState(s){return s==='ready'?'paused':(s||'idle')}function isPlaying(){return play.state==='playing'}function projected(){if(play.state!=='playing'||play.duration<=0)return play.basePos;return clamp(play.basePos+(Date.now()-play.baseAt),0,play.duration)}"
                + "function fmt(ms){if(!ms)return'0:00';const s=Math.floor(ms/1000),m=Math.floor(s/60),r=s%60;return m+':'+String(r).padStart(2,'0')}"
                + "function setPlayButton(state){const b=qs('playBtn');if(!b)return;const playing=state==='playing';b.textContent=playing?'Pause':'Play';b.classList.toggle('good',!playing);b.classList.toggle('primary',playing)}"
                + "function renderScrub(pos,dur){const s=qs('scrub'),pl=qs('posLabel'),dl=qs('durLabel');if(!s)return;const ok=dur>0;s.disabled=!ok;s.max=ok?dur:0;const safe=ok?clamp(pos||0,0,dur):0;s.value=safe;const pct=ok?((safe/dur)*100):0;s.style.setProperty('--scrub',pct+'%');pl.textContent=fmt(safe);dl.textContent=ok?fmt(dur):'--:--'}"
                + "function updateStatusModel(p){const now=Date.now();let state=normState(p.state);const dur=Number(p.durationMs)||0;let pos=Number(p.positionMs)||0;if(pendingSeekUntil>now||pendingControlUntil>now){pos=projected();state=play.state}else if(state==='playing'&&play.state==='playing'&&dur>0&&!scrubbing){const proj=projected();if(Math.abs(pos-proj)<2500||pos<proj)pos=proj}else if((state==='paused'||state==='idle')&&play.duration>0&&dur>0&&!scrubbing){const prev=projected();if(pos<prev&&Math.abs(pos-prev)<6000)pos=prev}play={state:state,duration:dur,basePos:dur>0?clamp(pos,0,dur):0,baseAt:now};setPlayButton(state);if(!scrubbing)renderScrub(play.basePos,play.duration)}"
                + "function tickScrub(){if(scrubbing)return;if(play.state==='playing'&&play.duration>0)renderScrub(projected(),play.duration)}setInterval(tickScrub,250);"
                + "function bindScrub(){const s=qs('scrub');if(!s)return;const show=()=>{const dur=Number(s.max)||0,val=Number(s.value)||0;s.style.setProperty('--scrub',(dur?val/dur*100:0)+'%');qs('posLabel').textContent=fmt(val)};s.addEventListener('input',()=>{scrubbing=true;show()});s.addEventListener('change',async()=>{const v=Math.round(Number(s.value)||0);scrubbing=false;play.basePos=v;play.baseAt=Date.now();pendingSeekUntil=Date.now()+2500;renderScrub(v,play.duration);if(play.duration>0)await gt('seek_to:'+v);setTimeout(status,500)});['pointerup','touchend','mouseup'].forEach(e=>s.addEventListener(e,()=>{if(scrubbing)setTimeout(()=>{scrubbing=false},180)}))}bindScrub();"
                + "async function togglePlayPause(){const pos=projected();play.basePos=pos;play.baseAt=Date.now();play.state=isPlaying()?'paused':'playing';pendingControlUntil=Date.now()+2500;setPlayButton(play.state);renderScrub(pos,play.duration);await gt('play_pause');setTimeout(status,450)}"
                + "async function seekCmd(c){const delta=c==='seek_back_60'?-60000:c==='seek_back'?-10000:c==='seek_forward'?10000:60000;if(play.duration>0){play.basePos=clamp(projected()+delta,0,play.duration);play.baseAt=Date.now();pendingSeekUntil=Date.now()+1800;renderScrub(play.basePos,play.duration)}await gt(c);setTimeout(status,500)}"
                + "function renderVolume(v){const pct=v&&typeof v.percent==='number'?v.percent:0;qs('volLabel').textContent=pct+'%';qs('volBar').style.setProperty('--vol',pct+'%')}async function volumeCmd(c){await gt(c);setTimeout(status,350)}"
                + "async function wakeGlassTube(){await post('/glasstube/wake');qs('state').textContent='Opening GlassyTube...';setTimeout(status,900)}"
                + "async function sendQuery(){const v=qs('query').value.trim();if(!v)return;await post(v.startsWith('http')||v.startsWith('vnd.youtube:')?'/glasstube/open':'/glasstube/search',v);qs('query').select()}"
                + "async function queueQuery(){const v=qs('query').value.trim();if(!v)return;if(!(v.startsWith('http')||v.startsWith('vnd.youtube:'))){setToast('Queue needs a YouTube link');return}await post('/glasstube/queue',v);qs('query').select()}"
                + "async function sendTextOnly(){const v=qs('query').value.trim();if(!v)return;await post('/system/text',v);qs('query').select()}"
                + "function speakInto(id){const R=window.SpeechRecognition||window.webkitSpeechRecognition;if(!R){setToast('Speech is not supported in this browser');return}const r=new R();r.interimResults=true;r.onresult=e=>{let t='';for(let i=0;i<e.results.length;i++)t+=e.results[i][0].transcript;qs(id).value=t};r.onend=()=>qs(id).focus();r.start()}"
                + "async function status(){clearTimeout(lastStatusTimer);let delay=1500;try{const r=await fetch('/status',{cache:'no-store'});const d=await r.json();const g=d.glasstube||{};const p=g.player||{};const state=normState(p.state);const live=['loading','buffering','playing','paused'].includes(state);const sleeping=g.installed&&g.active===false&&!live;document.body.classList.toggle('hibernating',sleeping);delay=sleeping?15000:(document.hidden?8000:1500);qs('state').textContent=(sleeping?'Sleeping':state)+' - '+(d.ip||'');qs('title').textContent=p.title||'No video loaded';qs('meta').textContent=[fmt(p.positionMs),fmt(p.durationMs),p.url].filter(Boolean).join(' / ');renderVolume(d.volume||{});updateStatusModel(p)}catch(e){qs('state').textContent='Remote offline';delay=15000}lastStatusTimer=setTimeout(status,delay)}"
                + "document.addEventListener('visibilitychange',()=>{if(!document.hidden)status()});status();setTimeout(()=>{if(!document.body.classList.contains('hibernating'))qs('query').focus()},300);";
    }
    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escapeJs(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
