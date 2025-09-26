package com.drone.djiwebrtc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.drone.djiwebrtc.core.SignalingMessageBuilder;
import com.drone.djiwebrtc.core.WebRTCClient;
import com.drone.djiwebrtc.core.WebRTCMediaOptions;
import com.drone.djiwebrtc.databinding.ActivityCameraStreamBinding;
import com.drone.djiwebrtc.network.PionSignalingClient;
import com.drone.djiwebrtc.ui.map.RouteOverlayManager;
import com.drone.djiwebrtc.util.PionConfigStore;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.json.JSONException;
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

public class CameraStreamActivity extends AppCompatActivity {
    private static final String TAG = "CameraStreamActivity";
    private static final int REQUEST_PERMISSIONS = 0xCA01;
    private static final long LOCATION_UPDATE_INTERVAL_MS = 1500L;
    private static final float LOCATION_UPDATE_MIN_DISTANCE_M = 1.0f;
    private static final long LOCATION_UPDATE_NETWORK_INTERVAL_MS = 3000L;
    private static final float LOCATION_UPDATE_NETWORK_MIN_DISTANCE_M = 5.0f;
    private static final String STATE_STREAM_ID = "state_stream_id";
    private static final String STATE_CAMERA_INDEX = "state_camera_index";
    private static final long TELEMETRY_MIN_INTERVAL_MS = 1000L;

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
    private SurfaceTextureHelper surfaceTextureHelper; 
    private RouteOverlayManager routeOverlayManager;
    private final List<GeoPoint> traveledPathPoints = new ArrayList<>();
    private double traveledDistanceMeters = 0d;
    private LocationManager locationManager;
    private boolean locationUpdatesActive = false;
    private long lastTelemetrySentRealtime = 0L;
    private Location lastKnownLocation;
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            handleLocationUpdate(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
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

        restoreState(savedInstanceState);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbar.setNavigationOnClickListener(v -> onBackPressed());

        pionConfigStore = new PionConfigStore(this);

        eglBase = EglBase.create();
        configurePreviewSurface();

        initialiseCameraEnumerator();
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

    private void configurePreviewSurface() {
        Log.d(TAG, "configurePreviewSurface called.");
        Log.d(TAG, "EGL Context for preview (will be used later): " + (eglBase != null ? eglBase.getEglBaseContext() : "null"));

        binding.cameraPreview.setZOrderOnTop(true);
        Log.d(TAG, "Set ZOrderOnTop(true) for cameraPreview.");

        binding.cameraPreview.setEnableHardwareScaler(false);
        binding.cameraPreview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        binding.cameraPreview.setKeepScreenOn(true);
        binding.cameraPreview.setVisibility(View.VISIBLE);
        Log.d(TAG, "configurePreviewSurface completed (ZOrderOnTop, scaler false, FIT).");
    }

    private void initialiseCameraEnumerator() {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(this)) {
            enumerator = new Camera2Enumerator(this);
        } else {
            enumerator = new Camera1Enumerator(false);
        }
        updateCameraEnumerator(enumerator, true);
    }

    private void updateCameraEnumerator(@Nullable CameraEnumerator enumerator, boolean preserveSelection) {
        CameraDescriptor previousSelection = null;
        if (preserveSelection && selectedCameraIndex >= 0 && selectedCameraIndex < availableCameras.size()) {
            previousSelection = availableCameras.get(selectedCameraIndex);
        }

        cameraEnumerator = enumerator;
        availableCameras.clear();
        if (cameraEnumerator != null) {
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
        }

        final List<String> displayLabels = buildCameraDisplayLabels();
        MaterialAutoCompleteTextView dropdown = binding.cameraSelector;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                displayLabels
        );
        dropdown.setAdapter(adapter);
        dropdown.setOnItemClickListener((parent, view, position, id) -> {
            setSelectedCamera(position, displayLabels.get(position));
            updateUiState();
        });

        if (availableCameras.isEmpty()) {
            selectedCameraIndex = -1;
            dropdown.setText(null, false);
            binding.cameraSelectorLayout.setEnabled(false);
            binding.cameraSelector.setEnabled(false);
            binding.startButton.setEnabled(false);
            return;
        }

        binding.cameraSelectorLayout.setEnabled(true);
        binding.cameraSelector.setEnabled(true);

