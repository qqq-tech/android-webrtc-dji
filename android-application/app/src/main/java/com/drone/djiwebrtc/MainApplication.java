package com.drone.djiwebrtc;

import android.app.Application;
import android.content.Context;
import android.util.Log; // Log import 추가

import androidx.multidex.MultiDex;

// DJI SDK 관련 클래스 import
import com.secneo.sdk.Helper;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;

import org.osmdroid.config.Configuration;

public class MainApplication extends Application {
    private static final String TAG = "MainApplication"; // 로깅을 위한 TAG

    @Override
    protected void attachBaseContext(Context paramContext) {
        super.attachBaseContext(paramContext);
        Helper.install(MainApplication.this);
    }

//    @Override
//    protected void attachBaseContext(Context base) {
//        try {
//            Class<?> helperClass = Class.forName("com.secneo.sdk.Helper");
//            helperClass.getMethod("install", Context.class).invoke(null, this);
//            Log.i(TAG, "SecNeo Helper installed successfully");
//        } catch (ClassNotFoundException ignored) {
//            Log.i(TAG, "SecNeo Helper not found; skipping optional install");
//        } catch (Exception e) {
//            Log.e(TAG, "Failed to invoke SecNeo Helper install", e);
//        }
//        super.attachBaseContext(base);
//        MultiDex.install(this);
//    }
}
