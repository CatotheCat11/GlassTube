# Send YouTube Links To GlassyTube From iOS

GlassyTube now listens on port `8765` while the app/service is running on Glass.
Open `http://GLASS_IP:8765/` from iPhone or a PC for a small remote page.

The control endpoints require the pairing token shown on the GlassyTube `Send to Glass` card.

## Shortcut: Open On Glass

1. Create a new iOS Shortcut.
2. Add `Receive Share Sheet Input`.
3. Add `Get URLs from Input`.
4. Add `Get Contents of URL`.
5. Set URL to `http://GLASS_IP:8765/open?token=PAIRING_TOKEN`.
6. Set method to `POST`.
7. Set request body to `Text`.
8. Use the shared YouTube URL as the body.

You can find `GLASS_IP` on the GlassyTube `Send to Glass` card.

## Shortcut: Queue On Glass

Use the same shortcut but send to:

```text
http://GLASS_IP:8765/queue?token=PAIRING_TOKEN
```

`/open` starts playback immediately. `/queue` appends the video to GlassyTube's queue.

## Health Check

Open this in Safari while on the same Wi-Fi:

```text
http://GLASS_IP:8765/status
```

`/status` is read-only and does not require the token.
