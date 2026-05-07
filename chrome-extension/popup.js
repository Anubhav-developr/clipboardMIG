const wsUrlInput = document.getElementById("ws-url");
const transportModeInput = document.getElementById("transport-mode");
const firebaseDbUrlInput = document.getElementById("firebase-db-url");
const firebaseRoomInput = document.getElementById("firebase-room");
const firebaseFields = document.getElementById("firebase-fields");
const websocketFields = document.getElementById("websocket-fields");
const statusText = document.getElementById("status");
const lastText = document.getElementById("last-text");
const lastTime = document.getElementById("last-time");
const saveButton = document.getElementById("save");
const reconnectButton = document.getElementById("reconnect");
const clearHistoryButton = document.getElementById("clear-history");
const copyAllButton = document.getElementById("copy-all");
const historyContainer = document.getElementById("history");
const historyCount = document.getElementById("history-count");
const statusDot = document.getElementById("status-dot");

saveButton.addEventListener("click", async () => {
  const response = await chrome.runtime.sendMessage({
    target: "background",
    type: "set-connection",
    transportMode: transportModeInput.value,
    wsUrl: wsUrlInput.value,
    firebaseDbUrl: firebaseDbUrlInput.value,
    firebaseRoom: firebaseRoomInput.value
  });

  if (!response?.ok) {
    statusText.textContent = response?.error || "Could not save URL";
  }
});

reconnectButton.addEventListener("click", async () => {
  await chrome.runtime.sendMessage({
    target: "background",
    type: "reconnect"
  });
  await refresh();
});

clearHistoryButton.addEventListener("click", async () => {
  await chrome.runtime.sendMessage({
    target: "background",
    type: "clear-history"
  });
  await refresh();
});

copyAllButton.addEventListener("click", async () => {
  const response = await chrome.runtime.sendMessage({
    target: "background",
    type: "copy-all-history"
  });

  statusText.textContent = response?.ok
    ? `Copied ${response.count} item${response.count === 1 ? "" : "s"}`
    : response?.error || "Could not copy history";
});

transportModeInput.addEventListener("change", updateTransportFields);

chrome.storage.onChanged.addListener(refresh);
refresh();

async function refresh() {
  const response = await chrome.runtime.sendMessage({
    target: "background",
    type: "get-status"
  });

  transportModeInput.value = response.transportMode || "firebase";
  firebaseDbUrlInput.value = response.firebaseDbUrl || "";
  firebaseRoomInput.value = response.firebaseRoom || "demo";
  wsUrlInput.value = response.wsUrl || "";
  statusText.textContent = response.status || "Not connected";
  lastText.textContent = response.lastText || "Nothing yet";
  lastTime.textContent = response.lastUpdatedAt || "";
  statusDot.classList.toggle("connected", isConnectedStatus(response.status));
  statusDot.classList.toggle("syncing", String(response.status || "").startsWith("Synced"));
  updateTransportFields();

  renderHistory(response.history || []);
}

function updateTransportFields() {
  const firebaseMode = transportModeInput.value !== "websocket";
  firebaseFields.hidden = !firebaseMode;
  websocketFields.hidden = firebaseMode;
}

function isConnectedStatus(status) {
  const value = String(status || "");
  return value.startsWith("Connected")
    || value.startsWith("Firebase relay listening")
    || value.startsWith("Clipboard updated");
}

function renderHistory(history) {
  const normalizedHistory = normalizeHistory(history).slice(0, 20);
  historyContainer.replaceChildren();
  historyCount.textContent = `${normalizedHistory.length} saved item${normalizedHistory.length === 1 ? "" : "s"}`;
  copyAllButton.disabled = normalizedHistory.length === 0;
  clearHistoryButton.disabled = normalizedHistory.length === 0;

  if (normalizedHistory.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-history";
    empty.textContent = "No clipboard history yet";
    historyContainer.appendChild(empty);
    return;
  }

  normalizedHistory.forEach((entry, index) => {
    const item = document.createElement("button");
    item.className = "history-item";
    item.type = "button";
    item.title = "Copy this item";

    const number = document.createElement("span");
    number.className = "history-number";
    number.textContent = String(index + 1);

    const content = document.createElement("span");
    content.className = "history-content";

    const text = document.createElement("span");
    text.className = "history-text";
    text.textContent = entry.text;

    const time = document.createElement("span");
    time.className = "history-time";
    time.textContent = entry.time || "";

    content.append(text, time);
    item.append(number, content);

    item.addEventListener("click", async () => {
      const response = await chrome.runtime.sendMessage({
        target: "background",
        type: "copy-history-item",
        text: entry.text
      });

      statusText.textContent = response?.ok
        ? "History item copied"
        : response?.error || "Could not copy history item";
    });

    historyContainer.appendChild(item);
  });
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
          time: ""
        };
      }

      return {
        text: String(item?.text || ""),
        time: String(item?.time || "")
      };
    })
    .filter((item) => item.text);
}
