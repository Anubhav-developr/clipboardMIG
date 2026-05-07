const http = require("http");
const os = require("os");
const WebSocket = require("ws");

const PORT = Number(process.env.PORT || 8080);
const REQUIRED_TOKEN = process.env.CLIPBOARD_TOKEN || "changeme";

const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  res.writeHead(200, { "content-type": "text/plain" });
  res.end("ClipboardMig WebSocket relay is running.\n");
});

const wss = new WebSocket.Server({ server });
const rooms = new Map();

function joinRoom(room, ws) {
  if (!rooms.has(room)) {
    rooms.set(room, new Set());
  }
  rooms.get(room).add(ws);
}

function leaveRoom(room, ws) {
  const clients = rooms.get(room);
  if (!clients) return;

  clients.delete(ws);
  if (clients.size === 0) {
    rooms.delete(room);
  }
}

function broadcast(room, sender, message, isBinary) {
  const clients = rooms.get(room);
  if (!clients) return;

  for (const client of clients) {
    if (client !== sender && client.readyState === WebSocket.OPEN) {
      client.send(message, { binary: isBinary });
    }
  }
}

wss.on("connection", (ws, request) => {
  const url = new URL(request.url, `http://${request.headers.host}`);
  const room = url.searchParams.get("room") || "demo";
  const token = url.searchParams.get("token") || "";

  if (REQUIRED_TOKEN && token !== REQUIRED_TOKEN) {
    ws.close(1008, "Invalid token");
    return;
  }

  joinRoom(room, ws);
  console.log(`Client joined room "${room}". Active clients: ${rooms.get(room).size}`);

  ws.on("message", (message, isBinary) => {
    const text = isBinary ? "<binary>" : message.toString();
    console.log(`Room "${room}" received: ${text.slice(0, 120)}`);
    broadcast(room, ws, message, isBinary);
  });

  ws.on("close", () => {
    leaveRoom(room, ws);
    console.log(`Client left room "${room}".`);
  });

  ws.on("error", (error) => {
    console.error(`WebSocket error in room "${room}":`, error.message);
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`ClipboardMig relay running on port ${PORT}`);
  console.log(`Token: ${REQUIRED_TOKEN || "(none)"}`);
  console.log("Use one of these URLs on Android and Chrome:");

  for (const networkInterface of Object.values(os.networkInterfaces())) {
    for (const address of networkInterface || []) {
      if (address.family === "IPv4" && !address.internal) {
        console.log(`  ws://${address.address}:${PORT}?room=demo&token=${encodeURIComponent(REQUIRED_TOKEN)}`);
      }
    }
  }

  console.log(`  ws://127.0.0.1:${PORT}?room=demo&token=${encodeURIComponent(REQUIRED_TOKEN)} (PC only)`);
});

