package com.drone.djiwebrtc.core;

import org.json.JSONException;
import org.json.JSONObject;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import androidx.annotation.Nullable;

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

        if (candidate.sdpMid != null && !candidate.sdpMid.trim().isEmpty()) {
            message.put("sdpMid", candidate.sdpMid);
        }

        if (candidate.sdpMLineIndex >= 0) {
            message.put("sdpMLineIndex", candidate.sdpMLineIndex);
        }
        return message;
    }

    public static JSONObject buildTelemetryMessage(
            double latitude,
            double longitude,
            @Nullable Double altitude,
            @Nullable Float accuracy,
            long timestampMillis,
            @Nullable String source
    ) throws JSONException {
        if (Double.isNaN(latitude) || Double.isInfinite(latitude)) {
            throw new IllegalArgumentException("Latitude must be finite");
        }
        if (Double.isNaN(longitude) || Double.isInfinite(longitude)) {
            throw new IllegalArgumentException("Longitude must be finite");
        }

        JSONObject message = new JSONObject();
        message.put("type", "telemetry");
        message.put("latitude", latitude);
        message.put("longitude", longitude);
        if (altitude != null && !Double.isNaN(altitude) && !Double.isInfinite(altitude)) {
            message.put("altitude", altitude);
        }
        if (accuracy != null && !Float.isNaN(accuracy) && !Float.isInfinite(accuracy) && accuracy >= 0f) {
            message.put("accuracy", accuracy);
        }
        if (timestampMillis > 0L) {
            message.put("timestamp", timestampMillis);
        }
        if (source != null && !source.trim().isEmpty()) {
            message.put("source", source.trim());
        }
        return message;
    }
}
