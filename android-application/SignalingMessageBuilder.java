package com.example;

import org.json.JSONException;
import org.json.JSONObject;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

/**
 * Utility class to produce signaling messages that comply with the JSON format shared across the
 * Android client, Pion signaling server, Jetson processing node and the web dashboard.
 */
public final class SignalingMessageBuilder {
    private SignalingMessageBuilder() {}

    public static JSONObject buildSdpMessage(SessionDescription sessionDescription) throws JSONException {
        JSONObject message = new JSONObject();
        message.put("type", "sdp");
        message.put("sdp", sessionDescription.description);
        message.put("sdpType", sessionDescription.type.canonicalForm());
        return message;
    }

    public static JSONObject buildIceMessage(IceCandidate candidate) throws JSONException {
        JSONObject message = new JSONObject();
        message.put("type", "ice");
        message.put("candidate", candidate.sdp);
        message.put("sdpMid", candidate.sdpMid);
        message.put("sdpMLineIndex", candidate.sdpMLineIndex);
        return message;
    }
}
