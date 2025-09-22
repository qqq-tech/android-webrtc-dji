package com.drone.djiwebrtc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.drone.djiwebrtc.core.WebRTCClient;
import com.drone.djiwebrtc.core.WebRTCMediaOptions;
import com.drone.djiwebrtc.databinding.ActivityCameraStreamBinding;
import com.drone.djiwebrtc.network.PionSignalingClient;
import com.drone.djiwebrtc.ui.map.RouteOverlayManager;
import com.drone.djiwebrtc.util.PionConfigStore;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.osmdroid.config.Configuration;
import org.osmdroid.util.GeoPoint;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Activity that allows publishing the mobile device camera to the same Pion relay used for
 * drone video streaming.
 */
public class CameraStreamActivity extends AppCompatActivity {
    private static final String TAG = "CameraStream";
    private static final int REQUEST_PERMISSIONS = 0xCA01;
    private static final long LOCATION_UPDATE_INTERVAL_MS = 1500L;
    private static final float LOCATION_UPDATE_MIN_DISTANCE_M = 1.0f;
    private static final long LOCATION_UPDATE_NETWORK_INTERVAL_MS = 3000L;
    private static final float LOCATION_UPDATE_NETWORK_MIN_DISTANCE_M = 5.0f;
    private static final String STATE_STREAM_ID = "state_stream_id";
    private static final String STATE_CAMERA_INDEX = "state_camera_index";

    private ActivityCameraStreamBinding binding;
    private PionConfigStore pionConfigStore;
    private CameraEnumerator cameraEnumerator;
    private final List<CameraDescriptor> availableCameras = new ArrayList<>();
    private int selectedCameraIndex = -1;

    private StreamingState streamingState = StreamingState.IDLE;
    private boolean pendingStartAfterPermission = false;

    private PionSignalingClient signalingClient;
    private WebRTCClient webRtcClient;
    private VideoCapturer activeCapturer;
    private String activeStreamId;

