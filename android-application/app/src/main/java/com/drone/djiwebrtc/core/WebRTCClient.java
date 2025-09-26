package com.drone.djiwebrtc.core;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
// import java.util.Arrays; // No longer needed for forceH264OnlyInSdp
// import java.util.List; // No longer needed for forceH264OnlyInSdp
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
// import java.util.regex.Matcher; // No longer needed for forceH264OnlyInSdp
// import java.util.regex.Pattern; // No longer needed for forceH264OnlyInSdp


public class WebRTCClient {
    private static final String TAG = "WebRTCClient";
    private final Context context;

    private PeerConnection peerConnection;private VideoTrack videoTrackFromCamera;
    private VideoSource videoSource;
    private final SurfaceTextureHelper surfaceTextureHelper;
    private final Set<VideoSink> localVideoSinks = new CopyOnWriteArraySet<>();
    private boolean disposed = false;
    private final WebRTCMediaOptions options;
    private final VideoCapturer videoCapturer;

    private PeerConnectionChangedListener connectionChangedListener;
    public void setConnectionChangedListener(PeerConnectionChangedListener connectionChangedListener) { this.connectionChangedListener = connectionChangedListener; }

    private final SignalingTransport signalingTransport;

    private static PeerConnectionFactory factory;
    private static PeerConnectionFactory getFactory(Context context){
        if (factory == null) {
            initializeFactory(context);
        }
        return factory;
    }

    public WebRTCClient(Context context, VideoCapturer videoCapturer, WebRTCMediaOptions options, SignalingTransport signalingTransport) {
        this(context, videoCapturer, options, signalingTransport, null);
    }

    public WebRTCClient(Context context, VideoCapturer videoCapturer, WebRTCMediaOptions options,
                        SignalingTransport signalingTransport, @Nullable SurfaceTextureHelper surfaceTextureHelper) {
        this.context = context;
        this.options = options;
        this.videoCapturer = videoCapturer;
        this.signalingTransport = signalingTransport;
        this.surfaceTextureHelper = surfaceTextureHelper;

        createVideoTrackFromVideoCapturer();
        initializePeerConnection();
        startStreamingVideo();
        initiateOffer();
    }

