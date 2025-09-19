package com.drone.djiwebrtc;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.drone.djiwebrtc.core.DJIVideoCapturer;
import com.drone.djiwebrtc.databinding.ActivityMainBinding;
import com.drone.djiwebrtc.ui.adapter.WaypointAdapter;
import com.drone.djiwebrtc.ui.map.RouteOverlayManager;
import com.drone.djiwebrtc.ui.model.Waypoint;
import com.drone.djiwebrtc.ui.viewmodel.FlightPathViewModel;
import com.drone.djiwebrtc.util.DjiProductHelper;
import com.drone.djiwebrtc.webrtc.DroneVideoPreview;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.osmdroid.config.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private BottomSheetBehavior<View> bottomSheetBehavior;
    private RouteOverlayManager routeOverlayManager;
    private FlightPathViewModel flightPathViewModel;
    private WaypointAdapter waypointAdapter;
    private DroneVideoPreview droneVideoPreview;

    private static final String TAG = MainActivity.class.getName();
    public static final String FLAG_CONNECTION_CHANGE = "dji_sdk_connection_change";
    private Handler mHandler;

    private static final String[] REQUIRED_PERMISSION_LIST = new String[]{
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
    };
    private List<String> missingPermission = new ArrayList<>();
    private AtomicBoolean isRegistrationInProgress = new AtomicBoolean(false);
    private static final int REQUEST_PERMISSION_CODE = 12345;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context appContext = getApplicationContext(); // appContext 변수명 사용

        Configuration.getInstance().load(appContext, appContext.getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue("android-webrtc-dji");


        // When the compile and target version is higher than 22, please request the following permission at runtime to ensure the SDK works well.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkAndRequestPermissions();
        }
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mHandler = new Handler(Looper.getMainLooper());

        setSupportActionBar(binding.toolbar);

        initializeBottomSheet();
        initializeRecycler();
        initializeMap();
        initializeVideo();

        flightPathViewModel = new ViewModelProvider(this).get(FlightPathViewModel.class);
        observeFlightData();

        if (savedInstanceState == null) {
            flightPathViewModel.seedDemoData();
        }
    }


    private void checkAndRequestPermissions() {
        // Check for permissions
        for (String eachPermission : REQUIRED_PERMISSION_LIST) {
            if (ContextCompat.checkSelfPermission(this, eachPermission) != PackageManager.PERMISSION_GRANTED) {
                missingPermission.add(eachPermission);
            }
        }
        // Request for missing permissions
        if (missingPermission.isEmpty()) {
            startSDKRegistration();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showToast("Need to grant the permissions!");
            ActivityCompat.requestPermissions(this,
                    missingPermission.toArray(new String[missingPermission.size()]),
                    REQUEST_PERMISSION_CODE);
        }

    }

    /**
     * Result of runtime permission request
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Check for granted permission and remove from missing list
        if (requestCode == REQUEST_PERMISSION_CODE) {
            for (int i = grantResults.length - 1; i >= 0; i--) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    missingPermission.remove(permissions[i]);
                }
            }
        }
        // If there is enough permission, we will start the registration
        if (missingPermission.isEmpty()) {
            startSDKRegistration();
        } else {
            showToast("Missing permissions!!!");
        }
    }

    private void startSDKRegistration() {
        if (isRegistrationInProgress.compareAndSet(false, true)) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    showToast("registering, pls wait...");

                    DJISDKManager.getInstance().registerApp(MainActivity.this.getApplicationContext(), new DJISDKManager.SDKManagerCallback() {
                        @Override
                        public void onRegister(DJIError djiError) {
                            if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                                showToast("Register Success");
                                DJISDKManager.getInstance().startConnectionToProduct();
                            } else {
                                showToast("Register sdk fails, please check the bundle id and network connection!");
                            }
                            Log.v(TAG, djiError.getDescription());
                        }

                        @Override
                        public void onProductDisconnect() {
                            Log.d(TAG, "onProductDisconnect");
                            showToast("Product Disconnected");
                            notifyStatusChange();

                        }
                        @Override
                        public void onProductConnect(BaseProduct baseProduct) {
                            Log.d(TAG, String.format("onProductConnect newProduct:%s", baseProduct));
                            showToast("Product Connected");
                            notifyStatusChange();

                        }

                        @Override
                        public void onProductChanged(BaseProduct baseProduct) {

                        }

                        @Override
                        public void onComponentChange(BaseProduct.ComponentKey componentKey, BaseComponent oldComponent,
                                                      BaseComponent newComponent) {

                            if (newComponent != null) {
                                newComponent.setComponentListener(new BaseComponent.ComponentListener() {

                                    @Override
                                    public void onConnectivityChange(boolean isConnected) {
                                        Log.d(TAG, "onComponentConnectivityChanged: " + isConnected);
                                        notifyStatusChange();
                                    }
                                });
                            }
                            Log.d(TAG,
                                    String.format("onComponentChange key:%s, oldComponent:%s, newComponent:%s",
                                            componentKey,
                                            oldComponent,
                                            newComponent));

                        }

                        @Override
                        public void onInitProcess(DJISDKInitEvent djisdkInitEvent, int i) {

                        }

                        @Override
                        public void onDatabaseDownloadProgress(long l, long l1) {

                        }

                    });
                }
            });
        }
    }

    private void notifyStatusChange() {
        mHandler.removeCallbacks(updateRunnable);
        mHandler.postDelayed(updateRunnable, 500);
    }

    private Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            Intent intent = new Intent(FLAG_CONNECTION_CHANGE);
            sendBroadcast(intent);
        }
    };

    private void showToast(final String toastMsg) {

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), toastMsg, Toast.LENGTH_LONG).show();
            }
        });

    }

    private void initializeBottomSheet() {
        // XML 레이아웃 변경 후, 이 코드가 올바르게 작동해야 합니다.
        bottomSheetBehavior = BottomSheetBehavior.from(binding.flightPathSheet);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        binding.togglePathButton.setText(getString(R.string.toggle_path_panel));
        binding.togglePathButton.setOnClickListener(v -> toggleBottomSheet());
        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                updateToggleText(newState);
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                // 필요하다면 FloatingActionButton의 위치를 조정하는 로직을 여기에 추가할 수 있습니다.
                // 예: binding.togglePathButton.setTranslationY(-slideOffset * someCalculatedValue);
            }
        });
    }

    private void updateToggleText(int state) {
        if (state == BottomSheetBehavior.STATE_EXPANDED) {
            binding.togglePathButton.setText(getString(R.string.toggle_path_panel_close));
        } else {
            binding.togglePathButton.setText(getString(R.string.toggle_path_panel));
        }
    }

    private void toggleBottomSheet() {
        int currentState = bottomSheetBehavior.getState();
        if (currentState == BottomSheetBehavior.STATE_EXPANDED || currentState == BottomSheetBehavior.STATE_HALF_EXPANDED) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private void initializeRecycler() {
        waypointAdapter = new WaypointAdapter();
        binding.waypointList.setLayoutManager(new LinearLayoutManager(this));
        binding.waypointList.setAdapter(waypointAdapter);
    }

    private void initializeMap() {
        routeOverlayManager = new RouteOverlayManager(binding.mapView);
        routeOverlayManager.initialize();
    }

    private void initializeVideo() {
        String droneDisplayName = DjiProductHelper.resolveDisplayName();
        DJIVideoCapturer capturer = new DJIVideoCapturer(droneDisplayName);
        droneVideoPreview = new DroneVideoPreview(this, binding.videoRenderer, capturer);
    }

    private void observeFlightData() {
        flightPathViewModel.getPlannedWaypoints().observe(this, waypoints -> {
            waypointAdapter.submitList(waypoints);
            routeOverlayManager.updatePlannedPath(waypoints);
            binding.pathSummary.setText(buildSummary(waypoints));
        });

        flightPathViewModel.getTraveledPath().observe(this, path -> routeOverlayManager.updateTraveledPath(path));

        flightPathViewModel.getNextWaypoint().observe(this, waypoint -> {
            routeOverlayManager.updateNextWaypoint(waypoint);
            if (waypoint == null) {
                binding.nextWaypointValue.setText(getString(R.string.no_next_waypoint));
            } else {
                binding.nextWaypointValue.setText(waypoint.getName());
            }
        });

        flightPathViewModel.getCurrentLocation().observe(this, routeOverlayManager::updateCurrentLocation);
    }

    private String buildSummary(List<Waypoint> waypoints) {
        int count = waypoints == null ? 0 : waypoints.size();
        double distanceKm = routeOverlayManager.getPlannedDistanceMeters() / 1000.0;
        return String.format(Locale.getDefault(), getString(R.string.path_summary_format), count, distanceKm);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (droneVideoPreview != null) {
            droneVideoPreview.start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.mapView.onResume();
    }

    @Override
    protected void onPause() {
        binding.mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (droneVideoPreview != null) {
            droneVideoPreview.stop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        binding.mapView.onDetach();
        if (droneVideoPreview != null) {
            droneVideoPreview.destroy();
        }
        super.onDestroy();
    }
}
