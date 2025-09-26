package com.drone.djiwebrtc.core;

import android.content.Context;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.drone.djiwebrtc.network.PionSignalingClient;
import com.drone.djiwebrtc.util.PionConfigStore;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;

import java.util.Locale;

import dji.sdk.sdkmanager.DJISDKManager;

/**
 * The DJIStreamer class manages the Android -> Pion WebRTC publication and bridges auxiliary
 * control channels (GCS commands, raw TCP streaming requests).
 */
public class DJIStreamer {
    private static final String TAG = "DJIStreamer";

    private final Context context;
    private final Handler mainHandler;
    private final GCSCommandHandler gcsCommandHandler;
    private final PionConfigStore pionConfigStore;
    private final String streamId;
    private final PionSignalingClient signalingClient; // Assuming PionSignalingClient implements SignalingTransport

    private RawH264TcpStreamer rawTcpStreamer;
    private WebRTCClient webRtcClient;
    private String droneDisplayName = "";
    private SurfaceTextureHelper surfaceTextureHelper;

    public DJIStreamer(Context context){
        this.context = context;
        this.mainHandler = new Handler(context.getMainLooper());
        this.pionConfigStore = new PionConfigStore(context);
        this.droneDisplayName = resolveDroneDisplayName();
        this.streamId = resolveStreamId(pionConfigStore.getStreamId(), droneDisplayName);

        String signalingUrl = pionConfigStore.getSignalingUrl();
        // Assuming PionSignalingClient implements a SignalingTransport interface needed by WebRTCClient
        this.signalingClient = new PionSignalingClient(signalingUrl, "publisher", streamId);
        this.gcsCommandHandler = new GCSCommandHandler(this.signalingClient);
        this.gcsCommandHandler.startTelemetry();

        this.signalingClient.setListener(new PionSignalingClient.Listener() {
            @Override
            public void onOpen() {
                mainHandler.post(() -> {
                    gcsCommandHandler.startTelemetry();
                    ensureWebRtcClient();
                });
            }

            @Override
            public void onMessage(JSONObject message) {
                mainHandler.post(() -> handleSignalMessage(message));
            }

            @Override
            public void onError(String description, @Nullable String code) {
                Log.e(TAG, "Signaling error: " + description + (code != null ? " (" + code + ")" : ""));
            }

            @Override
            public void onClosed() {
                mainHandler.post(() -> {
                    gcsCommandHandler.stopTelemetry();
                    if (webRtcClient != null) {
                        Log.d(TAG, "Signaling channel closed; resetting WebRTC client");
                        webRtcClient.dispose();
                        webRtcClient = null;
                    }
                    if (surfaceTextureHelper != null) {
                        Log.d(TAG, "Disposing SurfaceTextureHelper in onClosed");
                        surfaceTextureHelper.dispose();
                        surfaceTextureHelper = null;
                    }
                });
            }
        });
        this.signalingClient.connect();
    }

    private String resolveDroneDisplayName() {
        try {
            if (DJISDKManager.getInstance().getProduct() != null
                    && DJISDKManager.getInstance().getProduct().getModel() != null) {
                return DJISDKManager.getInstance().getProduct().getModel().getDisplayName();
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to determine drone model", e);
        }
        return "";
    }

    private String resolveStreamId(String configured, String fallbackName) {
        if (!TextUtils.isEmpty(configured)) {
            return configured;
        }
        if (TextUtils.isEmpty(fallbackName)) {
            return "android-stream";
        }
        String normalised = fallbackName.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9-_]", "-");
        return normalised.isEmpty() ? "android-stream" : normalised;
    }

    private void ensureWebRtcClient() {
        if (webRtcClient != null) {
            return;
        }
        VideoCapturer videoCapturer = new DJIVideoCapturer(droneDisplayName);
        
        if (this.surfaceTextureHelper == null) {
            // Create the SurfaceTextureHelper. Used by WebRTC for frame timestamping, etc.
            // For a non-preview capturer like DJIVideoCapturer, EGL context can be null.
            this.surfaceTextureHelper = SurfaceTextureHelper.create("DJICaptureThread", null);
        }

        // Assuming PionSignalingClient implements the SignalingTransport interface required by WebRTCClient
        webRtcClient = new WebRTCClient(context, videoCapturer, new WebRTCMediaOptions(), signalingClient, this.surfaceTextureHelper);
        
        webRtcClient.setConnectionChangedListener(() -> {
            mainHandler.post(() -> { // Ensure execution on the main handler's thread if UI or state changes need it
                Log.d(TAG, "Peer disconnected from stream " + streamId);
                if (webRtcClient != null) {
                    webRtcClient.dispose();
                    webRtcClient = null;
                }
                if (surfaceTextureHelper != null) {
                    Log.d(TAG, "Disposing SurfaceTextureHelper on peer disconnection");
                    surfaceTextureHelper.dispose();
                    surfaceTextureHelper = null;
                }
            });
        });
        Log.i(TAG, "Publishing stream '" + streamId + "' via Pion relay");
    }

