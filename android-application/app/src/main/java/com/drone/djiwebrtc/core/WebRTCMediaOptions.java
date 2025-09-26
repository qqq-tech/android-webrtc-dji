package com.drone.djiwebrtc.core;

public class WebRTCMediaOptions {
    private String mediaStreamId = "ARDAMS";
    private String videoSourceId = "ARDAMSv0";
    private int videoResolutionWidth = 320;
    private int videoResolutionHeight = 240;
    private int fps = 30;

    public String getMediaStreamId() {
        return mediaStreamId;
    }

    public WebRTCMediaOptions setMediaStreamId(String mediaStreamId) {
        if (mediaStreamId != null) {
            this.mediaStreamId = mediaStreamId;
        }
        return this;
    }

    public String getVideoSourceId() {
        return videoSourceId;
    }

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
}
