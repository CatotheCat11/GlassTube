package com.catothecat.glasstube;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class GlassTubeServer extends NanoHTTPD {
    private static final String TAG = "GlassTubeServer";
    public static final int PORT = 8765;

    private final Context context;
    private int previousMusicVolume = -1;

    public GlassTubeServer(Context context) throws IOException {
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
            return json("{\"ok\":true,\"app\":\"GlassyTube\",\"queue\":" +
                    VideoStore.getQueue(context).size() +
                    ",\"player\":" + VideoStore.getPlaybackJson(context) + "}");
        }
        if (Method.POST.equals(method) && ("/open".equals(uri) || "/queue".equals(uri)
                || "/control".equals(uri) || "/text".equals(uri)
                || "/system/key".equals(uri) || "/system/text".equals(uri))) {
            String body = readBody(session);
            if (!isAuthorized(session)) {
                return response(Response.Status.UNAUTHORIZED, "application/json",
                        "{\"ok\":false,\"error\":\"Pairing token required\"}");
            }
            if ("/control".equals(uri)) {
                return handleControl(session, body);
            }
            if ("/text".equals(uri)) {
                return handleText(session, body);
            }
            if ("/system/key".equals(uri)) {
                return handleSystemKey(session, body);
            }
            if ("/system/text".equals(uri)) {
                return handleSystemText(session, body);
            }
            String url = readUrl(session, body);
            if (!isYouTubeUrl(url)) {
                return jsonError("Expected a YouTube URL in body, form field 'url', or query parameter 'url'.");
            }
            if ("/queue".equals(uri)) {
                VideoStore.enqueue(context, "Queued video", url);
                return json("{\"ok\":true,\"queued\":true}");
            }
            openUrl(url);
            return json("{\"ok\":true,\"opened\":true}");
        }
        return response(Response.Status.NOT_FOUND, "application/json",
                "{\"ok\":false,\"error\":\"Use GET /, GET /status, POST /open, /queue, /control, /text, /system/key, or /system/text\"}");
    }

    private boolean isAuthorized(IHTTPSession session) {
        String token = session.getHeaders().get("x-glasstube-token");
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
        if (body != null && body.length() > 0) {
            return body.trim();
        }
        return "";
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

    private Response handleControl(IHTTPSession session, String body) {
        String command = firstNonEmpty(session.getParms().get("cmd"), session.getParms().get("command"), body);
        if (command == null) {
            return jsonError("Expected a control command.");
        }
        command = command.trim().toLowerCase(Locale.ROOT);
        if ("volume_up".equals(command) || "volup".equals(command)) {
            adjustVolume(AudioManager.ADJUST_RAISE);
            return json("{\"ok\":true,\"command\":\"volume_up\"}");
        }
        if ("volume_down".equals(command) || "voldown".equals(command)) {
            adjustVolume(AudioManager.ADJUST_LOWER);
            return json("{\"ok\":true,\"command\":\"volume_down\"}");
        }
        if ("mute".equals(command)) {
            toggleMute();
            return json("{\"ok\":true,\"command\":\"mute\"}");
        }
        sendRemoteCommand(command, null);
        return json("{\"ok\":true,\"command\":\"" + escapeJson(command) + "\"}");
    }

    private Response handleText(IHTTPSession session, String body) {
        String text = firstNonEmpty(session.getParms().get("text"), session.getParms().get("q"), body);
        if (text == null || text.trim().length() == 0) {
            return jsonError("Expected text.");
        }
        sendRemoteCommand("text", text.trim());
        return json("{\"ok\":true,\"text\":true}");
    }

    private Response handleSystemKey(IHTTPSession session, String body) {
        String key = firstNonEmpty(session.getParms().get("key"), session.getParms().get("code"), body);
        String keyCode = normalizeKeyCode(key);
        if (keyCode == null) {
            return jsonError("Unsupported key. Use home, back, enter, dpad_up/down/left/right, menu, search, media keys, volume, or a numeric keycode.");
        }
        boolean ok = runInputCommand("input keyevent " + keyCode);
        return json("{\"ok\":" + ok + ",\"key\":\"" + escapeJson(keyCode) + "\"}");
    }

    private Response handleSystemText(IHTTPSession session, String body) {
        String text = firstNonEmpty(session.getParms().get("text"), session.getParms().get("q"), body);
        if (text == null || text.length() == 0) {
            return jsonError("Expected text.");
        }
        boolean ok = runInputCommand("input text '" + escapeShellSingleQuoted(toAndroidInputText(text)) + "'");
        return json("{\"ok\":" + ok + ",\"systemText\":true}");
    }

    private void adjustVolume(int direction) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        }
    }

    private void toggleMute() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (current > 0) {
            previousMusicVolume = current;
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI);
        } else {
            int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int restore = previousMusicVolume > 0 ? Math.min(previousMusicVolume, max) : Math.max(1, max / 3);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, AudioManager.FLAG_SHOW_UI);
        }
    }

    private void sendRemoteCommand(String command, String text) {
        Intent intent = new Intent(VideoStore.ACTION_REMOTE_COMMAND);
        intent.setPackage(context.getPackageName());
        intent.putExtra(VideoStore.EXTRA_COMMAND, command);
        if (text != null) {
            intent.putExtra(VideoStore.EXTRA_TEXT, text);
        }
        context.sendBroadcast(intent, "com.glass.remoteagent.permission.CONTROL");
    }

    private boolean runInputCommand(String command) {
        return runShell("sh", "-c", command) || runShell("su", "-c", command);
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
        if (key == null) {
            return null;
        }
        String clean = key.trim().toLowerCase(Locale.ROOT);
        if (clean.matches("\\d{1,3}")) {
            return clean;
        }
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
        if ("media_play".equals(clean) || "play".equals(clean)) return "126";
        if ("media_pause".equals(clean) || "pause".equals(clean)) return "127";
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

    private void openUrl(String url) {
        VideoStore.addHistory(context, "Pushed video", url);
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(VideoStore.ACTION_OPEN_URL);
        intent.putExtra(VideoStore.EXTRA_URL, url);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private boolean isYouTubeUrl(String url) {
        if (url == null) {
            return false;
        }
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
        if (one != null && one.trim().length() > 0) {
            return one;
        }
        if (two != null && two.trim().length() > 0) {
            return two;
        }
        if (three != null && three.trim().length() > 0) {
            return three;
        }
        return null;
    }

    private Response json(String body) {
        return response(Response.Status.OK, "application/json", body);
    }

    private Response html(String body) {
        return response(Response.Status.OK, "text/html; charset=utf-8", body);
    }

    private Response jsonError(String error) {
        return response(Response.Status.BAD_REQUEST, "application/json",
                "{\"ok\":false,\"error\":\"" + error.replace("\"", "'") + "\"}");
    }

    private Response response(Response.Status status, String mimeType, String body) {
        Response response = newFixedLengthResponse(status, mimeType, body);
        response.addHeader("Connection", "close");
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private String remotePage() {
        String token = RemoteSecurity.getToken(context);
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Glass Remote</title>"
                + "<style>body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;margin:16px;background:#101010;color:#f5f5f5}"
                + "h1{font-size:28px;margin:0 0 8px}h2{font-size:18px;margin:18px 0 8px}.muted{color:#aaa;font-size:14px}"
                + "input,textarea,button{font-size:17px;padding:12px;margin:6px 0;width:100%;box-sizing:border-box;border-radius:8px;border:1px solid #555}"
                + "textarea,input{background:#1b1b1b;color:#fff}button{background:#2d6cdf;color:white;border:0}.secondary{background:#303030}.danger{background:#7d2d2d}"
                + ".grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}.grid button{margin:0}.pad{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}.pad button{height:58px;margin:0}"
                + ".dpad{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;max-width:360px;margin:auto}.dpad button{height:62px;margin:0}.empty{visibility:hidden}"
                + ".panel{border-top:1px solid #333;margin-top:18px;padding-top:12px}.hidden{display:none}.box{margin-top:16px;color:#bbb;word-break:break-word;white-space:pre-wrap;font-size:13px}</style>"
                + "</head><body><h1>Glass Remote</h1><div class='muted'>Token: " + escapeHtml(token) + "</div>"
                + "<div class='panel'><h2>Glass Navigation</h2><div class='dpad'>"
                + "<button onclick=\"sysKey('home')\">Home</button><button onclick=\"sysKey('up')\">Up</button><button onclick=\"sysKey('back')\">Back</button>"
                + "<button onclick=\"sysKey('left')\">Left</button><button onclick=\"sysKey('enter')\">Select</button><button onclick=\"sysKey('right')\">Right</button>"
                + "<button onclick=\"sysKey('menu')\">Menu</button><button onclick=\"sysKey('down')\">Down</button><button onclick=\"sysKey('search')\">Search</button></div></div>"
                + "<div class='panel'><h2>Glass Keyboard</h2><textarea id='sysText' rows='3' placeholder='Type into the focused Glass app'></textarea>"
                + "<button onclick='sendSystemText()'>Send to focused app</button><div class='grid'><button class='secondary' onclick=\"sysKey('delete')\">Delete</button><button class='secondary' onclick=\"sysKey('enter')\">Enter</button></div></div>"
                + "<div class='panel'><h2>Media / Volume</h2><div class='pad'>"
                + "<button onclick=\"sysKey('previous')\">Prev</button><button onclick=\"sysKey('play_pause')\">Play</button><button onclick=\"sysKey('next')\">Next</button>"
                + "<button onclick=\"sysKey('volume_down')\">Vol -</button><button onclick=\"sysKey('mute')\">Mute</button><button onclick=\"sysKey('volume_up')\">Vol +</button></div></div>"
                + "<div id='glassTubePanel' class='panel hidden'><h2>GlassyTube</h2><input id='url' placeholder='Paste YouTube URL'>"
                + "<button onclick=\"send('/open')\">Open now</button><button class='secondary' onclick=\"send('/queue')\">Add to queue</button>"
                + "<div class='pad'><button onclick=\"control('seek_back_60')\">-60</button><button onclick=\"control('play_pause')\">Play</button><button onclick=\"control('seek_forward_60')\">+60</button>"
                + "<button onclick=\"control('seek_back')\">-10</button><button onclick=\"control('next')\">Next</button><button onclick=\"control('seek_forward')\">+10</button>"
                + "<button onclick=\"control('volume_down')\">Vol -</button><button onclick=\"control('captions')\">CC</button><button onclick=\"control('volume_up')\">Vol +</button></div>"
                + "<div class='grid'><button class='secondary' onclick=\"control('favorite')\">Favorite</button><button class='danger' onclick=\"control('exit')\">Exit video</button></div>"
                + "<textarea id='text' rows='2' placeholder='Search or URL for GlassyTube'></textarea><button class='secondary' onclick='sendText()'>Send to GlassyTube</button></div>"
                + "<button class='secondary' onclick='status()'>Refresh status</button><div class='box' id='out'>"
                + escapeHtml(token) + "</div><script>"
                + "const token='" + escapeJs(token) + "';"
                + "function show(o){document.getElementById('out').textContent=typeof o==='string'?o:JSON.stringify(o,null,2);}"
                + "async function send(path){const u=document.getElementById('url').value.trim();"
                + "const r=await fetch(path+'?token='+encodeURIComponent(token),{method:'POST',body:u});"
                + "show(await r.text());}"
                + "async function control(cmd){const r=await fetch('/control?token='+encodeURIComponent(token)+'&cmd='+encodeURIComponent(cmd),{method:'POST'});"
                + "show(await r.text());}"
                + "async function sendText(){const t=document.getElementById('text').value.trim();const r=await fetch('/text?token='+encodeURIComponent(token),{method:'POST',body:t});"
                + "show(await r.text());}"
                + "async function sysKey(key){const r=await fetch('/system/key?token='+encodeURIComponent(token)+'&key='+encodeURIComponent(key),{method:'POST'});show(await r.text());}"
                + "async function sendSystemText(){const t=document.getElementById('sysText').value;const r=await fetch('/system/text?token='+encodeURIComponent(token),{method:'POST',body:t});show(await r.text());}"
                + "async function status(){const r=await fetch('/status');const data=await r.json();const p=data.player||{};"
                + "const active=['loading','ready','playing','buffering','paused','error','unsupported'].indexOf(p.state)>=0;"
                + "document.getElementById('glassTubePanel').className=active?'panel':'panel hidden';show(data);}"
                + "status();setInterval(status,5000);</script></body></html>";
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
