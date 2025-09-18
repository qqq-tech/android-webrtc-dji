package com.drone.djiwebrtc.ui.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.drone.djiwebrtc.ui.model.Waypoint;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FlightPathViewModel extends ViewModel {
    private final MutableLiveData<List<Waypoint>> plannedWaypoints = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<GeoPoint>> traveledPath = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Waypoint> nextWaypoint = new MutableLiveData<>();
    private final MutableLiveData<GeoPoint> currentLocation = new MutableLiveData<>();

    public LiveData<List<Waypoint>> getPlannedWaypoints() {
        return plannedWaypoints;
    }

    public LiveData<List<GeoPoint>> getTraveledPath() {
        return traveledPath;
    }

    public LiveData<Waypoint> getNextWaypoint() {
        return nextWaypoint;
    }

    public LiveData<GeoPoint> getCurrentLocation() {
        return currentLocation;
    }

    public void setPlannedWaypoints(List<Waypoint> waypoints) {
        plannedWaypoints.setValue(waypoints == null ? Collections.emptyList() : new ArrayList<>(waypoints));
    }

    public void setTraveledPath(List<GeoPoint> pathPoints) {
        traveledPath.setValue(pathPoints == null ? Collections.emptyList() : new ArrayList<>(pathPoints));
    }

    public void setNextWaypoint(Waypoint waypoint) {
        nextWaypoint.setValue(waypoint);
    }

    public void setCurrentLocation(GeoPoint location) {
        currentLocation.setValue(location);
    }

    public void seedDemoData() {
        List<Waypoint> demoWaypoints = new ArrayList<>();
        demoWaypoints.add(new Waypoint("WP-1", 37.4220, -122.0841, 40, true));
        demoWaypoints.add(new Waypoint("WP-2", 37.4225, -122.0835, 45, false));
        demoWaypoints.add(new Waypoint("WP-3", 37.4231, -122.0828, 45, false));
        plannedWaypoints.setValue(demoWaypoints);

        List<GeoPoint> traveled = new ArrayList<>();
        traveled.add(new GeoPoint(37.4220, -122.0841));
        traveled.add(new GeoPoint(37.4222, -122.0840));
        traveled.add(new GeoPoint(37.4223, -122.0838));
        traveledPath.setValue(traveled);

        nextWaypoint.setValue(demoWaypoints.size() > 1 ? demoWaypoints.get(1) : null);
        currentLocation.setValue(traveled.get(traveled.size() - 1));
    }
}
