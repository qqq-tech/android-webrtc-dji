package com.drone.djiwebrtc;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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

import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private BottomSheetBehavior<View> bottomSheetBehavior;
    private RouteOverlayManager routeOverlayManager;
    private FlightPathViewModel flightPathViewModel;
    private WaypointAdapter waypointAdapter;
    private DroneVideoPreview droneVideoPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
