package com.example;

import androidx.annotation.NonNull;

/**
 * Represents a single raw H.264 frame emitted by the DJI video pipeline. The frame contains the
 * encoded payload together with metadata needed by the custom TCP streaming format.
 */
public class RawH264Frame {
    public enum FrameType {
        IDR((byte) 0x01),
        P((byte) 0x02),
        SPS((byte) 0x03),
        PPS((byte) 0x04),
        UNKNOWN((byte) 0x00);

        private final byte wireValue;

        FrameType(byte wireValue) {
            this.wireValue = wireValue;
        }

        public byte getWireValue() {
            return wireValue;
        }
    }

    private final long frameId;
    private final long timestampMs;
    private final byte[] payload;
    private final int payloadSize;
    private final FrameType frameType;

    public RawH264Frame(long frameId, long timestampMs, byte[] payload, int payloadSize, FrameType frameType) {
        this.frameId = frameId;
        this.timestampMs = timestampMs;
        this.payload = payload;
        this.payloadSize = payloadSize;
        this.frameType = frameType == null ? FrameType.UNKNOWN : frameType;
    }

    public long getFrameId() {
        return frameId;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getPayloadSize() {
        return payloadSize;
    }

    public FrameType getFrameType() {
        return frameType;
    }

    @NonNull
    @Override
    public String toString() {
        return "RawH264Frame{" +
                "frameId=" + frameId +
                ", timestampMs=" + timestampMs +
                ", payloadSize=" + payloadSize +
                ", frameType=" + frameType +
                '}';
    }
}
