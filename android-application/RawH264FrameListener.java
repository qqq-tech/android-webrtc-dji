package com.example;

/**
 * Listener that receives raw H.264 frames captured from the DJI drone.
 */
public interface RawH264FrameListener {
    void onRawFrame(RawH264Frame frame);
}