    private void handleSignalMessage(JSONObject message) {
        if (message == null) {
            return;
        }
        String type = message.optString("type", "");
        switch (type) {
            case "gcs_command":
                JSONObject commandPayload = message.optJSONObject("payload");
                if (commandPayload == null) {
                    sendGcsCommandError(null, "Missing command payload", "INVALID_COMMAND");
                    return;
                }
                try {
                    gcsCommandHandler.handleCommand(commandPayload);
                } catch (JSONException e) {
                    Log.w(TAG, "Invalid GCS command payload", e);
                    String action = commandPayload.optString("action", null);
                    sendGcsCommandError(action, "Invalid command payload", "INVALID_COMMAND");
                }
                return;
            case "raw_stream":
                JSONObject rawPayload = message.optJSONObject("payload");
                if (rawPayload == null) {
                    sendControlError("raw_stream_ack", "Missing raw stream payload", "MISSING_PAYLOAD");
                    return;
                }
                try {
                    handleRawStreamRequest(rawPayload);
                } catch (JSONException e) {
                    Log.w(TAG, "Invalid raw stream payload", e);
                    sendControlError("raw_stream_ack", "Invalid raw stream payload", "INVALID_PAYLOAD");
                }
                return;
            default:
                break;
        }

        if (webRtcClient == null) {
            Log.w(TAG, "Dropping signaling message before WebRTC client ready: " + message);
            return;
        }
        webRtcClient.handleWebRTCMessage(message);
    }

    private synchronized void handleRawStreamRequest(JSONObject payload) throws JSONException {
        String action = payload.optString("action", "start");
        if (action.equals("stop")) {
            stopRawTcpStream();
            emitRawStreamAck("stopped", null, -1);
            return;
        }

        String host = payload.getString("host");
        int port = payload.getInt("port");

        try {
            startRawTcpStream(host, port);
            emitRawStreamAck("started", host, port);
        } catch (Exception e) {
            emitError("raw_stream_ack", e.getMessage(), "RAW_STREAM_ERROR");
        }
    }

    private void sendControlMessage(String type, @Nullable JSONObject payload) {
        if (signalingClient == null) {
            return;
        }
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("type", type);
            if (payload != null) {
                envelope.put("payload", payload);
            }
            signalingClient.send(envelope);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to send control message", e);
        }
    }

    private void sendControlError(String type, String description, String code) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("error", description);
            payload.put("code", code);
            sendControlMessage(type, payload);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to send control error", e);
        }
    }

    private void sendGcsCommandError(@Nullable String action, String description, String code) {
        try {
            JSONObject payload = new JSONObject();
            if (action != null) {
                payload.put("action", action);
            }
            payload.put("error", description);
            payload.put("code", code);
            sendControlMessage("gcs_command_ack", payload);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to send GCS command error", e);
        }
    }

    private synchronized void startRawTcpStream(String host, int port) throws Exception {
        if (rawTcpStreamer != null) {
            rawTcpStreamer.stop();
        }
        rawTcpStreamer = new RawH264TcpStreamer(host, port);
        rawTcpStreamer.start();
    }

    private synchronized void stopRawTcpStream() {
        if (rawTcpStreamer != null) {
            rawTcpStreamer.stop();
            rawTcpStreamer = null;
        }
    }

    private void emitRawStreamAck(String status, String host, int port) {
        try {
            JSONObject response = new JSONObject();
            response.put("status", status);
            if (host != null) {
                response.put("host", host);
                response.put("port", port);
            }
            sendControlMessage("raw_stream_ack", response);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to emit raw stream ack", e);
        }
    }

    private void emitError(String event, String description, String code) {
        sendControlError(event, description, code);
    }
}
