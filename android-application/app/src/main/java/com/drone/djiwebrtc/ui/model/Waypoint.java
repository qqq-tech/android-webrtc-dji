package com.drone.djiwebrtc.ui.model;

public class Waypoint {
    private final String name;
    private final double latitude;
    private final double longitude;
    private final double altitude;
    private final boolean visited;

    public Waypoint(String name, double latitude, double longitude, double altitude, boolean visited) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.visited = visited;
    }

    public String getName() {
        return name;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getAltitude() {
        return altitude;
    }

    public boolean isVisited() {
        return visited;
    }

    public Waypoint markVisited() {
        return new Waypoint(name, latitude, longitude, altitude, true);
    }
}
