# GlassyTube Agent Notes

Last updated: 2026-06-24

## Project

This is an extended Google Glass Explorer Edition / XE24 build of GlassyTube, based on CatotheCat11/GlassTube. The public product name is now `GlassyTube`; Android package names, Java class names, and `/glasstube/*` remote endpoints intentionally remain compatible with the original GlassTube namespace.

- App package: `com.catothecat.glasstube`
- Remote package: `com.glass.remoteagent`
- Glass IP during testing: `<glass-ip>`
- Remote port: `8765`
- Remote token during testing: `<pairing-token>`

The target device is Android 4.4.4 / XE24, rooted, with ADB working.

## Current Architecture

- `MainActivity` owns Glass card navigation for Search, Queue, Recent, Favorites, Send to Glass, playlist/channel/search result cards, and direct YouTube URL handling.
- `VideoActivity` owns playback through ExoPlayer and NewPipeExtractor stream resolution.
- `GlassTubeCommandReceiver` receives commands from the remote agent and routes app commands to GlassyTube.
- `GlassTubeStatusProvider` exposes current app/player state to the remote.
- `GlassTubeServer` is the older in-app server path.
- `remoteagent` owns the main PWA remote on port `8765`.
- `glassagent` is the separate GlassAgent assistant APK (`com.glassagent`), not part of the GlassyTube remote.
- `VideoStore` persists queue, history, favorites, active state, and playback status.

## Important Device Behavior

- First launch after reinstall is slow because Android 4.4 extracts and dexopts multidex files. Logs show `MultiDexExtractor`, `DEX prep`, and sometimes Activity pause/stop timeouts. Warm launch after extraction is much faster, around 0.7-1.2 seconds in testing.
- Do not confuse post-install cold start with normal app startup.
- Glass voice services can be flaky. Direct `SpeechRecognizer` can connect but never call `onReadyForSpeech`; the app now has its own timeout so it does not hang on "Listening...".
- If in-app tap-to-speak returns `ERROR_RECOGNIZER_BUSY`, GlassyTube now shows a clear busy/retry card. The supported stable voice path remains OK Glass `find a video`.
- Raw Android `input keyevent BACK` may not affect immersive `VideoActivity`; the remote `/glasstube/control?cmd=back` path does work.
- Claude Code or other agents may be active on the same Glass. Avoid force-stopping unrelated packages unless needed.

## 2026-06-24 Public-Readiness Pass

Build results:

- `:app:assembleDebug`, `:remoteagent:assembleDebug`, `:app:lintDebug`, and `:remoteagent:lintDebug` pass with `abortOnError=true`.
- `:app:assembleRelease` and `:remoteagent:assembleRelease` pass.
- Remaining lint output is warning-only and mostly old-target/dependency/version-catalog/upstream unused resource noise. There are no current lint errors.

Security/release cleanup:

- App backup is disabled and backup/data-extraction XML denies backup/transfer.
- Remote backup is disabled and has deny-all backup/data-extraction XML.
- The old Glass development permission was removed from GlassyTube and GlassyTube Remote.
- Remote state-changing endpoints require the pairing token; unauthenticated `/glasstube/control` returned HTTP `401` in the latest device test.
- Source/doc scan outside build output found no live Glass IP, pairing token, or local Windows user path.

Latest device verification:

- Installed current debug APKs to device `015DA6FC13015016`.
- GlassyTube Remote started on port `8765`; `/status` reported GlassyTube installed and inactive before wake/open.
- `find a video` is registered as the only GlassyTube OK Glass voice-trigger activity from this project.
- GlassyTube Remote has no OK Glass voice trigger.
- Remote opened livestream `https://www.youtube.com/watch?v=vOTiJkg1voo`.
- Remote `pause`, `play`, `seek_to:10000`, and `exit` all returned success.
- Logs showed HLS master manifest selection and AAC-LC audio override for bone-conduction-friendly playback.
- Exit released ExoPlayer and abandoned audio focus.

Known XE24 noise that is still expected:

- Glass system voice service may log `ClassCastException: Long cannot be cast to Integer` even outside GlassTube app code.
- ExoPlayer/OkHttp/desugaring logs many "Could not find method/class" verifier warnings for newer API shims on Android 4.4. These did not block playback in the latest test.
- Old OMX/codec warnings are expected on Glass 1. Treat actual `Playback error` / `FATAL EXCEPTION` lines as actionable, not every verifier warning.

## Voice / OK Glass Findings

The app now declares one OK Glass voice-trigger activity:

- `FindVideoVoiceActivity`: approved Glass command `FIND_A_VIDEO`, visible as `find a video`

The earlier custom/unlisted `search youtube` command was removed because it made the OK Glass menu and recognition flow glitchy on this XE24 device. Do not re-add `SearchYouTubeVoiceActivity` unless explicitly requested and tested on-device.

Direct simulated test:

