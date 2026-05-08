const DEFAULT_WS_URL = "ws://127.0.0.1:8080?room=demo&token=changeme";
const DEFAULT_TRANSPORT_MODE = "firebase";
const DEFAULT_FIREBASE_DB_URL = "https://clipboardmig-default-rtdb.firebaseio.com";
const DEFAULT_FIREBASE_ROOM = "demo";
const FIREBASE_POLL_MS = 2500;
const HISTORY_LIMIT = 20;

let socket = null;
let reconnectTimer = null;
let firebasePollTimer = null;
let currentUrl = DEFAULT_WS_URL;
let currentFirebaseUrl = "";
let currentFirebaseRoom = DEFAULT_FIREBASE_ROOM;
let lastFirebaseMessageId = "";
let manualClose = false;

chrome.runtime.onInstalled.addListener(async () => {
  const stored = await chrome.storage.local.get([
    "transportMode",
    "wsUrl",
    "firebaseDbUrl",
    "firebaseRoom"
  ]);

  await chrome.storage.local.set({
    transportMode: stored.transportMode || DEFAULT_TRANSPORT_MODE,
    wsUrl: stored.wsUrl || DEFAULT_WS_URL,
    firebaseDbUrl: stored.firebaseDbUrl || DEFAULT_FIREBASE_DB_URL,
    firebaseRoom: stored.firebaseRoom || DEFAULT_FIREBASE_ROOM
  });

  await connect();
});

