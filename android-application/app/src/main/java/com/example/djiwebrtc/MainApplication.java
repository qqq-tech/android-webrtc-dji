package com.example.djiwebrtc;

import android.app.Application;
import android.content.Context;

import org.osmdroid.config.Configuration;

public class MainApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Context context = getApplicationContext();
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue("android-webrtc-dji");
    }
}
