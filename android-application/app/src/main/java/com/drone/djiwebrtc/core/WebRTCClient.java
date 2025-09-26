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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;


public class WebRTCClient {
    private static final String TAG = "WebRTCClient";
    private final Context context;

    // WebRTC related variables
    private PeerConnection peerConnection;
    private VideoTrack videoTrackFromCamera;
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
        // EglBase seems to be used for Hardware-acceleration for our video. Could help with smoothing things. (keep it)
        EglBase rootEglBase = EglBase.create();

        // Initialize the PeerConnectionFactory
        PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions();
        PeerConnectionFactory.initialize(options);

        // Now configure and build the factory
        factory = PeerConnectionFactory
                .builder()
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext()))
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, true))
                .setOptions(new PeerConnectionFactory.Options()).createPeerConnectionFactory();
    }

    public void handleWebRTCMessage(JSONObject message){
        try {
            Log.d(TAG, "connectToSignallingServer: got message " + message);
            String type = message.getString("type");
            if (type.equals("sdp")) {
                String remoteSdp = message.getString("sdp");
                String sdpType = message.optString("sdpType", "answer");
                SessionDescription.Type descriptionType = SessionDescription.Type.fromCanonicalForm(sdpType);
                Log.d(TAG, "Received remote SDP of type: " + descriptionType);
                peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(descriptionType, remoteSdp));
                if (descriptionType == SessionDescription.Type.OFFER) {
                    sendAnswer();
                }
            } else if (type.equals("ice")) {
                Log.d(TAG, "connectToSignallingServer: receiving ICE candidate");
                IceCandidate candidate = new IceCandidate(
                        message.optString("sdpMid", ""),
                        message.optInt("sdpMLineIndex", 0),
                        message.getString("candidate"));
                peerConnection.addIceCandidate(candidate);
            }
        }
        catch (JSONException e) {
            Log.d(TAG, "Exception with socket : " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initiateOffer() {
        MediaConstraints mediaConstraints = new MediaConstraints();
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                try {
                    sendMessage(SignalingMessageBuilder.buildSdpMessage(sessionDescription));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, mediaConstraints);
    }

    private void sendAnswer() {
        MediaConstraints mediaConstraints = new MediaConstraints();
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                try {
                    sendMessage(SignalingMessageBuilder.buildSdpMessage(sessionDescription));
                } catch (JSONException e) {
                    e.printStackTrace();
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
        if (!signalingTransport.isConnected()) {
            Log.d(TAG, "Queueing signaling message until transport connects: " + message);
        }
        signalingTransport.send((JSONObject) message);
    }

    private void createVideoTrackFromVideoCapturer() {
        videoSource = getFactory(context).createVideoSource(false);

        if (surfaceTextureHelper != null) {
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
        } else {
            videoCapturer.initialize(null, context, videoSource.getCapturerObserver());
        }
        try {
            videoCapturer.startCapture(options.getVideoResolutionWidth(), options.getVideoResolutionHeight(), options.getFps());
        } catch (Exception e) {
            Log.e(TAG, "Unable to start capture", e);
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Thread was interrupted during or after startCapture attempt.");
                // Decide if you need to re-propagate the interrupt status
                // Thread.currentThread().interrupt();
            }
        }

        videoTrackFromCamera = getFactory(context).createVideoTrack(options.getVideoSourceId(), videoSource);
        videoTrackFromCamera.setEnabled(true);

        for (VideoSink sink : localVideoSinks) {
            videoTrackFromCamera.addSink(sink);
        }
    }

    private void initializePeerConnection() {
        peerConnection = createPeerConnection();
    }

    private void startStreamingVideo() {
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
                Log.d(TAG, "onSignalingChange: ");
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                switch (iceConnectionState){
                    case DISCONNECTED:
                        Log.d(TAG, "PEER HAS DISCONNECTED");
                        dispose();
                        if (connectionChangedListener != null)
                            connectionChangedListener.onDisconnected();
                        break;
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.d(TAG, "onIceConnectionReceivingChange: ");
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: ");
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                // We are not interested in displaying whatever video feed we receive from the other end.
                // Not that we are getting any..
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: ");
                try {
                    JSONObject message = SignalingMessageBuilder.buildIceMessage(iceCandidate);
                    Log.d(TAG, "onIceCandidate: sending candidate " + message);
                    sendMessage(message);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) { Log.d(TAG, "onIceCandidatesRemoved: "); }

            @Override
            public void onAddStream(MediaStream mediaStream) { Log.d(TAG, "onAddStream: "); }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "onRemoveStream: ");
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel: ");
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
            return;
        }
        localVideoSinks.add(sink);
        if (videoTrackFromCamera != null) {
            videoTrackFromCamera.addSink(sink);
        }
    }

    public void removeVideoSink(VideoSink sink) {
        if (sink == null) {
            return;
        }
        localVideoSinks.remove(sink);
        if (videoTrackFromCamera != null) {
            videoTrackFromCamera.removeSink(sink);
        }
    }

    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;

        if (videoTrackFromCamera != null) {
            for (VideoSink sink : localVideoSinks) {
                videoTrackFromCamera.removeSink(sink);
            }
            localVideoSinks.clear();
            videoTrackFromCamera.dispose();
            videoTrackFromCamera = null;
        }

        try {
            videoCapturer.stopCapture();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "Error stopping capture", e);
        }
        videoCapturer.dispose();

        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
        }
    }

    public interface PeerConnectionChangedListener {
        void onDisconnected(); // Is called when our peer disconnects from the call
    }
}
