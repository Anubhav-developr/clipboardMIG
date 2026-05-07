const DEFAULT_WS_URL = "ws://192.168.60.109:8080?room=demo&token=changeme";
const HISTORY_LIMIT = 20;

let socket = null;
let reconnectTimer = null;
let currentUrl = DEFAULT_WS_URL;
let manualClose = false;

chrome.runtime.onInstalled.addListener(async () => {
  const stored = await chrome.storage.local.get(["wsUrl"]);
  if (!stored.wsUrl) {
    await chrome.storage.local.set({ wsUrl: DEFAULT_WS_URL });
  }
  await connect();
});

chrome.runtime.onStartup.addListener(connect);

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.target === "background" && message.type === "get-status") {
    chrome.storage.local.get(["wsUrl", "status", "lastText", "lastUpdatedAt", "history"], sendResponse);
    return true;
  }

  if (message?.target === "background" && message.type === "set-url") {
    setUrlAndReconnect(message.wsUrl)
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

async function setUrlAndReconnect(wsUrl) {
  const trimmedUrl = String(wsUrl || "").trim();
  if (!trimmedUrl.startsWith("ws://") && !trimmedUrl.startsWith("wss://")) {
    throw new Error("URL must start with ws:// or wss://");
  }

  await chrome.storage.local.set({ wsUrl: trimmedUrl });
  await connect();
}

async function connect() {
  const stored = await chrome.storage.local.get(["wsUrl"]);
  currentUrl = stored.wsUrl || DEFAULT_WS_URL;
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

async function setStatus(status) {
  await chrome.storage.local.set({ status });
}

async function handleIncomingMessage(rawMessage) {
  const message = parseClipboardMessage(rawMessage);

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
