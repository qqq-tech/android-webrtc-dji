package com.drone.djiwebrtc.core;

import org.json.JSONObject;

/**
 * Lightweight abstraction for sending signaling messages to the relay.
 */
public interface SignalingTransport {
    void send(JSONObject message);
    boolean isConnected();
}
