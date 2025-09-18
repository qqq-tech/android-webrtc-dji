package com.drone.djiwebrtc.network;

import android.util.Log;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class SocketConnection {
    private static final String TAG = "SocketConnection";
    private static final String DEFAULT_URL = "http://10.0.2.2:3000";

    private static SocketConnection instance;

    private Socket socket;
    private String baseUrl = DEFAULT_URL;

    private SocketConnection() {
        connect();
    }

    public static synchronized SocketConnection getInstance() {
        if (instance == null) {
            instance = new SocketConnection();
        }
        return instance;
    }

    public synchronized void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        reconnect();
    }

    public synchronized void reconnect() {
        disconnect();
        connect();
    }

    public synchronized void disconnect() {
        if (socket != null) {
            socket.disconnect();
            socket.close();
            socket = null;
        }
    }

    public synchronized Emitter on(String event, Emitter.Listener listener) {
        ensureConnected();
        return socket.on(event, listener);
    }

    public synchronized void emit(String event, Object... args) {
        ensureConnected();
        socket.emit(event, args);
    }

    private void connect() {
        try {
            IO.Options options = IO.Options.builder()
                    .setForceNew(true)
                    .build();
            socket = IO.socket(baseUrl, options);
            socket.connect();
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid signaling server URL", e);
        }
    }

    private void ensureConnected() {
        if (socket == null) {
            connect();
        } else if (!socket.connected()) {
            socket.connect();
        }
    }
}
