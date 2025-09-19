package com.drone.djiwebrtc.core;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.FlightMode;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.gimbal.GimbalState; // 이미 존재
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.mission.MissionControl;
import dji.sdk.mission.waypoint.WaypointMissionOperator;

import dji.common.mission.waypoint.Waypoint;
import dji.common.mission.waypoint.WaypointMission;
import dji.common.mission.waypoint.WaypointMissionFinishedAction;
import dji.common.mission.waypoint.WaypointMissionFlightPathMode;
import dji.common.mission.waypoint.WaypointMissionGotoWaypointMode;
import dji.common.mission.waypoint.WaypointMissionHeadingMode;

import com.drone.djiwebrtc.network.SocketConnection;

/**
 * Handles commands coming from the ground control station client and pushes telemetry updates
 * back to the signaling backend. The commands leverage DJI's Mobile SDK v4 APIs.
 */
public class GCSCommandHandler {
    private static final String TAG = "GCSCommandHandler";

    private FlightController flightController;
    private Gimbal gimbal;
    private boolean virtualStickInitialized = false;
    private WaypointMissionOperator waypointMissionOperator;
    private GimbalState latestGimbalState; // 최근 짐벌 상태를 저장할 변수

    public GCSCommandHandler() {
        refreshProductHandles();
    }

    // 짐벌 상태 업데이트를 처리하는 콜백 메서드
    private void onGimbalStateUpdate(GimbalState gimbalState) {
        if (gimbalState != null) {
            this.latestGimbalState = gimbalState;
        }
    }

    // 짐벌 객체를 얻거나 telemetry가 시작될 때 호출할 수 있습니다.
    private void startGimbalStateUpdates() {
        Gimbal currentGimbal = getGimbal(); // 기존 짐벌 가져오는 메서드
        if (currentGimbal != null) {
            currentGimbal.setStateCallback(this::onGimbalStateUpdate);
        } else {
            Log.w(TAG, "Gimbal state updates skipped - gimbal unavailable");
        }
    }

    public void startTelemetry() {
        FlightController controller = getFlightController();
        if (controller == null) {
            Log.w(TAG, "Telemetry skipped - flight controller unavailable");
            // flight controller가 없어도 gimbal telemetry는 시도할 수 있도록 return 제거
        } else {
            controller.setStateCallback(this::emitTelemetry);
        }
        // 짐벌 상태 업데이트 시작
        startGimbalStateUpdates();
    }

    public void handleCommand(JSONObject command) throws JSONException {
        if (command == null) {
            emitCommandError("unknown", "Missing command payload", "INVALID_COMMAND");
            return;
        }

        refreshProductHandles();
        String action = command.optString("action");
        switch (action) {
            case "takeoff":
                handleTakeoff(action);
                break;
            case "land":
                handleLanding(action);
                break;
            case "return_home":
                handleGoHome(action);
                break;
            case "cancel_return_home":
                handleCancelGoHome(action);
                break;
            case "virtual_stick":
                handleVirtualStick(action, command);
                break;
            case "gimbal_rotate":
                handleGimbalRotate(action, command);
                break;
            case "flight_path":
                handleFlightPath(action, command);
                break;
            default:
                emitCommandError(action, "Unsupported command", "UNSUPPORTED_ACTION");
                break;
        }
    }

    private void handleTakeoff(String action) {
        FlightController controller = getFlightController();
        if (controller == null) {
            emitCommandError(action, "Flight controller unavailable", "NO_FLIGHT_CONTROLLER");
            return;
        }
        controller.startTakeoff(result -> handleCompletion(action, result));
    }

    private void handleLanding(String action) {
        FlightController controller = getFlightController();
        if (controller == null) {
            emitCommandError(action, "Flight controller unavailable", "NO_FLIGHT_CONTROLLER");
            return;
        }
        controller.startLanding(result -> handleCompletion(action, result));
    }

    private void handleGoHome(String action) {
        FlightController controller = getFlightController();
        if (controller == null) {
            emitCommandError(action, "Flight controller unavailable", "NO_FLIGHT_CONTROLLER");
            return;
        }
        controller.startGoHome(result -> handleCompletion(action, result));
    }

    private void handleCancelGoHome(String action) {
        FlightController controller = getFlightController();
        if (controller == null) {
            emitCommandError(action, "Flight controller unavailable", "NO_FLIGHT_CONTROLLER");
            return;
        }
        controller.cancelGoHome(result -> handleCompletion(action, result));
    }

