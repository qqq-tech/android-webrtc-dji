package com.drone.djiwebrtc.network;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.drone.djiwebrtc.core.SignalingTransport;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Manages the WebSocket connection to the Pion relay using the shared signaling format.
 */
public class PionSignalingClient extends WebSocketListener implements SignalingTransport {
    private static final String TAG = "PionSignalingClient";

    public interface Listener {
        void onOpen();
        void onMessage(JSONObject message);
        void onError(String description, @Nullable String code);
        void onClosed();
    }

    private final OkHttpClient httpClient;
    private final String requestUrl;
    private final Queue<JSONObject> pendingMessages = new ConcurrentLinkedQueue<>();
    private volatile boolean connected = false;
    private volatile WebSocket webSocket;
    private Listener listener;

    public PionSignalingClient(String baseUrl, String role, String streamId) {
        this.httpClient = new OkHttpClient();
        this.requestUrl = buildRequestUrl(baseUrl, role, streamId);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void connect() {
        if (webSocket != null) {
            return;
        }
        Request request = new Request.Builder().url(requestUrl).build();
        webSocket = httpClient.newWebSocket(request, this);
    }

    public synchronized void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "client disconnect");
            webSocket = null;
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public synchronized void send(JSONObject message) {
        if (webSocket != null && connected) {
            boolean success = webSocket.send(message.toString());
            if (!success) {
                Log.w(TAG, "Failed to send signaling message, queueing");
                pendingMessages.add(message);
            }
        } else {
            pendingMessages.add(message);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        Log.i(TAG, "Connected to Pion relay at " + requestUrl);
        connected = true;
        flushPending();
        if (listener != null) {
            listener.onOpen();
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JSONObject payload = new JSONObject(text);
            if (listener != null) {
                if (payload.has("error")) {
                    listener.onError(payload.optString("error"), payload.optString("code", null));
                } else {
                    listener.onMessage(payload);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse signaling message: " + text, e);
        }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        Log.i(TAG, "Signaling socket closed: " + reason + " (" + code + ")");
        connected = false;
        this.webSocket = null;
        if (listener != null) {
            listener.onClosed();
        }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
        Log.e(TAG, "Signaling socket failure", t);
        connected = false;
        this.webSocket = null;
        if (listener != null) {
            listener.onError(t.getMessage(), "SOCKET_FAILURE");
        }
    }

    private void flushPending() {
        JSONObject message;
        while ((message = pendingMessages.poll()) != null && webSocket != null) {
            webSocket.send(message.toString());
        }
    }

    private static String buildRequestUrl(String baseUrl, String role, String streamId) {
        Objects.requireNonNull(baseUrl, "Base URL is null");

        Uri baseUri = Uri.parse(baseUrl);
        if (baseUri.getScheme() == null) {
            baseUri = Uri.parse("ws://" + baseUrl);
        }

        Uri.Builder builder = baseUri.buildUpon();
        String scheme = baseUri.getScheme();
        if (scheme == null || scheme.isEmpty()) {
            builder.scheme("ws");
        } else if (scheme.equalsIgnoreCase("http")) {
            builder.scheme("ws");
        } else if (scheme.equalsIgnoreCase("https")) {
            builder.scheme("wss");
        } else if (!scheme.equalsIgnoreCase("ws") && !scheme.equalsIgnoreCase("wss")) {
            builder.scheme("ws");
        }

        builder.encodedFragment(null);
        builder.clearQuery();

        builder.path("");
        boolean appendedWs = false;
        for (String segment : baseUri.getPathSegments()) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            if ("socket.io".equals(segment)) {
                appendedWs = false;
                break;
            }
            builder.appendPath(segment);
            appendedWs = "ws".equals(segment);
        }
        if (!appendedWs) {
            builder.appendPath("ws");
        }

        builder.appendQueryParameter("role", role);
        builder.appendQueryParameter("streamId", streamId);
        return builder.build().toString();
    }
}
