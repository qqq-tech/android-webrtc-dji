package com.example.djiwebrtc.core;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams raw H.264 frames using the custom TCP framing protocol (header + payload).
 * The framing format is:
 *
 * <pre>
 * +------------+------------+------------------+--------------+
 * | 0x44524E48 | Frame Type | Payload Length   | Frame Number |
 * +------------+------------+------------------+--------------+
 * | 4 bytes    | 1 byte     | 4 bytes (uint32) | 8 bytes      |
 * +------------+------------+------------------+--------------+
 * | Payload bytes (H264 NAL units)                           |
 * +---------------------------------------------------------+
 * </pre>
 *
 * The magic number ("DRNH") helps receivers validate framing integrity.
 */
public class RawH264TcpStreamer implements RawH264FrameListener {
    private static final String TAG = "RawH264TcpStreamer";
    private static final int HEADER_SIZE = 17; // 4 + 1 + 4 + 8

    private final String host;
    private final int port;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Socket socket;
    private OutputStream outputStream;

    public RawH264TcpStreamer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5_000);
        outputStream = new BufferedOutputStream(socket.getOutputStream());
        executor = Executors.newSingleThreadExecutor();
        running.set(true);
        DJIVideoCapturer.registerRawFrameListener(this);
        Log.i(TAG, "Started raw TCP streaming to " + host + ":" + port);
    }

    public synchronized void stop() {
        if (!running.get()) {
            return;
        }

        running.set(false);
        DJIVideoCapturer.unregisterRawFrameListener(this);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close output stream", e);
            }
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close socket", e);
            }
        }

        Log.i(TAG, "Stopped raw TCP streaming");
    }

    @Override
    public void onRawFrame(RawH264Frame frame) {
        if (!running.get()) {
            return;
        }

        executor.execute(() -> sendFrame(frame));
    }

    private void sendFrame(RawH264Frame frame) {
        if (frame == null || outputStream == null) {
            return;
        }

        try {
            byte[] header = buildHeader(frame);
            outputStream.write(header);
            outputStream.write(frame.getPayload(), 0, frame.getPayloadSize());
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to stream frame", e);
            stop();
        }
    }

    private byte[] buildHeader(RawH264Frame frame) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0x44524E48); // ASCII: DRNH (Drone H264)
        buffer.put(frame.getFrameType().getWireValue());
        buffer.putInt(frame.getPayloadSize());
        buffer.putLong(frame.getFrameId());
        return buffer.array();
    }
}