    private EglBase eglBase;
    private RouteOverlayManager routeOverlayManager;
    private final List<GeoPoint> traveledPathPoints = new ArrayList<>();
    private double traveledDistanceMeters = 0d;
    private LocationManager locationManager;
    private boolean locationUpdatesActive = false;
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            handleLocationUpdate(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Deprecated but required for API compatibility.
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            runOnUiThread(CameraStreamActivity.this::updatePathStatus);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            runOnUiThread(CameraStreamActivity.this::showLocationUnavailableMessage);
        }
    };

    private enum StreamingState {
        IDLE,
        CONNECTING,
        STREAMING
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().load(getApplicationContext(), getApplicationContext().getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue("android-webrtc-dji");
        binding = ActivityCameraStreamBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbar.setNavigationOnClickListener(v -> onBackPressed());

        pionConfigStore = new PionConfigStore(this);

        eglBase = EglBase.create();
        binding.cameraPreview.init(eglBase.getEglBaseContext(), null);
        binding.cameraPreview.setEnableHardwareScaler(true);
        binding.cameraPreview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        binding.cameraPreview.setMirror(false);

        initialiseCameraEnumerator();
        restoreState(savedInstanceState);
        initialiseStreamIdField(savedInstanceState);
        updateSignalingSummary();

        routeOverlayManager = new RouteOverlayManager(binding.pathMap);
        routeOverlayManager.initialize();
        resetTraveledPath();

        binding.startButton.setOnClickListener(v -> onStartStreamingClicked());
        binding.stopButton.setOnClickListener(v -> stopStreamingInternal(getString(R.string.camera_stream_status_stopped)));

        if (availableCameras.isEmpty()) {
            updateStatus(getString(R.string.camera_stream_no_cameras));
        } else {
            updateStatus(getString(R.string.camera_stream_status_idle));
        }
        updateUiState();
    }

    private void initialiseCameraEnumerator() {
        if (Camera2Enumerator.isSupported(this)) {
            cameraEnumerator = new Camera2Enumerator(this);
        } else {
            cameraEnumerator = new Camera1Enumerator(false);
        }
        if (cameraEnumerator == null) {
            return;
        }
        availableCameras.clear();
        String[] deviceNames = cameraEnumerator.getDeviceNames();
        for (String deviceName : deviceNames) {
            boolean front = cameraEnumerator.isFrontFacing(deviceName);
            boolean back = cameraEnumerator.isBackFacing(deviceName);
            String label;
            if (front) {
                label = getString(R.string.camera_label_front);
            } else if (back) {
                label = getString(R.string.camera_label_back);
            } else {
                label = deviceName;
            }
            availableCameras.add(new CameraDescriptor(deviceName, label, front));
        }

        if (availableCameras.isEmpty()) {
            binding.cameraSelectorLayout.setEnabled(false);
            binding.cameraSelector.setEnabled(false);
            binding.startButton.setEnabled(false);
            return;
        }

        List<String> labels = new ArrayList<>();
        for (int i = 0; i < availableCameras.size(); i++) {
            CameraDescriptor descriptor = availableCameras.get(i);
            String label = descriptor.label;
            int duplicates = countLabelOccurrences(label, i);
            if (duplicates > 0) {
                label = label + " " + (duplicates + 1);
            }
            labels.add(label);
        }

        MaterialAutoCompleteTextView dropdown = binding.cameraSelector;
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                labels
        );
        dropdown.setAdapter(adapter);
        dropdown.setOnItemClickListener((parent, view, position, id) -> {
            setSelectedCamera(position, labels.get(position));
            updateUiState();
        });

        if (selectedCameraIndex < 0 && !availableCameras.isEmpty()) {
            setSelectedCamera(0, labels.get(0));
        } else if (selectedCameraIndex >= 0 && selectedCameraIndex < labels.size()) {
            setSelectedCamera(selectedCameraIndex, labels.get(selectedCameraIndex));
        }
    }

    private int countLabelOccurrences(String label, int beforeIndex) {
        int count = 0;
        for (int i = 0; i < beforeIndex; i++) {
            if (availableCameras.get(i).label.equals(label)) {
                count++;
            }
        }
        return count;
    }

    private void restoreState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        selectedCameraIndex = savedInstanceState.getInt(STATE_CAMERA_INDEX, selectedCameraIndex);
    }

    private void initialiseStreamIdField(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            String restored = savedInstanceState.getString(STATE_STREAM_ID);
            if (!TextUtils.isEmpty(restored)) {
                binding.streamIdInput.setText(restored);
                return;
            }
        }

        String configured = pionConfigStore.getStreamId();
        if (TextUtils.isEmpty(configured)) {
            binding.streamIdInput.setText(getString(R.string.camera_stream_default_stream_id));
        } else {
            binding.streamIdInput.setText(configured + "-mobile");
        }
    }

    private void updateSignalingSummary() {
        String signalingUrl = pionConfigStore.getSignalingUrl();
        if (TextUtils.isEmpty(signalingUrl)) {
            binding.signalingUrlValue.setText(getString(R.string.camera_stream_signaling_missing));
        } else {
            String label = getString(R.string.camera_stream_signaling_url_label);
            binding.signalingUrlValue.setText(label + ": " + signalingUrl);
        }
    }

    private void setSelectedCamera(int index, String displayLabel) {
        if (index < 0 || index >= availableCameras.size()) {
            return;
        }
        selectedCameraIndex = index;
        CameraDescriptor descriptor = availableCameras.get(index);
        binding.cameraSelector.setText(displayLabel, false);
        binding.cameraPreview.setMirror(descriptor.isFrontFacing);
    }

    private void onStartStreamingClicked() {
        if (streamingState != StreamingState.IDLE) {
            return;
        }
        binding.streamIdLayout.setError(null);

        if (availableCameras.isEmpty() || selectedCameraIndex < 0) {
            updateStatus(getString(R.string.camera_stream_no_cameras));
            return;
        }

        String streamId = getStreamIdInput();
        if (TextUtils.isEmpty(streamId)) {
            binding.streamIdLayout.setError(getString(R.string.error_required_field));
            return;
        }

        String signalingUrl = pionConfigStore.getSignalingUrl();
        if (TextUtils.isEmpty(signalingUrl)) {
            showError(getString(R.string.camera_stream_signaling_missing));
            return;
        }

        List<String> missingPermissions = new ArrayList<>();
        if (!hasCameraPermission()) {
            missingPermissions.add(Manifest.permission.CAMERA);
        }
        if (!hasLocationPermission()) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }

        if (!missingPermissions.isEmpty()) {
            pendingStartAfterPermission = true;
            ActivityCompat.requestPermissions(
                    this,
                    missingPermissions.toArray(new String[0]),
                    REQUEST_PERMISSIONS
            );
            return;
        }

        startStreamingInternal(streamId, signalingUrl);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startStreamingInternal(String streamId, String signalingUrl) {
        if (streamingState != StreamingState.IDLE) {
            return;
        }
        streamingState = StreamingState.CONNECTING;
        updateStatus(getString(R.string.camera_stream_status_connecting, streamId));
        updateUiState();

        activeStreamId = streamId;
        activeCapturer = createVideoCapturer();
        if (activeCapturer == null) {
            updateStatus(getString(R.string.camera_stream_status_error, getString(R.string.camera_stream_no_cameras)));
            streamingState = StreamingState.IDLE;
            updateUiState();
            return;
        }

        resetTraveledPath();
        startLocationUpdates();

        signalingClient = new PionSignalingClient(signalingUrl, "publisher", streamId);
        signalingClient.setListener(new PionSignalingClient.Listener() {
            @Override
            public void onOpen() {
                runOnUiThread(CameraStreamActivity.this::onSignalingConnected);
            }

            @Override
            public void onMessage(org.json.JSONObject message) {
                if (webRtcClient != null) {
                    webRtcClient.handleWebRTCMessage(message);
                }
            }

            @Override
            public void onError(String description, @Nullable String code) {
                String error = !TextUtils.isEmpty(description) ? description : code;
                if (TextUtils.isEmpty(error)) {
                    error = "SIGNALING";
                }
                String finalError = error;
                runOnUiThread(() -> {
                    String formatted = getString(R.string.camera_stream_status_error, finalError);
                    showError(formatted);
                    stopStreamingInternal(formatted);
                });
            }

            @Override
            public void onClosed() {
                runOnUiThread(() -> stopStreamingInternal(getString(R.string.camera_stream_status_stopped)));
            }
        });
        signalingClient.connect();
    }

    private void onSignalingConnected() {
        if (activeCapturer == null) {
            stopStreamingInternal(getString(R.string.camera_stream_status_error, getString(R.string.camera_stream_no_cameras)));
            return;
        }

        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("MOBILE_CAMERA", eglBase.getEglBaseContext());
        WebRTCMediaOptions options = new WebRTCMediaOptions()
                .setMediaStreamId(activeStreamId)
                .setVideoSourceId(activeStreamId + "-video")
                .setVideoResolution(1280, 720)
                .setFps(30);
        try {
            webRtcClient = new WebRTCClient(getApplicationContext(), activeCapturer, options, signalingClient, surfaceTextureHelper);
            webRtcClient.addVideoSink(binding.cameraPreview);
            webRtcClient.setConnectionChangedListener(() -> runOnUiThread(() ->
                    stopStreamingInternal(getString(R.string.camera_stream_status_peer_disconnected))));
            streamingState = StreamingState.STREAMING;
            updateStatus(getString(R.string.camera_stream_status_streaming, activeStreamId));
        } catch (RuntimeException e) {
            surfaceTextureHelper.dispose();
            Log.e(TAG, "Failed to start WebRTC client", e);
            String errorMessage = e.getMessage();
            if (TextUtils.isEmpty(errorMessage)) {
                errorMessage = "WebRTC";
            }
            String formatted = getString(R.string.camera_stream_status_error, errorMessage);
            showError(formatted);
            stopStreamingInternal(formatted);
        }
        updateUiState();
    }

    private VideoCapturer createVideoCapturer() {
        if (cameraEnumerator == null || selectedCameraIndex < 0 || selectedCameraIndex >= availableCameras.size()) {
            return null;
        }
        CameraDescriptor descriptor = availableCameras.get(selectedCameraIndex);
        VideoCapturer capturer = cameraEnumerator.createCapturer(descriptor.deviceName, new LoggingCameraEventsHandler(descriptor.label));
        if (capturer == null) {
            Log.e(TAG, "Unable to create capturer for camera: " + descriptor.deviceName);
        }
        return capturer;
    }

    private void stopStreamingInternal(@Nullable String statusMessage) {
        pendingStartAfterPermission = false;

        if (signalingClient != null) {
            signalingClient.setListener(null);
            signalingClient.disconnect();
            signalingClient = null;
        }

        if (webRtcClient != null) {
            webRtcClient.removeVideoSink(binding.cameraPreview);
            webRtcClient.dispose();
            webRtcClient = null;
        }

        if (activeCapturer != null) {
            activeCapturer = null;
        }

        activeStreamId = null;
        streamingState = StreamingState.IDLE;
        stopLocationUpdates();

        if (!availableCameras.isEmpty()) {
            if (TextUtils.isEmpty(statusMessage)) {
                updateStatus(getString(R.string.camera_stream_status_idle));
            } else {
                updateStatus(statusMessage);
            }
        } else {
            updateStatus(getString(R.string.camera_stream_no_cameras));
        }
        updateUiState();
    }

    private void updateStatus(String message) {
        binding.statusText.setText(message);
        binding.statusText.setContentDescription(message);
    }

    private void updateUiState() {
        boolean idle = streamingState == StreamingState.IDLE;
        boolean hasCamera = !availableCameras.isEmpty();
        binding.startButton.setEnabled(idle && hasCamera);
        binding.stopButton.setEnabled(!idle);
        binding.streamIdLayout.setEnabled(idle);
        binding.streamIdInput.setEnabled(idle);
        binding.cameraSelectorLayout.setEnabled(idle && hasCamera);
        binding.cameraSelector.setEnabled(idle && hasCamera);
        binding.connectionProgress.setVisibility(streamingState == StreamingState.CONNECTING ? View.VISIBLE : View.GONE);
    }

    private void resetTraveledPath() {
        traveledPathPoints.clear();
        traveledDistanceMeters = 0d;
        if (routeOverlayManager != null) {
            routeOverlayManager.updateTraveledPath(Collections.emptyList());
            routeOverlayManager.updateCurrentLocation(null);
        }
        updatePathStatus();
    }

    private void updatePathStatus() {
        if (binding == null) {
            return;
        }
        if (traveledPathPoints.isEmpty()) {
            String waiting = getString(R.string.camera_stream_path_waiting);
            binding.pathStatus.setText(waiting);
            binding.pathStatus.setContentDescription(waiting);
            return;
        }
        String distance = formatDistance(traveledDistanceMeters);
        String status = getString(R.string.camera_stream_path_status, traveledPathPoints.size(), distance);
        binding.pathStatus.setText(status);
        binding.pathStatus.setContentDescription(status);
    }

    private String formatDistance(double meters) {
        if (meters < 1000d) {
            return getString(R.string.camera_stream_distance_meters, meters);
        }
        double kilometers = meters / 1000d;
        return getString(R.string.camera_stream_distance_kilometers, kilometers);
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (locationUpdatesActive || !hasLocationPermission()) {
            return;
        }
        if (locationManager == null) {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        }
        if (locationManager == null) {
            Log.w(TAG, "Location service unavailable");
            return;
        }

        boolean requested = false;
        try {
            requested |= requestUpdatesForProvider(LocationManager.GPS_PROVIDER, LOCATION_UPDATE_INTERVAL_MS, LOCATION_UPDATE_MIN_DISTANCE_M);
            requested |= requestUpdatesForProvider(LocationManager.NETWORK_PROVIDER, LOCATION_UPDATE_NETWORK_INTERVAL_MS, LOCATION_UPDATE_NETWORK_MIN_DISTANCE_M);
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission revoked while requesting updates", e);
            return;
        }

        if (requested) {
            locationUpdatesActive = true;
            try {
                Location seedLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (seedLocation == null) {
                    seedLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (seedLocation != null) {
                    handleLocationUpdate(seedLocation);
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Unable to access last known location", e);
            }
        } else {
            showLocationUnavailableMessage();
        }
    }

    @SuppressLint("MissingPermission")
    private boolean requestUpdatesForProvider(String provider, long intervalMs, float minDistanceM) {
        if (locationManager == null) {
            return false;
        }
        try {
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.requestLocationUpdates(provider, intervalMs, minDistanceM, locationListener, Looper.getMainLooper());
                return true;
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Provider unavailable: " + provider, e);
        }
        return false;
    }

    private void stopLocationUpdates() {
        if (locationManager == null || !locationUpdatesActive) {
            return;
        }
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException e) {
            Log.w(TAG, "Failed to remove location updates", e);
        }
        locationUpdatesActive = false;
    }

    private void handleLocationUpdate(Location location) {
        if (location == null || !isTrackingActive()) {
            return;
        }
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return;
        }

        GeoPoint newPoint = new GeoPoint(latitude, longitude);
        if (!traveledPathPoints.isEmpty()) {
            GeoPoint lastPoint = traveledPathPoints.get(traveledPathPoints.size() - 1);
            double delta = lastPoint.distanceToAsDouble(newPoint);
            if (delta < 0.5d) {
                return;
            }
            traveledDistanceMeters += delta;
        }
        traveledPathPoints.add(newPoint);

        if (routeOverlayManager != null) {
            routeOverlayManager.updateTraveledPath(new ArrayList<>(traveledPathPoints));
            routeOverlayManager.updateCurrentLocation(newPoint);
        }
        updatePathStatus();
    }

    private boolean isTrackingActive() {
        return streamingState == StreamingState.CONNECTING || streamingState == StreamingState.STREAMING;
    }

    private void showLocationUnavailableMessage() {
        if (binding == null) {
            return;
        }
        String message = getString(R.string.camera_stream_location_disabled);
        binding.pathStatus.setText(message);
        binding.pathStatus.setContentDescription(message);
        if (streamingState != StreamingState.IDLE) {
            showError(message);
        }
    }

    private String getStreamIdInput() {
        CharSequence text = binding.streamIdInput.getText();
        return text == null ? "" : text.toString().trim();
    }

    private void showError(String message) {
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean cameraGranted = hasCameraPermission();
            boolean locationGranted = hasLocationPermission();
            if (cameraGranted && locationGranted && pendingStartAfterPermission) {
                String streamId = getStreamIdInput();
                String signalingUrl = pionConfigStore.getSignalingUrl();
                if (!TextUtils.isEmpty(streamId) && !TextUtils.isEmpty(signalingUrl)) {
                    startStreamingInternal(streamId, signalingUrl);
                }
            } else {
                if (!cameraGranted) {
                    showError(getString(R.string.camera_stream_permission_required));
                }
                if (!locationGranted) {
                    showError(getString(R.string.camera_stream_location_permission_required));
                }
            }
            pendingStartAfterPermission = false;
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_STREAM_ID, getStreamIdInput());
        outState.putInt(STATE_CAMERA_INDEX, selectedCameraIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) {
            binding.pathMap.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (binding != null) {
            binding.pathMap.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        stopStreamingInternal(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (binding != null) {
            binding.cameraPreview.release();
            binding.pathMap.onDetach();
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private static class CameraDescriptor {
        final String deviceName;
        final String label;
        final boolean isFrontFacing;

        CameraDescriptor(String deviceName, String label, boolean isFrontFacing) {
            this.deviceName = deviceName;
            this.label = label;
            this.isFrontFacing = isFrontFacing;
        }
    }

    private static class LoggingCameraEventsHandler implements CameraVideoCapturer.CameraEventsHandler {
        private final String cameraLabel;

        LoggingCameraEventsHandler(String cameraLabel) {
            this.cameraLabel = cameraLabel;
        }

        @Override
        public void onCameraError(String errorDescription) {
            Log.e(TAG, "Camera error (" + cameraLabel + "): " + errorDescription);
        }

        @Override
        public void onCameraDisconnected() {
            Log.w(TAG, "Camera disconnected: " + cameraLabel);
        }

        @Override
        public void onCameraFreezed(String errorDescription) {
            Log.w(TAG, "Camera frozen (" + cameraLabel + "): " + errorDescription);
        }

        @Override
        public void onCameraOpening(String cameraName) {
            Log.d(TAG, "Opening camera: " + cameraName);
        }

        @Override
        public void onFirstFrameAvailable() {
            Log.d(TAG, "First frame available from camera: " + cameraLabel);
        }

        @Override
        public void onCameraClosed() {
            Log.d(TAG, "Camera closed: " + cameraLabel);
        }
    }
}
