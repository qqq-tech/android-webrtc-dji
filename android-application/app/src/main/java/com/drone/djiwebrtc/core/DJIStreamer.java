package com.drone.djiwebrtc.core;

import android.content.Context;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.drone.djiwebrtc.network.PionSignalingClient;
import com.drone.djiwebrtc.network.SocketConnection;
import com.drone.djiwebrtc.util.PionConfigStore;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.VideoCapturer;

import java.util.Locale;

import dji.sdk.sdkmanager.DJISDKManager;

import static io.socket.client.Socket.EVENT_DISCONNECT;

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
    private final PionSignalingClient signalingClient;

    private RawH264TcpStreamer rawTcpStreamer;
    private WebRTCClient webRtcClient;
    private String droneDisplayName = "";

    public DJIStreamer(Context context){
        this.context = context;
        this.mainHandler = new Handler(context.getMainLooper());
        this.pionConfigStore = new PionConfigStore(context);
        this.droneDisplayName = resolveDroneDisplayName();
        this.streamId = resolveStreamId(pionConfigStore.getStreamId(), droneDisplayName);

        this.gcsCommandHandler = new GCSCommandHandler();
        this.gcsCommandHandler.startTelemetry();

        String signalingUrl = pionConfigStore.getSignalingUrl();
        this.signalingClient = new PionSignalingClient(signalingUrl, "publisher", streamId);
        this.signalingClient.setListener(new PionSignalingClient.Listener() {
            @Override
            public void onOpen() {
                mainHandler.post(DJIStreamer.this::ensureWebRtcClient);
            }

            @Override
            public void onMessage(JSONObject message) {
                mainHandler.post(() -> {
                    if (webRtcClient == null) {
                        Log.w(TAG, "Dropping signaling message before WebRTC client ready: " + message);
                        return;
                    }
                    webRtcClient.handleWebRTCMessage(message);
                });
            }

            @Override
            public void onError(String description, @Nullable String code) {
                Log.e(TAG, "Signaling error: " + description + (code != null ? " (" + code + ")" : ""));
            }

            @Override
            public void onClosed() {
                mainHandler.post(() -> {
                    if (webRtcClient != null) {
                        Log.d(TAG, "Signaling channel closed; resetting WebRTC client");
                        webRtcClient.dispose();
                        webRtcClient = null;
                    }
                });
            }
        });
        this.signalingClient.connect();

        setupSocketEvent();
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
        webRtcClient = new WebRTCClient(context, videoCapturer, new WebRTCMediaOptions(), signalingClient);
        webRtcClient.setConnectionChangedListener(() -> {
            Log.d(TAG, "Peer disconnected from stream " + streamId);
            if (webRtcClient != null) {
                webRtcClient.dispose();
            }
            webRtcClient = null;
        });
        Log.i(TAG, "Publishing stream '" + streamId + "' via Pion relay");
    }

    private void setupSocketEvent(){
        SocketConnection connection = SocketConnection.getInstance();
        connection.on("gcs_command", args -> {
            mainHandler.post(() -> {
                if (args.length == 0) {
                    Log.w(TAG, "Received empty GCS command payload");
                    return;
                }
                try {
                    JSONObject command = (JSONObject) args[0];
                    gcsCommandHandler.handleCommand(command);
                } catch (JSONException e) {
                    emitError("gcs_command_ack", "Invalid command payload", "INVALID_COMMAND");
                }
            });
        });
        connection.on("raw_stream", args -> {
            mainHandler.post(() -> {
                if (args.length == 0) {
                    emitError("raw_stream_ack", "Missing raw stream payload", "MISSING_PAYLOAD");
                    return;
                }
                try {
                    JSONObject payload = (JSONObject) args[0];
                    handleRawStreamRequest(payload);
                } catch (JSONException e) {
                    emitError("raw_stream_ack", "Invalid raw stream payload", "INVALID_PAYLOAD");
                }
            });
        });
        connection.on(EVENT_DISCONNECT, args -> Log.d(TAG, "connectToSignallingServer: disconnect"));
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
            SocketConnection.getInstance().emit("raw_stream_ack", response);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to emit raw stream ack", e);
        }
    }

    private void emitError(String event, String description, String code) {
        try {
            JSONObject error = new JSONObject();
            error.put("error", description);
            error.put("code", code);
            SocketConnection.getInstance().emit(event, error);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to emit error", e);
        }
    }
}
