package com.example.djiwebrtc.webrtc;

import android.content.Context;
import android.util.Log;

import com.example.djiwebrtc.core.DJIVideoCapturer;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

public class DroneVideoPreview {
    private static final String TAG = "DroneVideoPreview";
    private static final String TRACK_ID = "DJI_PREVIEW";

    private final Context context;
    private final SurfaceViewRenderer surfaceViewRenderer;
    private final DJIVideoCapturer videoCapturer;
    private final EglBase eglBase;

    private PeerConnectionFactory peerConnectionFactory;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private boolean started;

    public DroneVideoPreview(Context context, SurfaceViewRenderer surfaceViewRenderer, DJIVideoCapturer videoCapturer) {
        this.context = context.getApplicationContext();
        this.surfaceViewRenderer = surfaceViewRenderer;
        this.videoCapturer = videoCapturer;
        this.eglBase = EglBase.create();
    }

    public synchronized void start() {
        if (started) {
            return;
        }

        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(true)
                        .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .createPeerConnectionFactory();

        surfaceViewRenderer.init(eglBase.getEglBaseContext(), null);
        surfaceViewRenderer.setEnableHardwareScaler(true);
        surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        surfaceViewRenderer.setMirror(false);

        surfaceTextureHelper = SurfaceTextureHelper.create("DJI_CAPTURE", eglBase.getEglBaseContext());
        videoSource = peerConnectionFactory.createVideoSource(false);
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());

        try {
            videoCapturer.startCapture(1280, 720, 30);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start DJI video capture", e);
        }

        videoTrack = peerConnectionFactory.createVideoTrack(TRACK_ID, videoSource);
        videoTrack.setEnabled(true);
        videoTrack.addSink(surfaceViewRenderer);
        started = true;
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }

        started = false;

        if (videoTrack != null) {
            videoTrack.removeSink(surfaceViewRenderer);
            videoTrack.dispose();
            videoTrack = null;
        }

        try {
            videoCapturer.stopCapture();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "Error stopping DJI capture", e);
        }

        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.release();
        }

        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
    }

    public synchronized void destroy() {
        stop();
        eglBase.release();
    }
}
