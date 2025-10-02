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

    private PeerConnection peerConnection;
    private MediaStream localMediaStream; 
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
        Log.d(TAG, "createVideoTrackFromVideoCapturer called.");
        videoSource = getFactory(context).createVideoSource(videoCapturer.isScreencast());
        Log.d(TAG, "VideoSource created: " + (videoSource != null) + ", Capturer isScreencast: " + videoCapturer.isScreencast());

        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
        Log.d(TAG, "VideoCapturer initialized.");

        try {
            int requestedWidth = options.getVideoResolutionWidth();
            int requestedHeight = options.getVideoResolutionHeight();
            int requestedFps = options.getFps();
            Log.d(TAG, "Attempting to start video capture with requested options: " + requestedWidth + "x" + requestedHeight + "@" + requestedFps + "fps");

            videoCapturer.startCapture(requestedWidth, requestedHeight, requestedFps);
            Log.i(TAG, "Video capture started successfully (requested " + requestedWidth + "x" + requestedHeight + "@" + requestedFps + "fps).");
        } catch (Exception e) {
            Log.e(TAG, "Unable to start video capture using specified options", e);
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Thread was interrupted during or after startCapture attempt.");
            }
        }

        videoTrackFromCamera = getFactory(context).createVideoTrack(options.getVideoSourceId(), videoSource);
        Log.d(TAG, "VideoTrackFromCamera created: " + (videoTrackFromCamera != null) + ", ID: " + options.getVideoSourceId());
        if (videoTrackFromCamera != null) {
            videoTrackFromCamera.setEnabled(true);
            Log.d(TAG, "videoTrackFromCamera enabled.");
        } else {
            Log.e(TAG, "Failed to create videoTrackFromCamera!");
        }
    }

    private void initializePeerConnection() {
        peerConnection = createPeerConnection();
    }

    private void startStreamingVideo() {
        if (videoTrackFromCamera == null) {
            Log.e(TAG, "Cannot start streaming, videoTrackFromCamera is null. Capturer might have failed to start.");
            return;
        }
        this.localMediaStream = getFactory(context).createLocalMediaStream(options.getMediaStreamId());
        if (this.localMediaStream == null) {
            Log.e(TAG, "Failed to create local media stream");
            return;
        }
        this.localMediaStream.addTrack(videoTrackFromCamera);
        peerConnection.addStream(this.localMediaStream);
        Log.d(TAG, "Local media stream " + this.localMediaStream.getId() + " added to PeerConnection.");
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
        Log.d(TAG, "addVideoSink called. Sink: " + (sink != null) + ", videoTrackFromCamera: " + (videoTrackFromCamera != null));
        if (sink == null) {
            Log.w(TAG, "addVideoSink: sink is null");
            return;
        }
        if (videoTrackFromCamera == null) {
            Log.w(TAG, "addVideoSink: videoTrackFromCamera is null, cannot add sink.");
            return;
        }
        videoTrackFromCamera.addSink(sink);
        localVideoSinks.add(sink);
        Log.i(TAG, "VideoSink added to videoTrackFromCamera. Current localVideoSinks count: " + localVideoSinks.size());
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

    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        Log.i(TAG, "Disposing WebRTCClient...");

        // 1. 비디오 캡처러의 프레임 생성 중지 (videoCapturer 객체 자체의 dispose는 외부에서 관리)
        if (videoCapturer != null) {
            try {
                Log.d(TAG, "Stopping video capturer capture (from WebRTCClient.dispose)...");
                videoCapturer.stopCapture();
                Log.d(TAG, "Video capturer stopped capturing (from WebRTCClient.dispose).");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Interrupted during videoCapturer.stopCapture() in WebRTCClient", e);
            } catch (Exception e) {
                Log.w(TAG, "Error stopping video capturer capture in WebRTCClient", e);
            }
        }

        // 2. 로컬 비디오 트랙에서 모든 싱크 제거
        if (videoTrackFromCamera != null && !localVideoSinks.isEmpty()) {
            Log.d(TAG, "Removing all sinks from local video track.");
            for (VideoSink sink : localVideoSinks) {
                videoTrackFromCamera.removeSink(sink);
            }
            localVideoSinks.clear();
            Log.d(TAG, "All sinks removed from local video track.");
        }

        // 3. PeerConnection 관련 리소스 정리 (순서 중요)
        if (peerConnection != null) {
            // 3a. localMediaStream에서 videoTrackFromCamera 제거
            if (localMediaStream != null && videoTrackFromCamera != null) {
                Log.d(TAG, "Removing track from localMediaStream: " + videoTrackFromCamera.id());
                try {
                    localMediaStream.removeTrack(videoTrackFromCamera);
                    Log.d(TAG, "Track removed from localMediaStream.");
                } catch (Exception e) {
                    Log.w(TAG, "Exception removing track from localMediaStream: " + e.getMessage());
                }
            }
            
            // 3b. peerConnection에서 localMediaStream 제거
            if (localMediaStream != null) {
                Log.d(TAG, "Removing stream from peerConnection: " + localMediaStream.getId()); // getId() 호출 시점 변경
                 try {
                    peerConnection.removeStream(localMediaStream);
                    Log.d(TAG, "Stream removed from peerConnection.");
                } catch (Exception e) {
                    Log.w(TAG, "Exception removing stream from peerConnection: " + e.getMessage());
                }
            }

            // 3c. PeerConnection close
            Log.d(TAG, "Closing PeerConnection (WebRTCClient)...");
            try {
                peerConnection.close();
                Log.d(TAG, "PeerConnection closed (WebRTCClient).");
            } catch (Exception e) {
                Log.e(TAG, "Exception closing PeerConnection in WebRTCClient", e);
            }

            // 3d. PeerConnection dispose
            Log.d(TAG, "Disposing PeerConnection object (WebRTCClient)...");
            try {
                peerConnection.dispose();
                Log.d(TAG, "PeerConnection disposed (WebRTCClient).");
            } catch (Exception e) {
                Log.e(TAG, "Exception disposing PeerConnection object in WebRTCClient: " + e.getMessage(), e);
            }
            peerConnection = null;
        }

        // 4. 로컬 MediaStream 해제 (PeerConnection에서 제거 후 안전하게 해제)
        if (localMediaStream != null) {
            Log.d(TAG, "Disposing local media stream (WebRTCClient)... Logged before getId");
            try {
                // String streamIdForLog = localMediaStream.getId(); // getId() 호출은 여전히 위험할 수 있으므로 주석 처리
                localMediaStream.dispose();
                Log.d(TAG, "Local media stream disposed by WebRTCClient.");
            } catch (Exception e) {
                Log.w(TAG, "Exception disposing localMediaStream in WebRTCClient (possibly already disposed): " + e.getMessage());
            } finally {
                localMediaStream = null;
            }
        }

        // 5. VideoTrack 해제
        if (videoTrackFromCamera != null) {
            Log.d(TAG, "Disposing video track (WebRTCClient)..." );
            try {
                // String trackIdForLog = videoTrackFromCamera.id(); // getId() 호출은 여전히 위험할 수 있으므로 주석 처리
                videoTrackFromCamera.dispose();
                Log.d(TAG, "VideoTrack disposed by WebRTCClient.");
            } catch (Exception e) {
                 Log.w(TAG, "Exception disposing videoTrackFromCamera in WebRTCClient (possibly already disposed): " + e.getMessage());
            } finally {
                videoTrackFromCamera = null;
            }
        }

        // 6. VideoSource 해제
        if (videoSource != null) {
            Log.d(TAG, "Disposing video source (WebRTCClient)...");
            try {
                videoSource.dispose();
                Log.d(TAG, "VideoSource disposed by WebRTCClient.");
            } catch (Exception e) {
                 Log.w(TAG, "Exception disposing videoSource in WebRTCClient (possibly already disposed): " + e.getMessage());
            } finally {
                videoSource = null;
            }
        }

        // 7. surfaceTextureHelper는 WebRTCClient 외부에서 관리되므로 여기서 dispose 하지 않음

        Log.i(TAG, "WebRTCClient dispose sequence finished.");
    }

    public interface PeerConnectionChangedListener {
        void onDisconnected();
    }
}