```powershell
adb shell am start -n com.catothecat.glasstube/.FindVideoVoiceActivity -a com.google.android.glass.action.VOICE_TRIGGER --es android.speech.extra.RESULTS lofi
```

Expected log:

```text
Voice search=lofi
```

The installed package resolver should show only the approved voice activity:

```text
com.google.android.glass.action.VOICE_TRIGGER
com.catothecat.glasstube/.FindVideoVoiceActivity
```

Custom unlisted commands require `com.google.android.glass.permission.DEVELOPMENT` and a `<trigger keyword="...">`, but this device does not expose a useful "allow custom voice commands" UI and the custom phrase was unreliable in real use. Treat `find a video` as the supported OK Glass entry.

Global "OK Glass from anywhere" like modern Hey Google is not safely available to normal third-party APKs on XE24. The supported model is Glass Home/timeline OK Glass directory entries. A true global wake phrase would require modifying/replacing system Glass voice components or running an always-listening microphone service, which is high-risk for stability, privacy, and battery. Do not attempt that in the stability branch without explicit approval.

## GlassAgent Findings

GlassAgent now declares one approved OK Glass trigger:

- Activity: `com.glassagent/.MainActivity`
- Command: `MAKE_A_REQUEST`
- Prompt: `Ask GlassAgent`

This replaced the invalid installed `SEARCH` command that previously caused:

```text
VoiceTriggerBuildingException: Command SEARCH must be listed in VoiceTriggers.Command
```

Current tested result:

- `adb install -r glassagent-debug.apk` succeeds on this XE24/API 19 device after lowering `minSdk` to 19.
- Voice-trigger launch succeeds in about 0.7-0.8 seconds warm.
- No `VoiceTriggerBuildingException`, theme inflate crash, SharedPreferences failure, or AndroidKeyStore crash in the final test.
- Remaining log line is a benign SELinux `getattr` denial against `/data/app` during class loading.
- 2026-06-24 hotword edge pass:
  - No-input GlassAgent hotword launch opens the app without crashing.
  - Dictated `input_speech` hotword launch opens the app without crashing.
  - Empty `input_speech` hotword launch opens the app without crashing.
  - A no-input recognizer timeout guard was added so GlassAgent does not hang forever if the Glass speech recognizer never returns.
  - The current on-device GlassAgent blocker is auth/network, not hotword activation. Screenshot showed `Auth Failed` / `Network error`.
  - Auth failure UI now hides the blank QR square and displays the readable error text.

API/storage notes:

- API 23+ devices use AndroidKeyStore AES/GCM for agent token/secret encryption.
- API 19 Glass uses an install-local AES/CBC fallback key stored in app-private SharedPreferences. This is the best practical fallback on Android 4.4, but it is not root-proof.
- The device had `/data/data/com.glassagent/shared_prefs` owned by `root:root` from earlier manual/root work. It was fixed to app UID `10013:10013` and prefs files tightened to `660`. If token storage breaks again, check ownership before debugging app code.

GlassAgent test command:

```powershell
adb logcat -c
adb shell am start -W -n com.glassagent/.MainActivity -a com.google.android.glass.action.VOICE_TRIGGER
adb logcat -d -t 1200 | Select-String -Pattern "VoiceTriggers|VoiceTriggerBuildingException|FATAL|AndroidRuntime|SharedPreferencesImpl|KeyGenParameterSpec|AndroidKeyStore"
```

## Remote Findings

Remote endpoint:

```text
http://<glass-ip>:8765/remote
```

Useful endpoints:

```text
GET  /status?token=<token>
POST /glasstube/open?token=<token>&url=<youtube-url>
POST /glasstube/search?token=<token>&text=<query>
POST /glasstube/control?token=<token>&cmd=back
POST /glasstube/control?token=<token>&cmd=home
POST /glasstube/control?token=<token>&cmd=enter
POST /glasstube/control?token=<token>&cmd=play_pause
POST /glasstube/control?token=<token>&cmd=volume_up
POST /glasstube/control?token=<token>&cmd=volume_down
GET  /logs?token=<token>
GET  /glasstube/logs?token=<token>
```

Remote navigation issue fixed:

- `home` then `enter` now activates the Search YouTube card deterministically.
- Foreground card commands are routed through `MainActivity.onNewIntent`, not a fragile second internal broadcast.
- Remote/player `back` from `VideoActivity` returns to `MainActivity`.

Diagnostics:

- GlassTube now writes a rolling diagnostic log to app-private storage.
- The remote agent exposes that log through `/logs` and `/glasstube/logs`.
- The log includes MainActivity launch/intent events, remote commands, voice results/timeouts, search/playlist/channel failures, stream selection, HLS selection, playback errors, and audio-track selection.
- The app also attempts to write `/sdcard/GlassTube/glasstube.log`, but on some Glass builds external storage writes can fail. Treat the provider-backed remote `/logs` endpoint as the reliable path.

## Playback Findings

The test livestream was:

```text
https://www.youtube.com/watch?v=vOTiJkg1voo
```

