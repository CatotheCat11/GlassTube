# Glass Remote Planned Features

This device now separates playback from the remote hub: GlassyTube is the YouTube
client, and Glass Agent is the Glass-wide remote-control host.

## Current Remote Shape

- Local HTTP host owned by Glass Agent: `http://<glass-ip>:8765`
- Control endpoints require the per-device token shown on the remote page.
- General Glass controls:
  - `POST /system/key?token=<token>&key=<name>`
  - `POST /system/text?token=<token>` with plain text body
- GlassyTube controls:
  - `POST /glasstube/open?token=<token>` with YouTube URL body
  - `POST /glasstube/control?token=<token>&cmd=<command>`
  - `POST /glasstube/text?token=<token>` for GlassyTube search or URL input
- Agent action controls:
  - `POST /agent/message?token=<token>`
  - `POST /agent/action?token=<token>&type=<type>&payload=<payload>`
  - `POST /agent/confirm?token=<token>&id=<pendingId>&decision=approve|reject`
- Status:
  - `GET /status` returns queue and playback state.

## Product Direction

- Keep the phone/browser page as the main input surface for Glass.
- Treat Glass itself as a glanceable display with lightweight gesture fallback.
- Make the remote context-aware:
  - Always show Glass navigation, keyboard, media, and volume controls.
  - Show GlassyTube-specific transport and queue controls when GlassyTube is active.
  - Add more app-specific panels over time without breaking the general remote.

## Near-Term Improvements

- Add a clearer connection/pairing screen with QR code and token reset.
- Add a visible player progress scrubber on the remote.
- Add queue reorder/remove controls.
- Add shortcut buttons for common Glass actions and launcher intents.
- Add a lightweight plugin/action registry for new panels.
- Add an endpoint that reports foreground app/activity if feasible on XE24.
- Add persistent remote settings for default panel order and compact/large layouts.
- Improve live-stream support beyond NewPipeExtractor limitations.

## AI / Agent Integration Ideas

- Use ChatGPT as the authenticated subscription-backed agent surface.
- Do not copy Codex auth caches, ChatGPT cookies, or OpenAI API keys to Glass.
- Wrap Glass Agent with a ChatGPT App/MCP service when publishing is practical.
- Keep risky device actions pending until approved from the iPhone PWA or Glass.
- Keep a local bridge scaffold in `bridge/glass-agent-bridge.mjs` for LAN testing.

## Security Notes

- The server is plain HTTP on LAN because XE24 is old and HTTPS hosting is fragile.
- Keep this on trusted Wi-Fi or a phone hotspot.
- Token-protect every endpoint that changes device state.
- Keep `/status` read-only.
- Do not add unauthenticated shell/input endpoints.
