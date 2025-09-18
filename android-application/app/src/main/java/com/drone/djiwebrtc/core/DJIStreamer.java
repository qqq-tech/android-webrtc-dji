package com.drone.djiwebrtc.core;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.VideoCapturer;

import java.util.Hashtable;

import dji.sdk.sdkmanager.DJISDKManager;
import static io.socket.client.Socket.EVENT_DISCONNECT;

import com.drone.djiwebrtc.network.SocketConnection;
import com.drone.djiwebrtc.webrtc.WebRTCClient;
import com.drone.djiwebrtc.webrtc.WebRTCMediaOptions;

/**
 * The DJIStreamer class will manage all ongoing P2P connections
 * with clients, who desire videofeed.
 */
public class DJIStreamer {
    private static final String TAG = "DJIStreamer";

    private String droneDisplayName = "";
    private final Context context;
    private final Hashtable<String, WebRTCClient> ongoingConnections = new Hashtable<>();
    private RawH264TcpStreamer rawTcpStreamer;
    private final GCSCommandHandler gcsCommandHandler;

    public DJIStreamer(Context context){
        this.droneDisplayName = DJISDKManager.getInstance().getProduct().getModel().getDisplayName();
        this.context = context;

        this.gcsCommandHandler = new GCSCommandHandler();
        this.gcsCommandHandler.startTelemetry();

        setupSocketEvent();
    }

    private WebRTCClient getClient(String socketID){
        return ongoingConnections.getOrDefault(socketID, null);
    }

    private void removeClient(String socketID){
        // TODO: Any other cleanup necessary?.. Let the client stop the VideoCapturer though.
        ongoingConnections.remove(socketID);
    }

    private WebRTCClient addNewClient(String socketID){
        VideoCapturer videoCapturer = new DJIVideoCapturer(droneDisplayName);
        WebRTCClient client = new WebRTCClient(socketID, context, videoCapturer, new WebRTCMediaOptions());
        client.setConnectionChangedListener(new WebRTCClient.PeerConnectionChangedListener() {
            @Override
            public void onDisconnected() {
                removeClient(client.peerSocketID);
                Log.d(TAG, "DJIStreamer has removed connection from table. Remaining active sessions: " + ongoingConnections.size());
            }
        });
        ongoingConnections.put(socketID, client);
        return client;
    }

    private void setupSocketEvent(){
        SocketConnection.getInstance().on("webrtc_msg", args -> {

            Handler mainHandler = new Handler(context.getMainLooper());
            Runnable myRunnable = new Runnable() {
                @Override
                public void run() {
                    String peerSocketID = (String)args[0]; // The web-client sending a message
                    Log.d(TAG, "Received WebRTCMessage: " + peerSocketID);

                    WebRTCClient client = getClient(peerSocketID);

                    if (client == null){
                        // A new client wants to establish a P2P
                        client = addNewClient(peerSocketID);
                    }

                    // Then just pass the message to the client
                    JSONObject message = (JSONObject) args[1];
                    client.handleWebRTCMessage(message);
                }
            };
            mainHandler.post(myRunnable);
        }).on("gcs_command", args -> {
            Handler mainHandler = new Handler(context.getMainLooper());
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
        }).on("raw_stream", args -> {
            Handler mainHandler = new Handler(context.getMainLooper());
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
        }).on(EVENT_DISCONNECT, args -> {
            Log.d(TAG, "connectToSignallingServer: disconnect");
        });
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
