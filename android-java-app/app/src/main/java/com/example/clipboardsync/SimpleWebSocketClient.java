package com.example.clipboardsync;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocketFactory;

public class SimpleWebSocketClient {
    public interface Callback {
        void onStatus(String status);
        void onError(Exception error);
    }

    private final String url;
    private final URI uri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();

    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean connected = false;

    public SimpleWebSocketClient(String url) {
        this.url = url;
        this.uri = URI.create(url);
    }

    public String getUrl() {
        return url;
    }

    public void connect(Callback callback) {
        executor.execute(() -> {
            try {
                connectIfNeeded();
                notifyStatus(callback, "WebSocket connected");
            } catch (Exception error) {
                notifyError(callback, error);
            }
        });
    }

    public void sendText(String text, Callback callback) {
        executor.execute(() -> {
            try {
                connectIfNeeded();
                writeTextFrame(text);
                notifyStatus(callback, "Clipboard sent");
            } catch (Exception error) {
                closeInternal();
                notifyError(callback, error);
            }
        });
    }

    public void close() {
        executor.execute(this::closeInternal);
        executor.shutdown();
    }

    private void connectIfNeeded() throws IOException {
        if (connected && socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }

        boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        if (!"ws".equalsIgnoreCase(uri.getScheme()) && !secure) {
            throw new IOException("URL must start with ws:// or wss://");
        }

        String host = uri.getHost();
        int port = uri.getPort() == -1 ? (secure ? 443 : 80) : uri.getPort();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }

        closeInternal();

        socket = secure ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();

        String key = createWebSocketKey();
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";

        outputStream.write(request.getBytes(StandardCharsets.US_ASCII));
        outputStream.flush();

        String response = readHttpHeaders();
        if (!response.toLowerCase(Locale.US).contains(" 101 ")) {
            throw new IOException("WebSocket upgrade failed: " + firstResponseLine(response));
        }

        connected = true;
    }

    private String createWebSocketKey() {
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        return Base64.encodeToString(keyBytes, Base64.NO_WRAP);
    }

    private String readHttpHeaders() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;

        while ((current = inputStream.read()) != -1) {
            buffer.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }

            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;

            if (buffer.size() > 8192) {
                throw new IOException("WebSocket response headers are too large");
            }
        }

        return buffer.toString("US-ASCII");
    }

    private String firstResponseLine(String response) {
        int end = response.indexOf("\r\n");
        return end >= 0 ? response.substring(0, end) : response;
    }

    private void writeTextFrame(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = new byte[4];
        random.nextBytes(mask);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);

        if (payload.length <= 125) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 65535) {
            frame.write(0x80 | 126);
            frame.write((payload.length >> 8) & 0xff);
            frame.write(payload.length & 0xff);
        } else {
            frame.write(0x80 | 127);
            long length = payload.length;
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((length >> (8 * i)) & 0xff));
            }
        }

        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }

        outputStream.write(frame.toByteArray());
        outputStream.flush();
    }

    private void closeInternal() {
        connected = false;

        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }

        socket = null;
        inputStream = null;
        outputStream = null;
    }

    private void notifyStatus(Callback callback, String status) {
        if (callback != null) {
            callback.onStatus(status);
        }
    }

    private void notifyError(Callback callback, Exception error) {
        if (callback != null) {
            callback.onError(error);
        }
    }
}
