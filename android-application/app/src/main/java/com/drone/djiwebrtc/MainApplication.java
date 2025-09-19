package com.drone.djiwebrtc;

import android.app.Application;
import android.content.Context;
import android.util.Log; // Log import 추가

// DJI SDK 관련 클래스 import
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
    public void onCreate() {
        super.onCreate();
        Context appContext = getApplicationContext(); // appContext 변수명 사용

        // osmdroid 초기화 (기존 코드)
        Configuration.getInstance().load(appContext, appContext.getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue("android-webrtc-dji");

        // DJI SDK 초기화
        DJISDKManager.SDKManagerCallback sdkManagerCallback = new DJISDKManager.SDKManagerCallback() {
            @Override
            public void onRegister(DJIError error) {
                if (error == DJISDKError.REGISTRATION_SUCCESS) {
                    Log.i(TAG, "DJI SDK가 성공적으로 등록되었습니다!");
                    DJISDKManager.getInstance().startConnectionToProduct(); // 등록 성공 후 제품 연결 시작
                } else {
                    Log.e(TAG, "DJI SDK 등록 실패: " + error.getDescription());
                    // 등록 실패 처리 (예: 사용자에게 Toast 메시지 표시)
                    // TODO: 사용자에게 앱 키가 정확한지 확인하라는 메시지를 표시할 수 있습니다.
                }
            }

            @Override
            public void onProductDisconnect() {
                Log.i(TAG, "DJI 제품 연결 해제됨");
            }

            @Override
            public void onProductConnect(BaseProduct product) {
                Log.i(TAG, "DJI 제품 연결됨: " + (product != null ? product.getModel().getDisplayName() : "null"));
            }

            @Override
            public void onProductChanged(BaseProduct product) {
                 Log.i(TAG, "DJI 제품 변경됨: " + (product != null ? product.getModel().getDisplayName() : "null"));
            }

            @Override
            public void onComponentChange(BaseProduct.ComponentKey key, BaseComponent oldComponent, BaseComponent newComponent) {
                if (newComponent != null) {
                    newComponent.setComponentListener(isConnected ->
                            Log.i(TAG, "DJI 구성 요소 " + key.toString() + " 연결됨: " + isConnected));
                }
                Log.i(TAG, String.format("DJI 구성 요소 변경, 키: %s, 이전 구성 요소: %s, 새 구성 요소: %s.",
                        key,
                        oldComponent == null ? "null" : oldComponent.getClass().getSimpleName(),
                        newComponent == null ? "null" : newComponent.getClass().getSimpleName()));
            }

            @Override
            public void onInitProcess(DJISDKInitEvent initEvent, int totalProcess) {
                // 이 콜백은 최신 SDK 버전에서 사용될 수 있습니다.
                Log.i(TAG, "DJI SDK 초기화 과정: " + initEvent.getInitializationState().toString() + " - " + totalProcess + "%");
            }

            @Override
            public void onDatabaseDownloadProgress(long current, long total) {
                // 비행 안전 데이터베이스 다운로드 진행률
                int progress = (int) (100 * current / total);
                Log.i(TAG, "DJI 비행 안전 데이터베이스 다운로드 진행률: " + progress + "%");
            }
        };

        // 중요: AndroidManifest.xml 파일에 DJI 앱 키가 올바르게 설정되어 있는지 확인하세요.
        // 예: <meta-data android:name="com.dji.sdk.API_KEY" android:value="YOUR_APP_KEY_HERE" />
        DJISDKManager.getInstance().registerApp(appContext, sdkManagerCallback);
    }
}
