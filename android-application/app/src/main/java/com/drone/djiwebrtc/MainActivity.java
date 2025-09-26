package com.drone.djiwebrtc;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.drone.djiwebrtc.core.DJIVideoCapturer;
import com.drone.djiwebrtc.databinding.ActivityMainBinding;
import com.drone.djiwebrtc.util.PionConfigStore;
import com.drone.djiwebrtc.ui.adapter.WaypointAdapter;
import com.drone.djiwebrtc.ui.map.RouteOverlayManager;
import com.drone.djiwebrtc.ui.model.Waypoint;
import com.drone.djiwebrtc.ui.viewmodel.FlightPathViewModel;
import com.drone.djiwebrtc.util.DjiProductHelper;
import com.drone.djiwebrtc.webrtc.DroneVideoPreview;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
    private PionConfigStore pionConfigStore;
    private SharedPreferences streamingPreferences;

    private static final String TAG = MainActivity.class.getName();
    public static final String FLAG_CONNECTION_CHANGE = "dji_sdk_connection_change";
    private Handler mHandler;
    private StreamSource selectedStreamSource = StreamSource.DRONE;

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
    private static final String PREF_STREAMING = "streaming_preferences";
    private static final String KEY_STREAM_SOURCE = "key_stream_source";
    private static final String KEY_INCLUDE_GPS = "key_include_gps";
    private static final String KEY_INCLUDE_AUDIO = "key_include_audio";

    private enum StreamSource {
        DRONE,
        MOBILE
    }

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
        pionConfigStore = new PionConfigStore(this);
        streamingPreferences = getSharedPreferences(PREF_STREAMING, MODE_PRIVATE);

        setSupportActionBar(binding.toolbar);

        initializeSettingsDrawer();
        initializeBottomSheet();
        initializeRecycler();
        initializeMap();
        initializeVideo();
        initializeStreamOptions();

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
        bottomSheetBehavior.setHideable(true);
        bottomSheetBehavior.setPeekHeight(0);
        bottomSheetBehavior.setSkipCollapsed(true);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
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
        if (state == BottomSheetBehavior.STATE_EXPANDED || state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
            binding.togglePathButton.setText(getString(R.string.toggle_path_panel_close));
        } else {
            binding.togglePathButton.setText(getString(R.string.toggle_path_panel));
        }
    }

    private void toggleBottomSheet() {
        int currentState = bottomSheetBehavior.getState();
        if (currentState == BottomSheetBehavior.STATE_EXPANDED || currentState == BottomSheetBehavior.STATE_HALF_EXPANDED) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
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

    private void initializeSettingsDrawer() {
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this,
                binding.drawerLayout,
                binding.toolbar,
                R.string.streaming_drawer_open,
                R.string.streaming_drawer_close);
        binding.drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        binding.settingsNavigationView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_open_mobile_stream) {
                binding.drawerLayout.closeDrawer(GravityCompat.END);
                binding.drawerLayout.post(() -> {
                    Intent intent = new Intent(this, CameraStreamActivity.class);
                    startActivity(intent);
                });
                return true;
            }
            return false;
        });
    }

    private void initializeStreamOptions() {
        boolean includeGps = streamingPreferences.getBoolean(KEY_INCLUDE_GPS, true);
        boolean includeAudio = streamingPreferences.getBoolean(KEY_INCLUDE_AUDIO, false);
        String savedSource = streamingPreferences.getString(KEY_STREAM_SOURCE, StreamSource.DRONE.name());
        selectedStreamSource = parseStreamSource(savedSource);

        binding.includeGpsSwitch.setChecked(includeGps);
        binding.includeAudioSwitch.setChecked(includeAudio);

        int checkedId = selectedStreamSource == StreamSource.DRONE ? R.id.streamSourceDrone : R.id.streamSourceMobile;
        binding.streamingSourceToggle.check(checkedId);

        binding.streamingSourceToggle.addOnButtonCheckedListener((group, checkedId1, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId1 == R.id.streamSourceDrone) {
                selectedStreamSource = StreamSource.DRONE;
            } else if (checkedId1 == R.id.streamSourceMobile) {
                selectedStreamSource = StreamSource.MOBILE;
            }
            streamingPreferences.edit().putString(KEY_STREAM_SOURCE, selectedStreamSource.name()).apply();
            updateStreamingActionButton();
            updateStreamingSummary();
        });

        binding.includeGpsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            streamingPreferences.edit().putBoolean(KEY_INCLUDE_GPS, isChecked).apply();
            updateStreamingSummary();
        });

        binding.includeAudioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            streamingPreferences.edit().putBoolean(KEY_INCLUDE_AUDIO, isChecked).apply();
            updateStreamingSummary();
        });

        binding.startStreamingButton.setOnClickListener(v -> handleStreamingAction());

        updateStreamingActionButton();
        updateStreamingSummary();
    }

    private StreamSource parseStreamSource(String value) {
        if (value != null && StreamSource.MOBILE.name().equalsIgnoreCase(value)) {
            return StreamSource.MOBILE;
        }
        return StreamSource.DRONE;
    }

    private void updateStreamingActionButton() {
        if (selectedStreamSource == StreamSource.MOBILE) {
            binding.startStreamingButton.setText(getString(R.string.streaming_action_mobile));
        } else {
            binding.startStreamingButton.setText(getString(R.string.streaming_action_drone));
        }
    }

    private void updateStreamingSummary() {
        String sourceLabel = selectedStreamSource == StreamSource.MOBILE
                ? getString(R.string.streaming_source_mobile)
                : getString(R.string.streaming_source_drone);
        String gpsLabel = binding.includeGpsSwitch.isChecked()
                ? getString(R.string.streaming_option_enabled)
                : getString(R.string.streaming_option_disabled);
        String audioLabel = binding.includeAudioSwitch.isChecked()
                ? getString(R.string.streaming_option_enabled)
                : getString(R.string.streaming_option_disabled);
        binding.streamingSummaryText.setText(getString(R.string.streaming_summary_format, sourceLabel, gpsLabel, audioLabel));
    }

    private void handleStreamingAction() {
        binding.drawerLayout.closeDrawer(GravityCompat.START);
        if (selectedStreamSource == StreamSource.MOBILE) {
            Intent intent = new Intent(this, CameraStreamActivity.class);
            startActivity(intent);
        } else {
            Toast.makeText(this, getString(R.string.streaming_action_drone_message), Toast.LENGTH_SHORT).show();
        }
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
        return String.format("ko_KR", getString(R.string.path_summary_format), count, distanceKm);
        //return String.format(Locale.getDefault(), getString(R.string.path_summary_format), count, distanceKm);
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

    @Override
    public void onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
            binding.drawerLayout.closeDrawer(GravityCompat.END);
            return;
        }
        super.onBackPressed();
    }

    private void showPionSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_pion_settings, null, false);
        TextInputLayout signalingUrlLayout = dialogView.findViewById(R.id.pionSignalingUrlLayout);
        TextInputLayout streamIdLayout = dialogView.findViewById(R.id.pionStreamIdLayout);
        TextInputEditText signalingUrlInput = dialogView.findViewById(R.id.pionSignalingUrlInput);
        TextInputEditText streamIdInput = dialogView.findViewById(R.id.pionStreamIdInput);

        signalingUrlInput.setText(pionConfigStore.getSignalingUrl());
        streamIdInput.setText(pionConfigStore.getStreamId());

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pion_settings_title)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String signalingUrl = signalingUrlInput.getText() != null ? signalingUrlInput.getText().toString().trim() : "";
            String streamId = streamIdInput.getText() != null ? streamIdInput.getText().toString().trim() : "";
            boolean valid = true;

            if (TextUtils.isEmpty(signalingUrl)) {
                signalingUrlLayout.setError(getString(R.string.error_required_field));
                valid = false;
            } else {
                signalingUrlLayout.setError(null);
            }

            if (TextUtils.isEmpty(streamId)) {
                streamIdLayout.setError(getString(R.string.error_required_field));
                valid = false;
            } else {
                streamIdLayout.setError(null);
            }

            if (!valid) {
                return;
            }

            pionConfigStore.setSignalingUrl(signalingUrl);
            pionConfigStore.setStreamId(streamId);
            Toast.makeText(this, getString(R.string.pion_settings_saved), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));

        dialog.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_action_configure_pion) {
            showPionSettingsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