Initial livestream failure was caused by removing the HLS module during dependency trimming:

```text
No suitable media source factory found for content type: 2
```

Fix:

```gradle
implementation 'com.google.android.exoplayer:exoplayer-hls:2.19.1'
```

The livestream was later verified as playing through `/status`:

```json
"state":"playing"
```

Glass audio is mono/bone-conduction. Playback now prefers bone-conduction-friendly audio:

- Maximum audio channel count set to 2.
- AAC-LC (`mp4a.40.2`) is preferred over HE-AAC where available.
- Logs include selected audio track channel/bitrate info.

Expected log when override succeeds:

```text
Forced bone-conduction friendly AAC-LC audio track
```

Known ExoPlayer warnings on Android 4.4 are expected and not necessarily fatal:

```text
Could not find class android.media.MediaCodec$CodecException
Could not find method android.media.AudioTrack.write(ByteBuffer...)
```

## Buffering / UI Findings

The Glass buffering spinner/slider could stay visible after playback began. Fix:

- Hide the indeterminate slider on `STATE_READY`.
- Hide it again when `onIsPlayingChanged(true)` fires.

Swipe/back behavior changed:

- From search/results/loading/error/list screens, Back returns to the GlassTube home/search menu.
- From video, swipe-down / remote back returns to GlassTube instead of closing the app when possible.
- `MainActivity` is no longer finished immediately after opening a video, so there is a useful screen underneath the player.

## Startup / Performance Findings

Dependency trimming already done:

- Removed Glide and replaced thumbnail loading with a small `BitmapFactory` loader.
- Removed Zxing.
- Removed direct lifecycle/core-ktx declarations.
- Made OpenPrism compile-only so the Glass runtime provides GDK classes.
- Kept ExoPlayer core/ui/hls, NewPipeExtractor, OkHttp, NanoHTTPD, multidex.

Remaining startup cost is mostly:

- NewPipeExtractor transitive dependencies, including Rhino/protobuf.
- ExoPlayer and AndroidX transitive dependencies.
- Multidex extraction/dexopt on Android 4.4.

Do not remove `exoplayer-hls`; it breaks livestream playback.

## Build / Install

Use Android Studio JBR and SDK:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
.\gradlew.bat --max-workers=1 :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Use `--max-workers=1` if Gradle transform cache races occur.

## Useful Test Commands

Check device:

```powershell
adb devices
adb shell dumpsys window windows | findstr /i "mCurrentFocus mFocusedApp"
```

Check voice registration:

```powershell
adb shell dumpsys package com.catothecat.glasstube | findstr /i "VOICE_TRIGGER DIRECTORY FindVideoVoiceActivity"
```

Fetch persistent GlassTube logs:

```powershell
curl.exe -s "http://<glass-ip>:8765/logs?token=<pairing-token>"
adb shell run-as com.catothecat.glasstube cat files/glasstube.log
```

Test approved hotword activity:

```powershell
adb logcat -c
adb shell am start -n com.catothecat.glasstube/.FindVideoVoiceActivity -a com.google.android.glass.action.VOICE_TRIGGER --es android.speech.extra.RESULTS lofi
adb logcat -d -t 1200 | Select-String -Pattern "Voice search=|Search failed|FATAL|AndroidRuntime|Exception"
```

Test remote status:

```powershell
curl.exe -s "http://<glass-ip>:8765/status?token=<pairing-token>"
```

Test livestream:

```powershell
curl.exe -s -X POST "http://<glass-ip>:8765/glasstube/open?token=<pairing-token>&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DvOTiJkg1voo"
adb logcat -d -t 3000 | Select-String -Pattern "Playing HLS|Forced bone|Playback error|No suitable media source|FATAL|AndroidRuntime|Exception"
```

## Current Risk Areas

- OK Glass menu cache may need a reboot to show newly installed voice commands.
- Direct app-level speech recognition can time out on Glass; remote search remains the reliable fallback.
- Cold start after reinstall remains slow due to multidex on Android 4.4.
- Live video depends on YouTube HLS manifests, which can change or expire.
- Some ExoPlayer warnings are expected on KitKat; focus on actual `Playback error` and app status.
- The remote `/status` endpoint may briefly time out while Android 4.4 is extracting multidex files immediately after reinstall. Warm status reads are normal.
- GlassAgent hotword activation can be verified independently of AI auth. If it opens to `Auth Failed`, fix `/data/data/com.glassagent/files/config.json`, network/TLS, or the configured endpoint before debugging hotword registration.

## Do Not Regress

- Do not remove `exoplayer-hls`.
- Do not finish `MainActivity` after launching `VideoActivity`; it is needed for swipe-down/back navigation.
- Keep `FindVideoVoiceActivity` as the only OK Glass voice-trigger activity unless a new command is explicitly requested and verified on-device.
- Do not rely on a hidden "allow custom voice" UI; this device does not expose it.
- Do not assume raw `adb shell input keyevent 4` is equivalent to Glass swipe-down in immersive playback.
