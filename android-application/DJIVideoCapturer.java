/* Originally sourced from
* https://chromium.googlesource.com/external/webrtc/+/b6760f9e4442410f2bcb6090b3b89bf709e2fce2/webrtc/api/android/java/src/org/webrtc/CameraVideoCapturer.java
* and rewritten to work for DJI drones.
*  */
package com.example;

import org.webrtc.CapturerObserver;
import org.webrtc.NV12Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;

public class DJIVideoCapturer implements VideoCapturer {
    private final static String TAG = "DJIStreamer";

    private static DJICodecManager codecManager;
    private static final ArrayList<CapturerObserver> observers = new ArrayList<CapturerObserver>();
    private static final List<RawH264FrameListener> rawFrameListeners = new CopyOnWriteArrayList<>();
    private static final AtomicLong frameCounter = new AtomicLong();

    private final String droneDisplayName;
    private Context context;
    private CapturerObserver capturerObserver;

    public DJIVideoCapturer(String droneDisplayName){
        this.droneDisplayName = droneDisplayName;
    }

    private void setupVideoListener(){
        if(codecManager != null)
            return;

        // Pass SurfaceTexture as null to force the Yuv callback - width and height for the surface texture does not matter
        codecManager = new DJICodecManager(context, (SurfaceTexture)null, 0, 0);
        codecManager.enabledYuvData(true);
        codecManager.setYuvDataCallback(new DJICodecManager.YuvDataCallback() {
            @Override
            public void onYuvDataReceived(MediaFormat mediaFormat, ByteBuffer videoBuffer, int dataSize, int width, int height) {
                if (videoBuffer != null){
                    try{
                        // TODO: We need to check which color format they are using by doing a lookup in our MediaFormat, otherwise we get green artifacts
                        // This can change with Android/device versions. The format might actually change, seemingly at random, according to community reports...
                        // Other possible buffers we might have to use: I420Buffer
                        long timestampNS = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());
                        NV12Buffer buffer = new NV12Buffer(width,
                                                            height,
                                                            mediaFormat.getInteger(MediaFormat.KEY_STRIDE),
                                                            mediaFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT),
                                                            videoBuffer,
                                                            null);
                        VideoFrame videoFrame = new VideoFrame(buffer, 0, timestampNS);
                        // Feed the video frame to everyone
                        for (CapturerObserver obs : observers) {
                            obs.onFrameCaptured(videoFrame);
                        }
                        videoFrame.release();
                    } catch (Exception e){
                        e.printStackTrace();
                    }
                }
            }
        });

        // Could create more cases if other drones from DJI require a different approach
        switch (this.droneDisplayName){
            // The Air 2S relies on the VideoDataListener to obtain the video feed
            case "DJI Air 2S":
                // The onReceive callback provides us the raw H264 (at least according to official documentation). To decode it we send it to our DJICodecManager
                // H264 or H265 encoding is done to compress and save bandwidth. (4K video might force a switch to H265 on DJI drones)
                VideoFeeder.VideoDataListener videoDataListener = new VideoFeeder.VideoDataListener() {
                    @Override
                    public void onReceive(byte[] bytes, int dataSize) {
                        // Broadcast the raw H264 frame before decoding so it can be forwarded using the
                        // custom TCP streaming path.
                        notifyRawFrameListeners(bytes, dataSize);

                        // Pass the encoded data along to obtain the YUV-color data
                        codecManager.sendDataToDecoder(bytes, dataSize);
                    }
                };
                VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(videoDataListener);
                break;
        }
    }

    public static void registerRawFrameListener(RawH264FrameListener listener) {
        if (listener != null && !rawFrameListeners.contains(listener)) {
            rawFrameListeners.add(listener);
        }
    }

    public static void unregisterRawFrameListener(RawH264FrameListener listener) {
        rawFrameListeners.remove(listener);
    }

    private void notifyRawFrameListeners(byte[] bytes, int dataSize) {
        if (rawFrameListeners.isEmpty() || bytes == null || dataSize <= 0) {
            return;
        }

        byte[] payloadCopy = new byte[dataSize];
        System.arraycopy(bytes, 0, payloadCopy, 0, dataSize);

        RawH264Frame.FrameType frameType = detectFrameType(payloadCopy, dataSize);
        RawH264Frame frame = new RawH264Frame(
                frameCounter.incrementAndGet(),
                SystemClock.elapsedRealtime(),
                payloadCopy,
                dataSize,
                frameType
        );

        for (RawH264FrameListener listener : rawFrameListeners) {
            try {
                listener.onRawFrame(frame);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private RawH264Frame.FrameType detectFrameType(byte[] buffer, int length) {
        if (buffer == null || length < 1) {
            return RawH264Frame.FrameType.UNKNOWN;
        }

        int offset = findStartCodeOffset(buffer, length);
        if (offset < 0 || offset >= length) {
            return RawH264Frame.FrameType.UNKNOWN;
        }

        int nalUnitType = buffer[offset] & 0x1F;
        switch (nalUnitType) {
            case 5:
                return RawH264Frame.FrameType.IDR;
            case 1:
                return RawH264Frame.FrameType.P;
            case 7:
                return RawH264Frame.FrameType.SPS;
            case 8:
                return RawH264Frame.FrameType.PPS;
            default:
                return RawH264Frame.FrameType.UNKNOWN;
        }
    }

    private int findStartCodeOffset(byte[] buffer, int length) {
        for (int i = 0; i < length - 4; i++) {
            if (buffer[i] == 0x00 && buffer[i + 1] == 0x00 && buffer[i + 2] == 0x00 && buffer[i + 3] == 0x01) {
                return i + 4;
            }
        }
        return -1;
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext,
                           CapturerObserver capturerObserver) {
        this.context = applicationContext;
        this.capturerObserver = capturerObserver;

        observers.add(capturerObserver);
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        // Hook onto the DJI onYuvDataReceived event
        setupVideoListener();
    }

    @Override
    public void stopCapture() throws InterruptedException {
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        // Empty on purpose
    }

    @Override
    public void dispose() {
        // Stop receiving frames on the callback from the decoder
        if (observers.contains(capturerObserver))
            observers.remove(capturerObserver);
    }

    @Override
    public boolean isScreencast() {
        return false;
    }
}