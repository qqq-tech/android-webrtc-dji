package com.drone.djiwebrtc.ui.map;

import android.graphics.Color;

import androidx.core.content.ContextCompat;

import com.drone.djiwebrtc.R;
import com.drone.djiwebrtc.ui.model.Waypoint;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteOverlayManager {
    private final MapView mapView;
    private Polyline plannedPolyline;
    private Polyline traveledPolyline;
    private Marker nextWaypointMarker;
    private Marker currentLocationMarker;
    private double plannedPathDistanceMeters;

    public RouteOverlayManager(MapView mapView) {
        this.mapView = mapView;
    }

    public void initialize() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER);
        mapView.getController().setZoom(17.0);
    }

    public void updatePlannedPath(List<Waypoint> waypoints) {
        List<GeoPoint> geoPoints = new ArrayList<>();
        plannedPathDistanceMeters = 0;

        GeoPoint previousPoint = null;
        for (Waypoint waypoint : waypoints) {
            if (waypoint == null) {
                continue;
            }
            GeoPoint currentPoint = toGeoPoint(waypoint);
            geoPoints.add(currentPoint);
            if (previousPoint != null) {
                plannedPathDistanceMeters += previousPoint.distanceToAsDouble(currentPoint);
            }
            previousPoint = currentPoint;
        }

        if (geoPoints.isEmpty()) {
            clearPlannedPath();
            mapView.invalidate();
            return;
        }

        if (plannedPolyline == null) {
            plannedPolyline = new Polyline();
            plannedPolyline.setColor(Color.parseColor("#0A84FF"));
            plannedPolyline.setWidth(8f);
            mapView.getOverlayManager().add(plannedPolyline);
        }
        plannedPolyline.setPoints(geoPoints);

        if (geoPoints.size() > 1) {
            mapView.zoomToBoundingBox(Polyline.boundingBoxFromGeoPoints(geoPoints), true);
        } else {
            mapView.getController().setCenter(geoPoints.get(0));
        }

        mapView.invalidate();
    }

    public double getPlannedDistanceMeters() {
        return plannedPathDistanceMeters;
    }

    public void updateTraveledPath(List<GeoPoint> pathPoints) {
        List<GeoPoint> points = pathPoints == null ? Collections.emptyList() : pathPoints;
        if (points.isEmpty()) {
            clearTraveledPath();
            mapView.invalidate();
            return;
        }

        if (traveledPolyline == null) {
            traveledPolyline = new Polyline();
            traveledPolyline.setColor(Color.parseColor("#32D74B"));
            traveledPolyline.setWidth(6f);
            mapView.getOverlayManager().add(traveledPolyline);
        }
        traveledPolyline.setPoints(points);
        mapView.invalidate();
    }

    public void updateNextWaypoint(Waypoint waypoint) {
        if (waypoint == null) {
            if (nextWaypointMarker != null) {
                mapView.getOverlayManager().remove(nextWaypointMarker);
                nextWaypointMarker = null;
                mapView.invalidate();
            }
            return;
        }

        if (nextWaypointMarker == null) {
            nextWaypointMarker = new Marker(mapView);
            nextWaypointMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            nextWaypointMarker.setTextLabelBackgroundColor(Color.argb(200, 10, 132, 255));
            mapView.getOverlayManager().add(nextWaypointMarker);
        }
        nextWaypointMarker.setPosition(toGeoPoint(waypoint));
        nextWaypointMarker.setTitle(waypoint.getName());
        nextWaypointMarker.setTextLabelForegroundColor(Color.WHITE);
        nextWaypointMarker.setTextLabelFontSize(14);
        nextWaypointMarker.setTextIcon("NEXT");
        mapView.invalidate();
    }

    public void updateCurrentLocation(GeoPoint location) {
        if (location == null) {
            return;
        }
        if (currentLocationMarker == null) {
            currentLocationMarker = new Marker(mapView);
            currentLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            currentLocationMarker.setIcon(ContextCompat.getDrawable(mapView.getContext(), R.drawable.ic_current_location));
            currentLocationMarker.setTextLabelForegroundColor(Color.parseColor("#FF375F"));
            mapView.getOverlayManager().add(currentLocationMarker);
        }
        currentLocationMarker.setPosition(location);
        currentLocationMarker.setTitle("현재 위치");
        mapView.getController().animateTo(location);
        mapView.invalidate();
    }

    private void clearPlannedPath() {
        if (plannedPolyline != null) {
            mapView.getOverlayManager().remove(plannedPolyline);
            plannedPolyline = null;
        }
    }

    private void clearTraveledPath() {
        if (traveledPolyline != null) {
            mapView.getOverlayManager().remove(traveledPolyline);
            traveledPolyline = null;
        }
    }

    private GeoPoint toGeoPoint(Waypoint waypoint) {
        return new GeoPoint(waypoint.getLatitude(), waypoint.getLongitude(), waypoint.getAltitude());
    }
}