        int indexToSelect = -1;
        if (previousSelection != null) {
            indexToSelect = findCameraIndex(previousSelection.deviceName);
            if (indexToSelect < 0) {
                indexToSelect = findCameraIndexByFacing(previousSelection.isFrontFacing);
            }
        } else if (selectedCameraIndex >= 0 && selectedCameraIndex < availableCameras.size()) {
            indexToSelect = selectedCameraIndex;
        }

        if (indexToSelect < 0) {
            indexToSelect = 0;
        }
        setSelectedCamera(indexToSelect, displayLabels.get(indexToSelect));
    }

    private List<String> buildCameraDisplayLabels() {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < availableCameras.size(); i++) {
            CameraDescriptor descriptor = availableCameras.get(i);
            int duplicates = 0;
            for (int j = 0; j < i; j++) {
                if (availableCameras.get(j).label.equals(descriptor.label)) {
                    duplicates++;
                }
            }
            String displayLabel = descriptor.label;
            if (duplicates > 0) {
                displayLabel = displayLabel + " " + (duplicates + 1);
            }
            labels.add(displayLabel);
        }
        return labels;
    }

    private int findCameraIndex(String deviceName) {
        for (int i = 0; i < availableCameras.size(); i++) {
            if (TextUtils.equals(availableCameras.get(i).deviceName, deviceName)) {
                return i;
            }
        }
        return -1;
    }

    private int findCameraIndexByFacing(boolean frontFacing) {
        for (int i = 0; i < availableCameras.size(); i++) {
            CameraDescriptor descriptor = availableCameras.get(i);
            if (descriptor.isFrontFacing == frontFacing) {
                return i;
            }
        }
        return availableCameras.isEmpty() ? -1 : 0;
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

        // SurfaceTextureHelper는 WebRTCClient보다 먼저, Capturer가 생성된 후에 생성
        if (this.surfaceTextureHelper != null) {
            this.surfaceTextureHelper.dispose(); // 이전 것이 있다면 명시적 해제
        }
        this.surfaceTextureHelper = SurfaceTextureHelper.create("MOBILE_CAMERA_STREAM", eglBase.getEglBaseContext());
        Log.d(TAG, "SurfaceTextureHelper created for new stream: " + (this.surfaceTextureHelper != null));


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
        Log.d(TAG, "onSignalingConnected called. activeCapturer: " + (activeCapturer != null) + ", surfaceTextureHelper: " + (surfaceTextureHelper != null));
        if (activeCapturer == null || surfaceTextureHelper == null) {
            String errorMsg = "activeCapturer or surfaceTextureHelper is null in onSignalingConnected.";
            Log.e(TAG, errorMsg);
            stopStreamingInternal(getString(R.string.camera_stream_status_error, errorMsg));
            return;
        }

        WebRTCMediaOptions options = new WebRTCMediaOptions()
                .setMediaStreamId(activeStreamId)
                .setVideoSourceId(activeStreamId + "-video")
                .setVideoResolution(1280, 720)
                .setFps(30);
        try {
            Log.d(TAG, "Attempting to create WebRTCClient.");
            webRtcClient = new WebRTCClient(getApplicationContext(), activeCapturer, options, signalingClient, this.surfaceTextureHelper);
            Log.d(TAG, "WebRTCClient created: " + (webRtcClient != null));

            if (webRtcClient != null && binding.cameraPreview != null) {
                Log.d(TAG, "Preparing to init and add cameraPreview as VideoSink.");
                binding.cameraPreview.post(() -> {
                    int width = binding.cameraPreview.getWidth();
                    int height = binding.cameraPreview.getHeight();
                    Log.d(TAG, "Inside post. cameraPreview dimensions: " + width + "x" + height);

                    if (width > 0 && height > 0) {
                        if (webRtcClient != null && eglBase != null) {
                            Log.d(TAG, "Dimensions are valid. Initializing cameraPreview EGL.");
                            binding.cameraPreview.init(eglBase.getEglBaseContext(), new RendererCommon.RendererEvents() {
                                @Override
                                public void onFirstFrameRendered() {
                                    Log.i(TAG, "****** CameraPreview: First frame rendered! ******");
                                }

                                @Override
                                public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
                                    Log.i(TAG, "CameraPreview: Frame resolution changed to " + videoWidth + "x" + videoHeight + " rotation " + rotation);
                                }
                            });
                            Log.d(TAG, "cameraPreview EGL initialized.");
                            binding.cameraPreview.clearImage();

                            Log.d(TAG, "Attempting to add VideoSink.");
                            webRtcClient.addVideoSink(binding.cameraPreview);
                            Log.d(TAG, "cameraPreview VideoSink added to WebRTCClient (inside post).");
                        } else {
                            Log.e(TAG, "WebRTCClient or eglBase is null inside post. Cannot init/add VideoSink.");
                        }
                    } else {
                        Log.e(TAG, "cameraPreview dimensions are still 0x0 or invalid inside post. VideoSink NOT added.");
                    }
                });
            } else {
                Log.e(TAG, "WebRTCClient or cameraPreview is null. Cannot prepare to add VideoSink.");
            }

            webRtcClient.setConnectionChangedListener(() -> runOnUiThread(() ->
                    stopStreamingInternal(getString(R.string.camera_stream_status_peer_disconnected))));
            streamingState = StreamingState.STREAMING;
            updateStatus(getString(R.string.camera_stream_status_streaming, activeStreamId));
            if (lastKnownLocation != null) {
                sendTelemetryUpdate(lastKnownLocation, true);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to start WebRTC client in onSignalingConnected", e);
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
        VideoCapturer capturer = cameraEnumerator.createCapturer(
                descriptor.deviceName,
                new LoggingCameraEventsHandler(descriptor.label));
        if (capturer == null && !(cameraEnumerator instanceof Camera1Enumerator)) {
            updateCameraEnumerator(new Camera1Enumerator(false), true);
            if (cameraEnumerator != null && selectedCameraIndex >= 0 && selectedCameraIndex < availableCameras.size()) {
                descriptor = availableCameras.get(selectedCameraIndex);
                capturer = cameraEnumerator.createCapturer(
                        descriptor.deviceName,
                        new LoggingCameraEventsHandler(descriptor.label));
            }
        }
        if (capturer == null) {
            Log.e(TAG, "Unable to create capturer for camera: " + descriptor.deviceName);
        }
        return capturer;
    }

    private void stopStreamingInternal(@Nullable String statusMessage) {
        Log.i(TAG, "stopStreamingInternal called. Current state: " + streamingState + ". Status message: " + statusMessage);
        pendingStartAfterPermission = false;

        // 1. 시그널링 클라이언트 연결 해제
        if (signalingClient != null) {
            signalingClient.setListener(null);
            signalingClient.disconnect();
            signalingClient = null;
            Log.d(TAG, "Signaling client disconnected and nulled.");
        }

        // 2. WebRTCClient에서 VideoSink (미리보기) 제거
        if (webRtcClient != null && binding != null && binding.cameraPreview != null) {
            webRtcClient.removeVideoSink(binding.cameraPreview);
            Log.d(TAG, "VideoSink removed from WebRTCClient.");
        }

        // 3. 카메라 캡처 중지
        if (activeCapturer != null) {
            try {
                Log.d(TAG, "Attempting to stop activeCapturer capture.");
                activeCapturer.stopCapture(); 
                Log.d(TAG, "activeCapturer.stopCapture() called.");
            } catch (InterruptedException e) {
                Log.e(TAG, "InterruptedException stopping activeCapturer capture", e);
                Thread.currentThread().interrupt(); 
            } catch (Exception e) { 
                Log.e(TAG, "Exception stopping activeCapturer capture", e);
            }
        }
        
        // 4. WebRTCClient 해제
        if (webRtcClient != null) {
            Log.d(TAG, "Attempting to dispose WebRTCClient.");
            webRtcClient.dispose();
            webRtcClient = null;
            Log.d(TAG, "WebRTCClient disposed and nulled.");
        }

        // 5. 카메라 캡처러 객체 자체 해제 (카메라 장치 닫기 시작)
        if (activeCapturer != null) {
            Log.d(TAG, "Attempting to dispose activeCapturer object.");
            activeCapturer.dispose(); 
            activeCapturer = null;
            Log.d(TAG, "activeCapturer object disposed and nulled.");
        }

        // 6. SurfaceTextureHelper 해제 (카메라가 닫힌 후)
        if (surfaceTextureHelper != null) {
            Log.d(TAG, "Attempting to dispose SurfaceTextureHelper.");
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
            Log.d(TAG, "SurfaceTextureHelper disposed and nulled.");
        }

        activeStreamId = null;
        streamingState = StreamingState.IDLE;
        stopLocationUpdates();
        lastTelemetrySentRealtime = 0L;
        lastKnownLocation = null;

        // 7. SurfaceViewRenderer EGL 리소스 해제
        if (binding != null && binding.cameraPreview != null) {
            binding.cameraPreview.clearImage();
            Log.d(TAG, "cameraPreview image cleared.");
            binding.cameraPreview.release(); 
            Log.d(TAG, "cameraPreview EGL released.");
        }

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
        Log.i(TAG, "stopStreamingInternal completed.");
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
        lastTelemetrySentRealtime = 0L;
        lastKnownLocation = null;
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

        lastKnownLocation = new Location(location);
        sendTelemetryUpdate(location, false);

        GeoPoint newPoint = new GeoPoint(latitude, longitude);
        boolean pathUpdated = false;
        if (traveledPathPoints.isEmpty()) {
            traveledPathPoints.add(newPoint);
            pathUpdated = true;
        } else {
            GeoPoint lastPoint = traveledPathPoints.get(traveledPathPoints.size() - 1);
            double delta = lastPoint.distanceToAsDouble(newPoint);
            if (delta >= 0.5d) {
                traveledDistanceMeters += delta;
                traveledPathPoints.add(newPoint);
                pathUpdated = true;
            }
        }

        if (routeOverlayManager != null) {
            if (pathUpdated) {
                routeOverlayManager.updateTraveledPath(new ArrayList<>(traveledPathPoints));
            }
            routeOverlayManager.updateCurrentLocation(newPoint);
        }
        updatePathStatus();
    }

    private void sendTelemetryUpdate(Location location, boolean forceImmediate) {
        if (signalingClient == null || !signalingClient.isConnected()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!forceImmediate && lastTelemetrySentRealtime > 0L
                && now - lastTelemetrySentRealtime < TELEMETRY_MIN_INTERVAL_MS) {
            return;
        }
        lastTelemetrySentRealtime = now;

        long timestamp = location.getTime() > 0L ? location.getTime() : System.currentTimeMillis();
        Double altitude = location.hasAltitude() ? location.getAltitude() : null;
        Float accuracy = location.hasAccuracy() ? location.getAccuracy() : null;
        try {
            signalingClient.send(SignalingMessageBuilder.buildTelemetryMessage(
                    location.getLatitude(),
                    location.getLongitude(),
                    altitude,
                    accuracy,
                    timestamp,
                    "android"
            ));
        } catch (JSONException | IllegalArgumentException e) {
            Log.w(TAG, "Failed to send telemetry", e);
        }
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
        // Activity가 멈출 때 스트리밍 상태라면 명시적으로 중지
        if (streamingState != StreamingState.IDLE) {
             Log.i(TAG, "onStop called while streaming, stopping internal stream.");
             stopStreamingInternal(getString(R.string.camera_stream_status_stopped_on_exit));
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // onStop에서 이미 스트리밍이 중지되었을 가능성이 높지만, 만약을 위해 호출
        // (stopStreamingInternal은 상태가 IDLE이면 빠르게 반환함)
        stopStreamingInternal(null); 

        if (binding != null && binding.pathMap != null) { 
            binding.pathMap.onDetach();
        }
        // cameraPreview.release()는 stopStreamingInternal에서 이미 호출됨

        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
            Log.d(TAG, "EglBase released in onDestroy.");
        }
        // surfaceTextureHelper와 activeCapturer는 stopStreamingInternal에서 해제됨
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
            Log.d(TAG, "Opening camera: " + cameraName + " for label: " + cameraLabel);
        }

        @Override
        public void onFirstFrameAvailable() {
            Log.i(TAG, "****** First frame available from camera: " + cameraLabel + " ******");
        }

        @Override
        public void onCameraClosed() {
            Log.d(TAG, "Camera closed: " + cameraLabel);
        }
    }
}