    private void handleVirtualStick(String action, JSONObject command) throws JSONException {
        FlightController controller = getFlightController();
        if (controller == null) {
            emitCommandError(action, "Flight controller unavailable", "NO_FLIGHT_CONTROLLER");
            return;
        }

        float pitch = (float) command.optDouble("pitch", 0.0);
        float roll = (float) command.optDouble("roll", 0.0);
        float yaw = (float) command.optDouble("yaw", 0.0);
        float throttle = (float) command.optDouble("throttle", 0.0);

        ensureVirtualStickModeEnabled(controller, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError != null) {
                    emitCommandError(action, djiError.getDescription(), "VIRTUAL_STICK_ENABLE_FAILED");
                    return;
                }
                FlightControlData data = new FlightControlData(pitch, roll, yaw, throttle);
                controller.sendVirtualStickFlightControlData(data, result -> handleCompletion(action, result));
            }
        });
    }

    private void handleFlightPath(String action, JSONObject command) throws JSONException {
        WaypointMissionOperator operator = getWaypointMissionOperator();
        if (operator == null) {
            emitCommandError(action, "Waypoint mission operator unavailable", "NO_MISSION_OPERATOR");
            return;
        }

        JSONArray waypointsArray = command.optJSONArray("waypoints");
        if (waypointsArray == null || waypointsArray.length() < 2) {
            emitCommandError(action, "At least two waypoints are required", "INVALID_WAYPOINTS");
            return;
        }

        JSONObject options = command.optJSONObject("options");
        double requestedAltitude = options != null ? options.optDouble("altitude", 30.0) : 30.0;
        float defaultAltitude = (float) clamp(requestedAltitude, 5.0, 500.0);

        WaypointMission.Builder builder = new WaypointMission.Builder()
                .finishedAction(WaypointMissionFinishedAction.NO_ACTION)
                .headingMode(WaypointMissionHeadingMode.AUTO)
                .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
                .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
                .autoFlightSpeed(5.0f)
                .maxFlightSpeed(10.0f)
                .setGimbalPitchRotationEnabled(true);

        for (int i = 0; i < waypointsArray.length(); i++) {
            JSONObject waypointJson = waypointsArray.getJSONObject(i);
            double latitude = waypointJson.optDouble("latitude", Double.NaN);
            double longitude = waypointJson.optDouble("longitude", Double.NaN);
            double altitude = waypointJson.has("altitude")
                    ? waypointJson.optDouble("altitude", defaultAltitude)
                    : defaultAltitude;

            if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
                emitCommandError(action, "Waypoint " + i + " missing coordinates", "INVALID_WAYPOINT");
                return;
            }

            float waypointAltitude = (float) clamp(altitude, 5.0, 500.0);
            Waypoint waypoint = new Waypoint(latitude, longitude, waypointAltitude);
            builder.addWaypoint(waypoint);
        }

        WaypointMission mission = builder.build();

        DJIError loadError = operator.loadMission(mission);
        if (loadError != null) {
            emitCommandError(action, loadError.getDescription(), "MISSION_LOAD_FAILED");
            return;
        }

        operator.uploadMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError uploadError) {
                if (uploadError != null) {
                    emitCommandError(action, uploadError.getDescription(), "MISSION_UPLOAD_FAILED");
                    return;
                }
                operator.startMission(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError startError) {
                        if (startError != null) {
                            emitCommandError(action, startError.getDescription(), "MISSION_START_FAILED");
                        } else {
                            emitCommandAck(action, "ok", null);
                        }
                    }
                });
            }
        });
    }

    private void handleGimbalRotate(String action, JSONObject command) throws JSONException {
        Gimbal gimbal = getGimbal();
        if (gimbal == null) {
            emitCommandError(action, "Gimbal unavailable", "NO_GIMBAL");
            return;
        }

        float pitch = (float) command.optDouble("pitch", 0.0);
        float roll = (float) command.optDouble("roll", 0.0);
        float yaw = (float) command.optDouble("yaw", 0.0);
        int time = command.optInt("duration", 1);

        Rotation rotation = new Rotation.Builder()
                .mode(RotationMode.ABSOLUTE_ANGLE)
                .pitch(pitch)
                .roll(roll)
                .yaw(yaw)
                .time(time)
                .build();

        gimbal.rotate(rotation, result -> handleCompletion(action, result));
    }

    private void ensureVirtualStickModeEnabled(FlightController controller, CommonCallbacks.CompletionCallback callback) {
        if (virtualStickInitialized) {
            callback.onResult(null);
            return;
        }

        controller.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
        controller.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        controller.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        controller.setVerticalControlMode(VerticalControlMode.VELOCITY);

        controller.setVirtualStickModeEnabled(true, result -> {
            if (result == null) {
                virtualStickInitialized = true;
            }
            callback.onResult(result);
        });
    }

    private void handleCompletion(String action, DJIError error) {
        if (error == null) {
            emitCommandAck(action, "ok", null);
        } else {
            emitCommandError(action, error.getDescription(), action.toUpperCase() + "_FAILED");
        }
    }

    private void emitTelemetry(FlightControllerState state) {
        // FlightControllerState가 null이어도 gimbal telemetry는 보낼 수 있도록 수정
        // if (state == null) {
        //     return;
        // }
        try {
            JSONObject payload = new JSONObject();
            payload.put("timestamp", System.currentTimeMillis());

            if (state != null) {
                payload.put("frame_id", state.getFlightTimeInSeconds());
                FlightMode flightMode = state.getFlightMode();
                if (flightMode != null) {
                    payload.put("flight_mode", flightMode.name());
                }
                payload.put("satellites", state.getSatelliteCount());
                payload.put("heading", state.getAircraftHeadDirection());

                LocationCoordinate3D location = state.getAircraftLocation();
                if (location != null) {
                    JSONObject loc = new JSONObject();
                    loc.put("latitude", location.getLatitude());
                    loc.put("longitude", location.getLongitude());
                    loc.put("altitude", location.getAltitude());
                    payload.put("location", loc);
                }

                JSONObject velocity = new JSONObject();
                velocity.put("x", state.getVelocityX());
                velocity.put("y", state.getVelocityY());
                velocity.put("z", state.getVelocityZ());
                payload.put("velocity", velocity);
            }

            // 저장된 최신 짐벌 상태 사용
            if (this.latestGimbalState != null) {
                JSONObject gimbalJson = new JSONObject();
                gimbalJson.put("pitch", this.latestGimbalState.getAttitudeInDegrees().getPitch());
                gimbalJson.put("roll", this.latestGimbalState.getAttitudeInDegrees().getRoll());
                gimbalJson.put("yaw", this.latestGimbalState.getAttitudeInDegrees().getYaw());
                // 필요에 따라 gimbalState.getMode().name() 등 다른 정보 추가
                payload.put("gimbal", gimbalJson);
            }

            // payload에 내용이 있을 때만 emit
            if (payload.length() > 1) { // timestamp는 항상 있으므로 1 초과
                SocketConnection.getInstance().emit("gcs_telemetry", payload);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to emit telemetry", e);
        }
    }

    private void emitCommandAck(String action, String status, JSONObject data) {
        try {
            JSONObject response = new JSONObject();
            response.put("action", action);
            response.put("status", status);
            if (data != null) {
                response.put("data", data);
            }
            SocketConnection.getInstance().emit("gcs_command_ack", response);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to emit command ack", e);
        }
    }

    private void emitCommandError(String action, String description, String code) {
        try {
            JSONObject error = new JSONObject();
            error.put("action", action);
            error.put("error", description);
            error.put("code", code);
            SocketConnection.getInstance().emit("gcs_command_ack", error);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to emit command error", e);
        }
    }

    private void refreshProductHandles() {
        BaseProduct product = DJISDKManager.getInstance().getProduct();
        if (product instanceof Aircraft) {
            Aircraft aircraft = (Aircraft) product;
            flightController = aircraft.getFlightController();
            gimbal = aircraft.getGimbal();
            // waypointMissionOperator는 여기서 null로 초기화하고 getWaypointMissionOperator에서 필요시 생성
            waypointMissionOperator = null; 
        } else {
            flightController = null;
            gimbal = null;
            waypointMissionOperator = null;
        }
    }

    private FlightController getFlightController() {
        if (flightController == null) {
            refreshProductHandles();
        }
        return flightController;
    }

    private Gimbal getGimbal() {
        if (gimbal == null) {
            refreshProductHandles();
        }
        return gimbal;
    }

    private WaypointMissionOperator getWaypointMissionOperator() {
        if (waypointMissionOperator == null) {
            MissionControl missionControl = DJISDKManager.getInstance().getMissionControl();
            if (missionControl != null) {
                waypointMissionOperator = missionControl.getWaypointMissionOperator();
            }
        }
        return waypointMissionOperator;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
