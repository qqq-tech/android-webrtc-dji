package com.drone.djiwebrtc.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.drone.djiwebrtc.R;

/**
 * Stores and retrieves the Pion relay configuration so it can be edited at runtime.
 */
public class PionConfigStore {
    private static final String PREFS_NAME = "pion_config";
    private static final String KEY_SIGNALING_URL = "signaling_url";
    private static final String KEY_STREAM_ID = "stream_id";

    private final SharedPreferences preferences;
    private final Context context;

    public PionConfigStore(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getSignalingUrl() {
        String fallback = context.getString(R.string.pion_signaling_url_default);
        String configured = preferences.getString(KEY_SIGNALING_URL, fallback);
        if (TextUtils.isEmpty(configured)) {
            return fallback;
        }
        return configured;
    }

    public void setSignalingUrl(@NonNull String signalingUrl) {
        preferences.edit().putString(KEY_SIGNALING_URL, signalingUrl).apply();
    }

    public String getStreamId() {
        String fallback = context.getString(R.string.pion_stream_id_default);
        String configured = preferences.getString(KEY_STREAM_ID, fallback);
        if (TextUtils.isEmpty(configured)) {
            return fallback;
        }
        return configured;
    }

    public void setStreamId(@NonNull String streamId) {
        preferences.edit().putString(KEY_STREAM_ID, streamId).apply();
    }
}
