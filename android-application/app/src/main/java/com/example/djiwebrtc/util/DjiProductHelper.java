package com.example.djiwebrtc.util;

import android.util.Log;

import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

public final class DjiProductHelper {
    private static final String TAG = "DjiProductHelper";

    private DjiProductHelper() {}

    public static String resolveDisplayName() {
        try {
            BaseProduct product = DJISDKManager.getInstance().getProduct();
            if (product instanceof Aircraft) {
                Aircraft aircraft = (Aircraft) product;
                if (aircraft.getModel() != null) {
                    return aircraft.getModel().getDisplayName();
                }
            } else if (product != null && product.getModel() != null) {
                return product.getModel().getDisplayName();
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to resolve DJI product name", e);
        }
        return "DJI Drone";
    }
}
