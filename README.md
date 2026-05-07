# ClipboardMig

Minimal phone-to-PC clipboard sync using Firebase or WebSockets.

## What Is Included

- `server/` - optional Node.js WebSocket relay for local testing.
- `android-java-app/` - minimal Android app written in Java.
- `chrome-extension/` - Chrome Manifest V3 extension that receives text and writes it to the PC clipboard.
- `website/` - static download page for the app and extension.

## How It Works

```text
Android clipboard -> Firebase Realtime Database -> Chrome extension -> PC clipboard
```

Firebase mode avoids hard-coded local IP addresses. The phone and PC only need internet access and the same Firebase Database URL + room code.

WebSocket mode is still available as a local-network fallback.

## 1. Firebase Setup

1. Create a Firebase project.
2. Create a **Realtime Database**.
3. Copy the database URL, for example:

```text
https://your-project-default-rtdb.firebaseio.com
```

4. For quick personal testing, use Realtime Database test rules. This is convenient but not private enough for production clipboard data:

```json
{
  "rules": {
    "rooms": {
      "$room": {
        ".read": true,
        ".write": true
      }
    }
  }
}
```

Use a hard-to-guess room code for personal testing. For production, add Firebase Authentication and locked-down rules.

## 2. Load The Chrome Extension

1. Open `chrome://extensions`.
2. Enable **Developer mode**.
3. Click **Load unpacked**.
4. Choose the `chrome-extension` folder.
5. Open the extension popup.
6. Select **Firebase Cloud**.
7. Paste your Firebase Database URL.
8. Enter the same room code you will use on Android.
9. Click **Save & Connect**.

The popup keeps the latest 20 received clipboard texts in **Clipboard History**. Click any history item to copy it back to the PC clipboard, or use **Copy All** to copy all saved items as newline-separated text.

## 3. Run The Android App

Open `android-java-app` in Android Studio and run it on your phone.

In the app:

1. Select **Firebase Cloud**.
2. Paste the same Firebase Database URL.
3. Enter the same room code.
4. Tap **Start Capture**.
5. Copy text on the phone.
6. The text should appear in the PC clipboard.

There is also a **Send Current Clipboard** button for manual testing, and **Sync Saved History** to send the Android app's saved clipboard history to Chrome.

## Optional: Local WebSocket Mode

Use this only if you want LAN-only testing without Firebase.

### Start The WebSocket Server

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

## Clipboard History Behavior

Android does not allow normal apps to read the phone's old system clipboard history after the fact. ClipboardMig can only transfer:

- The current clipboard item.
- Items copied while ClipboardMig live sync was running and able to observe clipboard changes.
- Items already saved inside ClipboardMig's own phone-side history.

For link collection workflows, start **Live Sync** before copying links. Later, when your PC and phone are on the same network, open Chrome extension and tap **Sync Saved History** in the Android app. The extension will show up to 20 saved items. Use **Copy All** if you want all links in the PC clipboard at once.

## Android Clipboard Limitation

Modern Android versions restrict background clipboard access. This app uses a foreground service and a clipboard listener, but some devices may only allow reliable clipboard reads while the app is visible. The manual **Send Current Clipboard** button is included for that reason.
