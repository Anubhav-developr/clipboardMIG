# ClipboardMig

Minimal phone-to-PC clipboard sync using WebSockets.

## What Is Included

- `server/` - Node.js WebSocket relay.
- `android-java-app/` - minimal Android app written in Java.
- `chrome-extension/` - Chrome Manifest V3 extension that receives text and writes it to the PC clipboard.

## How It Works

```text
Android clipboard -> WebSocket relay -> Chrome extension -> PC clipboard
```

The phone and PC must be able to reach the same WebSocket server. The easiest first test is to run the server on the PC and keep both devices on the same Wi-Fi.

## 1. Start The WebSocket Server

```powershell
cd server
npm install
npm start
```

The server prints LAN URLs such as:

```text
ws://192.168.1.20:8080?room=demo&token=changeme
```

Use the LAN URL in both the Android app and the Chrome extension.

Security note: the sample token is only a basic guard. For anything beyond local testing, use a private network, a stronger token, and `wss://`.

## 2. Load The Chrome Extension

1. Open `chrome://extensions`.
2. Enable **Developer mode**.
3. Click **Load unpacked**.
4. Choose the `chrome-extension` folder.
5. Open the extension popup.
6. Paste the WebSocket URL printed by the server.
7. Click **Save & Reconnect**.

Chrome may ask for clipboard permission. The extension uses an offscreen document to write received text into the desktop clipboard.

The popup also keeps the latest 20 received clipboard texts in **Clipboard History**. Click any history item to copy it back to the PC clipboard, or use **Copy All** to copy all saved items as newline-separated text.

## 3. Run The Android App

Open `android-java-app` in Android Studio and run it on your phone.

In the app:

1. Paste the same WebSocket URL.
2. Tap **Start Live Sync**.
3. Copy text on the phone.
4. The text should appear in the PC clipboard.

There is also a **Send Clipboard Now** button for manual testing, and **Sync Saved History** to send the Android app's saved clipboard history to Chrome.

## Clipboard History Behavior

Android does not allow normal apps to read the phone's old system clipboard history after the fact. ClipboardMig can only transfer:

- The current clipboard item.
- Items copied while ClipboardMig live sync was running and able to observe clipboard changes.
- Items already saved inside ClipboardMig's own phone-side history.

For link collection workflows, start **Live Sync** before copying links. Later, when your PC and phone are on the same network, open Chrome extension and tap **Sync Saved History** in the Android app. The extension will show up to 20 saved items. Use **Copy All** if you want all links in the PC clipboard at once.

## Android Clipboard Limitation

Modern Android versions restrict background clipboard access. This app uses a foreground service and a clipboard listener, but some devices may only allow reliable clipboard reads while the app is visible. The manual **Send Clipboard Now** button is included for that reason.