    private static void initializeFactory(Context context){
        EglBase rootEglBase = EglBase.create();
        PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions();
        PeerConnectionFactory.initialize(options);

        factory = PeerConnectionFactory
                .builder()
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext()))
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), false, true))
                .setOptions(new PeerConnectionFactory.Options()).createPeerConnectionFactory();
        Log.d(TAG, "PeerConnectionFactory initialized with H264 preferred (VP8 disabled in factory).");
    }

    // forceH264OnlyInSdp method removed

    public void handleWebRTCMessage(JSONObject message){
        try {
            Log.d(TAG, "handleWebRTCMessage: got message " + message.toString(2));
            String type = message.getString("type");
            if (type.equals("sdp")) {
                String remoteSdp = message.getString("sdp");
                String sdpType = message.optString("sdpType", "answer");
                Log.d(TAG, "Received remote SDP (" + sdpType + "):\n" + remoteSdp);
                SessionDescription.Type descriptionType = SessionDescription.Type.fromCanonicalForm(sdpType);
                peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(descriptionType, remoteSdp));
                if (descriptionType == SessionDescription.Type.OFFER) {
                    sendAnswer();
                }
            } else if (type.equals("ice")) {
                Log.d(TAG, "handleWebRTCMessage: receiving ICE candidate");
                IceCandidate candidate = new IceCandidate(
                        message.optString("sdpMid", ""),
                        message.optInt("sdpMLineIndex", 0),
                        message.getString("candidate"));
                peerConnection.addIceCandidate(candidate);
            }
        }
        catch (JSONException e) {
            Log.e(TAG, "Exception in handleWebRTCMessage: " + e.getMessage(), e);
        }
    }

    private void initiateOffer() {
        MediaConstraints mediaConstraints = new MediaConstraints();
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "Original SDP offer created by library:\n" + sessionDescription.description);
                // Use original SDP directly
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                try {
                    sendMessage(SignalingMessageBuilder.buildSdpMessage(sessionDescription));
                } catch (JSONException e) {
                    Log.e(TAG, "JSONException in initiateOffer: " + e.getMessage(), e);
                }
            }
        }, mediaConstraints);
    }

    private void sendAnswer() {
        MediaConstraints mediaConstraints = new MediaConstraints();
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "Original SDP answer created by library:\n" + sessionDescription.description);
                // Use original SDP directly
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                try {
                    sendMessage(SignalingMessageBuilder.buildSdpMessage(sessionDescription));
                } catch (JSONException e) {
                    Log.e(TAG, "JSONException in sendAnswer: " + e.getMessage(), e);
                }
            }
        }, mediaConstraints);
    }

    private void sendMessage(Object message) {
        if (!(message instanceof JSONObject)) {
            Log.w(TAG, "Ignoring non-JSON signaling payload: " + message);
            return;
        }
        if (signalingTransport == null) {
            Log.w(TAG, "No signaling transport available, dropping message: " + message);
            return;
        }
        signalingTransport.send((JSONObject) message);
    }

    private void createVideoTrackFromVideoCapturer() {
        videoSource = getFactory(context).createVideoSource(videoCapturer.isScreencast());

        if (surfaceTextureHelper != null) {
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
        } else {
            videoCapturer.initialize(null, context, videoSource.getCapturerObserver());
        }
        try {
            videoCapturer.startCapture(options.getVideoResolutionWidth(), options.getVideoResolutionHeight(), options.getFps());
        } catch (Exception e) {
            Log.e(TAG, "Unable to start video capture", e);
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Thread was interrupted during or after startCapture attempt.");
            }
            return;
        }

        videoTrackFromCamera = getFactory(context).createVideoTrack(options.getVideoSourceId(), videoSource);
        videoTrackFromCamera.setEnabled(true);
    }

    private void initializePeerConnection() {
        peerConnection = createPeerConnection();
    }

    private void startStreamingVideo() {
        if (videoTrackFromCamera == null) {
            Log.e(TAG, "Cannot start streaming, videoTrackFromCamera is null. Capturer might have failed to start.");
            return;
        }
        MediaStream mediaStream = getFactory(context).createLocalMediaStream(options.getMediaStreamId());
        mediaStream.addTrack(videoTrackFromCamera);
        peerConnection.addStream(mediaStream);
    }

    private PeerConnection createPeerConnection() {
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        PeerConnection.IceServer stun =  PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer();
        iceServers.add(stun);
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);

        PeerConnection.Observer pcObserver = new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "onSignalingChange: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);
                switch (iceConnectionState){
                    case DISCONNECTED:
                    case FAILED:
                    case CLOSED:
                        Log.w(TAG, "ICE Connection Disconnected/Failed/Closed: " + iceConnectionState);
                        if (connectionChangedListener != null)
                            connectionChangedListener.onDisconnected();
                        break;
                    case CONNECTED:
                        Log.i(TAG, "ICE Connection Connected");
                        break;
                    case COMPLETED:
                        Log.i(TAG, "ICE Connection Completed");
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.d(TAG, "onIceConnectionReceivingChange: " + b);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: " + iceGatheringState);
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "onAddTrack - remote track added");
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: " + iceCandidate);
                try {
                    JSONObject message = SignalingMessageBuilder.buildIceMessage(iceCandidate);
                    Log.d(TAG, "onIceCandidate: sending candidate " + message.toString(2));
                    sendMessage(message);
                } catch (JSONException e) {
                    Log.e(TAG, "JSONException in onIceCandidate: " + e.getMessage(), e);
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) { Log.d(TAG, "onIceCandidatesRemoved: "); }

            @Override
            public void onAddStream(MediaStream mediaStream) { Log.d(TAG, "onAddStream - remote stream added: " + mediaStream.getId()); }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "onRemoveStream: " + mediaStream.getId());
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel: " + dataChannel.label());
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded: ");
            }
        };

        return getFactory(context).createPeerConnection(rtcConfig, pcObserver);
    }

    public void addVideoSink(VideoSink sink) {
        if (sink == null) {
            Log.w(TAG, "addVideoSink: sink is null");
            return;
        }
        if (videoTrackFromCamera == null) {
            Log.w(TAG, "addVideoSink: videoTrackFromCamera is null");
            return;
        }
        videoTrackFromCamera.addSink(sink);
        localVideoSinks.add(sink);
    }

    public void removeVideoSink(VideoSink sink) {
        if (sink == null) {
            Log.w(TAG, "removeVideoSink: sink is null");
            return;
        }
        if (videoTrackFromCamera == null) {
            Log.w(TAG, "removeVideoSink: videoTrackFromCamera is null");
            return;
        }
        videoTrackFromCamera.removeSink(sink);
        localVideoSinks.remove(sink);
    }

    // Corrected dispose order with try-catch for track disposal
    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        Log.d(TAG, "Disposing WebRTCClient...");

        // 1. Stop capturer
        Log.d(TAG, "Stopping video capturer...");
        try {
            if (videoCapturer != null) {
                videoCapturer.stopCapture();
                Log.d(TAG, "Video capturer stopped.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interrupted during videoCapturer.stopCapture()", e);
        } catch (Exception e) {
            Log.w(TAG, "Error stopping video capturer", e);
        }

        // 2. Dispose local video track
        if (videoTrackFromCamera != null) {
            String trackIdInfo = "videoTrackFromCamera"; // Placeholder in case .id() fails early
            try {
                // Try to get the track ID for logging, but be aware it might fail if track is severely broken
                trackIdInfo = videoTrackFromCamera.id();
                Log.d(TAG, "Attempting to dispose video track: " + trackIdInfo);

                // Remove sinks before disposing the track. This might also throw if track is already disposed.
                for (VideoSink sink : localVideoSinks) {
                    videoTrackFromCamera.removeSink(sink);
                }
                localVideoSinks.clear();

                // Attempt to dispose the track itself
                videoTrackFromCamera.dispose();
                Log.d(TAG, "VideoTrack " + trackIdInfo + " disposed successfully.");

            } catch (IllegalStateException e) {
                // This is the specific exception we are trying to handle
                Log.w(TAG, "VideoTrack " + trackIdInfo + ": " + e.getMessage() + ". (Likely already disposed or native resource is gone).");
            } catch (Exception e) {
                // Catch any other unexpected errors during track cleanup
                Log.e(TAG, "Generic exception while cleaning up VideoTrack " + trackIdInfo + ".", e);
            } finally {
                // Crucial: always nullify the reference to prevent further attempts to use it
                videoTrackFromCamera = null;
                Log.d(TAG, "videoTrackFromCamera reference set to null for track: " + trackIdInfo);
            }
        } else {
            Log.d(TAG, "videoTrackFromCamera was already null before explicit disposal in dispose().");
        }

        // 3. Dispose video source
        if (videoSource != null) {
            Log.d(TAG, "Disposing video source...");
            try {
                videoSource.dispose();
                Log.d(TAG, "VideoSource disposed.");
            } catch (Exception e) {
                 Log.e(TAG, "Exception disposing videoSource", e);
            } finally {
                videoSource = null;
            }
        }

        // 4. Dispose video capturer object
        if (videoCapturer != null) {
            Log.d(TAG, "Disposing video capturer object...");
            try {
                videoCapturer.dispose();
                Log.d(TAG, "VideoCapturer object disposed.");
            } catch (Exception e) {
                Log.e(TAG, "Exception disposing videoCapturer object", e);
            }
        }

        // 5. Dispose PeerConnection
        if (peerConnection != null) {
            Log.d(TAG, "Closing and disposing PeerConnection...");
            try {
                peerConnection.close();
                peerConnection.dispose();
                Log.d(TAG, "PeerConnection disposed.");
            } catch (Exception e) {
                Log.e(TAG, "Exception disposing peerConnection", e);
            } finally {
                peerConnection = null;
            }
        }

        // 6. Dispose SurfaceTextureHelper
        if (surfaceTextureHelper != null) {
            Log.d(TAG, "Disposing SurfaceTextureHelper...");
            try {
                surfaceTextureHelper.dispose();
                Log.d(TAG, "SurfaceTextureHelper disposed.");
            } catch (Exception e) {
                Log.e(TAG, "Exception disposing surfaceTextureHelper", e);
            }
        }

        Log.d(TAG, "WebRTCClient disposed completely.");
    }

    public interface PeerConnectionChangedListener {
        void onDisconnected();
    }
}
