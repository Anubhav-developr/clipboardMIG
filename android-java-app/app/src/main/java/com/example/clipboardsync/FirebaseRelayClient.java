package com.example.clipboardsync;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FirebaseRelayClient {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void sendText(String firebaseDbUrl, String roomCode, String payload, SimpleWebSocketClient.Callback callback) {
        executor.execute(() -> {
            try {
                String url = latestUrl(firebaseDbUrl, roomCode);
                String body = withRelayFields(payload);
                putJson(url, body);
                notifyStatus(callback, "Firebase synced");
            } catch (Exception error) {
                notifyError(callback, error);
            }
        });
    }

    public void close() {
        executor.shutdown();
    }

    private String latestUrl(String firebaseDbUrl, String roomCode) throws IOException {
        String cleanDbUrl = trimTrailingSlash(firebaseDbUrl);
        if (!cleanDbUrl.startsWith("https://")) {
            throw new IOException("Firebase DB URL must start with https://");
        }

        String cleanRoom = sanitizeRoom(roomCode);
        return cleanDbUrl + "/rooms/" + urlEncode(cleanRoom) + "/latest.json";
    }

    private String withRelayFields(String payload) throws JSONException {
        JSONObject object = new JSONObject(payload);
        object.put("messageId", UUID.randomUUID().toString());
        object.put("sentAt", System.currentTimeMillis());
        return object.toString();
    }

    private void putJson(String url, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        byte[] data = body.getBytes(StandardCharsets.UTF_8);

        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Content-Length", String.valueOf(data.length));

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(data);
        }

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Firebase HTTP " + code + ": " + readResponse(connection.getErrorStream()));
        }
    }

    private String readResponse(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String sanitizeRoom(String value) {
        String result = value == null ? "" : value.trim();
        result = result.replaceAll("[.#$\\[\\]/]", "-");
        return result.isEmpty() ? "demo" : result;
    }

    private String urlEncode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private void notifyStatus(SimpleWebSocketClient.Callback callback, String status) {
        if (callback != null) {
            callback.onStatus(status);
        }
    }

    private void notifyError(SimpleWebSocketClient.Callback callback, Exception error) {
        if (callback != null) {
            callback.onError(error);
        }
    }
}

