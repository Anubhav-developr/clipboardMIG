package com.example.clipboardsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

public class ClipboardSyncService extends Service {
    public static final String ACTION_START = "com.example.clipboardsync.START";
    public static final String ACTION_SEND_NOW = "com.example.clipboardsync.SEND_NOW";
    public static final String ACTION_SYNC_HISTORY = "com.example.clipboardsync.SYNC_HISTORY";
    public static final String ACTION_STOP = "com.example.clipboardsync.STOP";
    public static final String EXTRA_WS_URL = "ws_url";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "clipboardmig_sync";

    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private ClipboardHistoryStore historyStore;
    private SimpleWebSocketClient webSocketClient;
    private String wsUrl;
    private String lastSentText = "";
    private boolean listenerRegistered = false;

    @Override
    public void onCreate() {
        super.onCreate();

        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        historyStore = new ClipboardHistoryStore(this);
        clipListener = this::sendCurrentClipboard;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopSync();
            return START_NOT_STICKY;
        }

        wsUrl = intent != null ? intent.getStringExtra(EXTRA_WS_URL) : wsUrl;
        if (wsUrl == null || wsUrl.trim().isEmpty()) {
            Toast.makeText(this, "Missing WebSocket URL", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Listening for clipboard changes"));
        ensureWebSocketClient();

        if (ACTION_SEND_NOW.equals(action)) {
            sendCurrentClipboard(true);
            return START_NOT_STICKY;
        }

        if (ACTION_SYNC_HISTORY.equals(action)) {
            sendSavedHistory(true);
            return START_NOT_STICKY;
        }

        startListening();
        sendSavedHistory(false);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSync();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startListening() {
        if (!listenerRegistered && clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(clipListener);
            listenerRegistered = true;
            Toast.makeText(this, "Clipboard live sync started", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendCurrentClipboard() {
        sendCurrentClipboard(false);
    }

    private void sendCurrentClipboard(boolean stopAfterSend) {
        String text = readClipboardText();
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, "Clipboard is empty or unavailable", Toast.LENGTH_SHORT).show();
            if (stopAfterSend) {
                stopSelf();
            }
            return;
        }

        if (text.equals(lastSentText)) {
            if (stopAfterSend) {
                stopSelf();
            }
            return;
        }

        ClipboardHistoryStore.Entry entry = historyStore.add(text);
        if (entry == null) {
            return;
        }

        lastSentText = text;
        ensureWebSocketClient();
        webSocketClient.sendText(historyStore.createClipboardTextPayload(entry), new SimpleWebSocketClient.Callback() {
            @Override
            public void onStatus(String status) {
                updateNotification(status);
                if (stopAfterSend) {
                    stopSelf();
                }
            }

            @Override
            public void onError(Exception error) {
                updateNotification("WebSocket error: " + error.getMessage());
                if (stopAfterSend) {
                    stopSelf();
                }
            }
        });
    }

    private void sendSavedHistory(boolean stopAfterSend) {
        recordCurrentClipboard();

        int count = historyStore.count();
        if (count == 0) {
            updateNotification("No saved phone history yet");
            Toast.makeText(this, "No saved phone history yet", Toast.LENGTH_SHORT).show();
            if (stopAfterSend) {
                stopSelf();
            }
            return;
        }

        ensureWebSocketClient();
        webSocketClient.sendText(historyStore.createHistorySyncPayload(), new SimpleWebSocketClient.Callback() {
            @Override
            public void onStatus(String status) {
                updateNotification("Synced " + count + " saved items");
                if (stopAfterSend) {
                    stopSelf();
                }
            }

            @Override
            public void onError(Exception error) {
                updateNotification("WebSocket error: " + error.getMessage());
                if (stopAfterSend) {
                    stopSelf();
                }
            }
        });
    }

    private void recordCurrentClipboard() {
        String text = readClipboardText();
        if (text != null && !text.isEmpty()) {
            historyStore.add(text);
        }
    }

    private String readClipboardText() {
        try {
            if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
                return null;
            }

            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                return null;
            }

            CharSequence text = clipData.getItemAt(0).coerceToText(this);
            return text != null ? text.toString() : null;
        } catch (SecurityException error) {
            return null;
        }
    }

    private void ensureWebSocketClient() {
        if (webSocketClient != null && webSocketClient.getUrl().equals(wsUrl)) {
            return;
        }

        if (webSocketClient != null) {
            webSocketClient.close();
        }

        webSocketClient = new SimpleWebSocketClient(wsUrl);
        webSocketClient.connect(new SimpleWebSocketClient.Callback() {
            @Override
            public void onStatus(String status) {
                updateNotification(status);
            }

            @Override
            public void onError(Exception error) {
                updateNotification("WebSocket error: " + error.getMessage());
            }
        });
    }

    private void stopSync() {
        if (clipboardManager != null && listenerRegistered) {
            clipboardManager.removePrimaryClipChangedListener(clipListener);
            listenerRegistered = false;
        }

        if (webSocketClient != null) {
            webSocketClient.close();
            webSocketClient = null;
        }

        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("ClipboardMig")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ClipboardMig sync",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
