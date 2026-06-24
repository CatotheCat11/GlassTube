# Glass Agent API Key Config

Create `/data/data/com.glassagent/files/config.json` on the rooted device before first run.

```json
{
  "auth_type": "api_key",
  "api_base": "https://api.minimax.io/v1",
  "model": "MiniMax-M3",
  "api_key": "PASTE_KEY_ON_DEVICE_ONLY"
}
```

On first launch, the app imports `api_key` into Android Keystore-backed encrypted storage and rewrites `config.json` without the `api_key` field.

After first launch, the file should look like:

```json
{
  "auth_type": "api_key",
  "api_base": "https://api.minimax.io/v1",
  "model": "MiniMax-M3"
}
```

If you rotate the key, add a new `api_key` field to `config.json` and launch the app again.
