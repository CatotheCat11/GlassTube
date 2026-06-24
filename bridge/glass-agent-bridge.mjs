import http from "node:http";

const GLASS_URL = process.env.GLASS_AGENT_URL || "";
const GLASS_TOKEN = process.env.GLASS_AGENT_TOKEN || "";
const PORT = Number(process.env.PORT || 8787);

if (!GLASS_URL) {
  console.error("Set GLASS_AGENT_URL, for example http://<glass-ip>:8765");
  process.exit(1);
}

function sendJson(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json",
    "cache-control": "no-store",
  });
  res.end(text);
}

async function glass(path, init = {}) {
  const sep = path.includes("?") ? "&" : "?";
  const response = await fetch(`${GLASS_URL}${path}${sep}token=${encodeURIComponent(GLASS_TOKEN)}`, init);
  const text = await response.text();
  try {
    return { status: response.status, body: JSON.parse(text) };
  } catch {
    return { status: response.status, body: { ok: response.ok, text } };
  }
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  const text = Buffer.concat(chunks).toString("utf8");
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { text };
  }
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/health") {
      const status = await glass("/status", { method: "GET" });
      sendJson(res, 200, { ok: true, glass: status.body });
      return;
    }
    if (req.method === "GET" && req.url === "/tools") {
      sendJson(res, 200, {
        tools: [
          "get_glass_status",
          "show_message_on_glass",
          "propose_glass_action",
          "open_youtube_on_glass",
          "send_text_to_glass",
          "control_glass_media",
        ],
      });
      return;
    }
    if (req.method !== "POST") {
      sendJson(res, 404, { ok: false, error: "Unknown endpoint" });
      return;
    }
    const body = await readBody(req);
    if (req.url === "/tools/get_glass_status") {
      const status = await glass("/status", { method: "GET" });
      sendJson(res, status.status, status.body);
      return;
    }
    if (req.url === "/tools/show_message_on_glass") {
      const result = await glass("/agent/message", { method: "POST", body: body.text || body.message || "" });
      sendJson(res, result.status, result.body);
      return;
    }
    if (req.url === "/tools/propose_glass_action") {
      const params = new URLSearchParams({
        type: body.type || "message",
        payload: body.payload || body.text || "",
      });
      const result = await glass(`/agent/action?${params}`, { method: "POST" });
      sendJson(res, result.status, result.body);
      return;
    }
    if (req.url === "/tools/open_youtube_on_glass") {
      const params = new URLSearchParams({
        type: "open_youtube",
        payload: body.url || "",
      });
      const result = await glass(`/agent/action?${params}`, { method: "POST" });
      sendJson(res, result.status, result.body);
      return;
    }
    if (req.url === "/tools/send_text_to_glass") {
      const params = new URLSearchParams({
        type: "system_text",
        payload: body.text || "",
      });
      const result = await glass(`/agent/action?${params}`, { method: "POST" });
      sendJson(res, result.status, result.body);
      return;
    }
    if (req.url === "/tools/control_glass_media") {
      const params = new URLSearchParams({
        type: "system_key",
        payload: body.key || body.command || "play_pause",
      });
      const result = await glass(`/agent/action?${params}`, { method: "POST" });
      sendJson(res, result.status, result.body);
      return;
    }
    sendJson(res, 404, { ok: false, error: "Unknown endpoint" });
  } catch (error) {
    sendJson(res, 500, { ok: false, error: error.message });
  }
});

server.listen(PORT, () => {
  console.log(`Glass Agent bridge listening on http://127.0.0.1:${PORT}`);
  console.log(`Forwarding to ${GLASS_URL}`);
});