chrome.runtime.onStartup.addListener(connect);

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.target === "background" && message.type === "get-status") {
    chrome.storage.local.get([
      "transportMode",
      "wsUrl",
      "firebaseDbUrl",
      "firebaseRoom",
      "status",
      "lastText",
      "lastUpdatedAt",
      "history"
    ], sendResponse);
    return true;
  }

  if (message?.target === "background" && message.type === "set-connection") {
    setConnectionAndReconnect(message)
      .then(() => sendResponse({ ok: true }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (message?.target === "background" && message.type === "set-url") {
    setConnectionAndReconnect({ transportMode: "websocket", wsUrl: message.wsUrl })
      .then(() => sendResponse({ ok: true }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (message?.target === "background" && message.type === "reconnect") {
    connect()
      .then(() => sendResponse({ ok: true }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (message?.target === "background" && message.type === "copy-history-item") {
    writeClipboard(message.text || "")
      .then(() => sendResponse({ ok: true }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (message?.target === "background" && message.type === "copy-all-history") {
    copyAllHistory()
      .then((count) => sendResponse({ ok: true, count }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (message?.target === "background" && message.type === "clear-history") {
    chrome.storage.local.set({ history: [] })
      .then(() => sendResponse({ ok: true }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  return false;
});

async function setConnectionAndReconnect(message) {
  const transportMode = message.transportMode === "websocket" ? "websocket" : "firebase";
  const values = { transportMode };

  if (transportMode === "firebase") {
    const firebaseDbUrl = normalizeFirebaseDbUrl(message.firebaseDbUrl || "");
    const firebaseRoom = sanitizeRoom(message.firebaseRoom || DEFAULT_FIREBASE_ROOM);

    if (!firebaseDbUrl) {
      throw new Error("Cloud Relay URL required");
    }

    values.firebaseDbUrl = firebaseDbUrl;
    values.firebaseRoom = firebaseRoom;
  } else {
    const trimmedUrl = String(message.wsUrl || "").trim();
    if (!trimmedUrl.startsWith("ws://") && !trimmedUrl.startsWith("wss://")) {
      throw new Error("URL must start with ws:// or wss://");
    }

    values.wsUrl = trimmedUrl;
  }

  await chrome.storage.local.set(values);
  await connect();
}

async function connect() {
  const stored = await chrome.storage.local.get([
    "transportMode",
    "wsUrl",
    "firebaseDbUrl",
    "firebaseRoom"
  ]);

  stopFirebasePolling();
  closeWebSocket();

  const transportMode = stored.transportMode || DEFAULT_TRANSPORT_MODE;
  if (transportMode === "websocket") {
    await connectWebSocket(stored.wsUrl || DEFAULT_WS_URL);
    return;
  }

  await connectFirebase(stored.firebaseDbUrl || DEFAULT_FIREBASE_DB_URL, stored.firebaseRoom || DEFAULT_FIREBASE_ROOM);
}

async function connectFirebase(firebaseDbUrl, firebaseRoom) {
  currentFirebaseUrl = normalizeFirebaseDbUrl(firebaseDbUrl);
  currentFirebaseRoom = sanitizeRoom(firebaseRoom);
  lastFirebaseMessageId = "";

  if (!currentFirebaseUrl) {
    await setStatus("Add cloud relay URL, then Save & Connect");
    return;
  }

  await setStatus(`Cloud sync ready: ${currentFirebaseRoom}`);
  await pollFirebaseOnce();

  firebasePollTimer = setInterval(pollFirebaseOnce, FIREBASE_POLL_MS);
}

async function pollFirebaseOnce() {
  if (!currentFirebaseUrl || !currentFirebaseRoom) return;

  try {
    const response = await fetch(firebaseLatestUrl(currentFirebaseUrl, currentFirebaseRoom), {
      cache: "no-store"
    });

    if (!response.ok) {
      throw new Error(`Cloud relay ${response.status}`);
    }

    const data = await response.json();
    if (!data) return;

    const messageId = String(data.messageId || data.sentAt || JSON.stringify(data));
    if (messageId === lastFirebaseMessageId) return;

    lastFirebaseMessageId = messageId;
    await handleIncomingPayload(data);
  } catch (error) {
    await setStatus(`Cloud sync error: ${error.message}`);
  }
}

async function connectWebSocket(wsUrl) {
  currentUrl = wsUrl;
  manualClose = false;
  await setStatus(`Connecting to ${currentUrl}`);

  try {
    socket = new WebSocket(currentUrl);
  } catch (error) {
    await setStatus(`Connection failed: ${error.message}`);
    scheduleReconnect();
    return;
  }

  socket.onopen = () => {
    setStatus(`Connected to ${currentUrl}`);
  };

  socket.onmessage = async (event) => {
    const rawMessage = typeof event.data === "string" ? event.data : await event.data.text();

    try {
      await handleIncomingMessage(rawMessage);
    } catch (error) {
      await setStatus(`Clipboard write failed: ${error.message}`);
    }
  };

  socket.onclose = () => {
    socket = null;
    if (!manualClose) {
      setStatus("Disconnected. Reconnecting...");
      scheduleReconnect();
    }
  };

  socket.onerror = () => {
    setStatus("WebSocket error");
  };
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, 3000);
}

function closeWebSocket() {
  manualClose = true;

  if (socket) {
    socket.close();
    socket = null;
  }

  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  manualClose = false;
}

function stopFirebasePolling() {
  if (firebasePollTimer) {
    clearInterval(firebasePollTimer);
    firebasePollTimer = null;
  }
}

async function setStatus(status) {
  await chrome.storage.local.set({ status });
}

async function handleIncomingMessage(rawMessage) {
  await handleIncomingPayload(parseClipboardMessage(rawMessage));
}

async function handleIncomingPayload(message) {
  if (message.type === "historySync") {
    const entries = normalizeHistory(message.items);
    await mergeClipboardHistory(entries);

    if (entries.length > 0) {
      await writeClipboard(entries[0].text);
      await chrome.storage.local.set({
        lastText: entries[0].text.slice(0, 120),
        lastUpdatedAt: entries[0].time || new Date().toLocaleString()
      });
    }

    await setStatus(`Synced ${entries.length} history item${entries.length === 1 ? "" : "s"}`);
    return;
  }

  await writeClipboard(message.text);
  await saveClipboardHistory(message.text, message.time);
  await chrome.storage.local.set({
    lastText: message.text.slice(0, 120),
    lastUpdatedAt: message.time || new Date().toLocaleString()
  });
  await setStatus("Clipboard updated");
}

function parseClipboardMessage(rawMessage) {
  try {
    const parsed = JSON.parse(rawMessage);
    if (parsed?.source === "clipboardmig" && parsed.type === "historySync") {
      return {
        type: "historySync",
        items: parsed.items || []
      };
    }

    if (parsed?.source === "clipboardmig" && parsed.type === "clipboardText") {
      return {
        type: "clipboardText",
        text: String(parsed.text || ""),
        time: String(parsed.time || "")
      };
    }
  } catch (error) {
    // Plain text messages are still supported for older Android builds.
  }

  return {
    type: "clipboardText",
    text: String(rawMessage || ""),
    time: ""
  };
}

async function writeClipboard(text) {
  await ensureOffscreenDocument();

  const response = await chrome.runtime.sendMessage({
    target: "offscreen",
    type: "copy-text",
    text
  });

  if (!response?.ok) {
    throw new Error(response?.error || "offscreen copy failed");
  }
}

async function ensureOffscreenDocument() {
  if (await chrome.offscreen.hasDocument()) {
    return;
  }

  await chrome.offscreen.createDocument({
    url: "offscreen.html",
    reasons: [chrome.offscreen.Reason.CLIPBOARD],
    justification: "Copy text received from the paired phone to the desktop clipboard."
  });
}

async function saveClipboardHistory(text, time = "") {
  await mergeClipboardHistory([{
    text,
    time,
    savedAt: time ? "" : new Date().toISOString()
  }]);
}

async function mergeClipboardHistory(entries) {
  const result = await chrome.storage.local.get(["history"]);

  let history = normalizeHistory(result.history);

  for (let index = entries.length - 1; index >= 0; index--) {
    const entry = entries[index];
    const text = String(entry.text || "").trim();
    if (!text) continue;

    const now = new Date();
    const mergedEntry = {
      text,
      time: entry.time || now.toLocaleString(),
      savedAt: entry.savedAt || now.toISOString()
    };

    history = history.filter((item) => item.text !== text);
    history.unshift(mergedEntry);
  }

  history = history.slice(0, HISTORY_LIMIT);

  await chrome.storage.local.set({ history });
  console.log("History saved:", history);
}

async function copyAllHistory() {
  const result = await chrome.storage.local.get(["history"]);
  const history = normalizeHistory(result.history).slice(0, HISTORY_LIMIT);
  const text = history.map((item) => item.text).join("\n");

  if (!text) {
    throw new Error("No history to copy");
  }

  await writeClipboard(text);
  return history.length;
}

function normalizeHistory(history) {
  if (!Array.isArray(history)) {
    return [];
  }

  return history
    .map((item) => {
      if (typeof item === "string") {
        return {
          text: item,
          time: "",
          savedAt: ""
        };
      }

      return {
        text: String(item?.text || ""),
        time: String(item?.time || ""),
        savedAt: String(item?.savedAt || "")
      };
    })
    .filter((item) => item.text);
}

function normalizeFirebaseDbUrl(value) {
  return String(value || "").trim().replace(/\/+$/, "");
}

function sanitizeRoom(value) {
  return String(value || DEFAULT_FIREBASE_ROOM).trim().replace(/[.#$[\]/]/g, "-") || DEFAULT_FIREBASE_ROOM;
}

function firebaseLatestUrl(dbUrl, room) {
  return `${normalizeFirebaseDbUrl(dbUrl)}/rooms/${encodeURIComponent(sanitizeRoom(room))}/latest.json`;
}
