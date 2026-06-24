# Glass Agent Bridge

This bridge is the non-credential sidecar for ChatGPT/MCP integration.

It does not run Codex CLI and does not store OpenAI API keys or Codex tokens.
ChatGPT subscription authentication must happen in ChatGPT. A hosted ChatGPT
App/MCP service can call this bridge, and this bridge forwards approved tool
requests to the Glass Agent app on the LAN.

## Run locally

```powershell
$env:GLASS_AGENT_URL='http://<glass-ip>:8765'
$env:GLASS_AGENT_TOKEN='<token from Glass Agent remote page>'
node bridge/glass-agent-bridge.mjs
```

## Tool endpoints

- `GET /health`
- `GET /tools`
- `POST /tools/get_glass_status`
- `POST /tools/show_message_on_glass` with `{ "text": "..." }`
- `POST /tools/propose_glass_action` with `{ "type": "...", "payload": "..." }`
- `POST /tools/open_youtube_on_glass` with `{ "url": "..." }`
- `POST /tools/send_text_to_glass` with `{ "text": "..." }`
- `POST /tools/control_glass_media` with `{ "key": "play_pause" }`

Risky actions are queued in Glass Agent and must be approved from the iPhone PWA
or Glass Agent confirmation UI before execution.
