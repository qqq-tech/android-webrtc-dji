package com.drone.djiwebrtc.core;

public class WebRTCMediaOptions {
    private String mediaStreamId = "ARDAMS";
    private String videoSourceId = "ARDAMSv0";
    private int videoResolutionWidth = 320;
    private int videoResolutionHeight = 240;
    private int fps = 30;
    private String preferredVideoCodec = "H264";

    public String getMediaStreamId() {
        return mediaStreamId;
    }

    /**
     * Sets the identifier for the {@link org.webrtc.MediaStream} that will contain all local tracks.
     * <p>
     * The stream ID is surfaced to the remote peer so that it can group the incoming tracks the same
     * way we expose them locally.  It must therefore stay stable and unique within a given peer
     * connection session.
     */
    public WebRTCMediaOptions setMediaStreamId(String mediaStreamId) {
        if (mediaStreamId != null) {
            this.mediaStreamId = mediaStreamId;
        }
        return this;
    }

    public String getVideoSourceId() {
        return videoSourceId;
    }

    /**
     * Sets the identifier used when creating the {@link org.webrtc.VideoTrack} inside the stream.
     * <p>
     * While the stream ID groups multiple tracks, the source ID maps to the specific track so that
     * the remote peer can subscribe, mute or otherwise control it independently from other tracks
     * that might share the same stream ID.
     */
    public WebRTCMediaOptions setVideoSourceId(String videoSourceId) {
        if (videoSourceId != null) {
            this.videoSourceId = videoSourceId;
        }
        return this;
    }

    public int getVideoResolutionWidth() {
        return videoResolutionWidth;
    }

    public int getVideoResolutionHeight() {
        return videoResolutionHeight;
    }

    public WebRTCMediaOptions setVideoResolution(int width, int height) {
        if (width > 0) {
            this.videoResolutionWidth = width;
        }
        if (height > 0) {
            this.videoResolutionHeight = height;
        }
        return this;
    }

    public int getFps() {
        return fps;
    }

    public WebRTCMediaOptions setFps(int fps) {
        if (fps > 0) {
            this.fps = fps;
        }
        return this;
    }

    public String getPreferredVideoCodec() {
        return preferredVideoCodec;
    }

    /**
     * Instructs the SDP negotiation helper to prioritise the provided codec (e.g. {@code H264}).
     */
    public WebRTCMediaOptions setPreferredVideoCodec(String preferredVideoCodec) {
        if (preferredVideoCodec != null && !preferredVideoCodec.trim().isEmpty()) {
            this.preferredVideoCodec = preferredVideoCodec.trim();
        }
        return this;
    }
}
