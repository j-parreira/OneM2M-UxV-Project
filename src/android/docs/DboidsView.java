package com.dji.sdk.duvops.flight;

import static com.dji.sdk.sample.internal.view.MainContent.TAG;
import static java.lang.Math.pow;
import static java.lang.Math.sqrt;

import android.app.Service;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.App;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.utils.ToastUtils;
import com.dji.sdk.sample.internal.utils.VideoFeedView;
import com.dji.sdk.sample.internal.view.PresentableView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;

import dji.common.error.DJIError;
import dji.common.flightcontroller.LEDsSettings;
import dji.common.flightcontroller.simulator.InitializationData;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.model.LocationCoordinate2D;
import dji.common.util.CommonCallbacks;
import dji.keysdk.BatteryKey;
import dji.keysdk.KeyManager;
import dji.keysdk.callback.GetCallback;
import dji.sdk.battery.Battery;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.flightcontroller.FlightController;

import dji.sdk.mission.waypoint.WaypointMissionOperator;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.sdkmanager.LiveStreamManager;
import dji.sdk.sdkmanager.LiveVideoBitRateMode;
import dji.sdk.sdkmanager.LiveVideoResolution;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;

public class DboidsView extends LinearLayout implements PresentableView, View.OnClickListener {

    private Button connectws;
    private Button startRTMP;
    private Button startUDP;
    private Button startSimulator;
    private Button abort;
    private EditText hostname;
    private WebSocket ws;
    private TextView messageField;
    private TextView statusField;
    private float pitch;
    private float roll;
    private float yaw;
    private float throttle;
    private Timer sendVirtualStickDataTimer;
    private SendVirtualStickDataTask sendVirtualStickDataTask;
    public JSONObject receivedCommand = new JSONObject();
    FlightController flightController = null;
    private Battery mBattery;
    private VideoFeedView primaryVideoFeedView;
    private double latitude = 0;
    private double longitude = 0;
    private WaypointMissionOperator waypointMissionOperator = null;
    private BatteryKey BbatteryKey = BatteryKey.create(BatteryKey.CHARGE_REMAINING_IN_PERCENT);
    private String batteryLevel = "0";
    private float batteryTemp = 0;
    public boolean isTraveling = false;
    public String serialNumber = "-1";
    public String model = "";
    private boolean ledsStatus = false;
    private boolean wait = false;

    private VideoFeeder.VideoDataListener videoDataListener = null;

    public DboidsView(@NonNull Context context) {
        super(context);
        initUI(context);
        getSerialNumber();
    }

    private void initUI(Context context) {
        setClickable(true);
        setOrientation(HORIZONTAL);
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_dboids, this, true);

        connectws = (Button) findViewById(R.id.connectws);
        connectws.setOnClickListener(this);

        startRTMP = (Button) findViewById(R.id.startRTMP);
        startRTMP.setOnClickListener(this);

        startUDP = (Button) findViewById(R.id.startUDP);
        startUDP.setOnClickListener(this);

        startSimulator = (Button) findViewById(R.id.startSimulator);
        startSimulator.setOnClickListener(this);

        abort = (Button) findViewById(R.id.abort);
        abort.setOnClickListener(this);

        messageField = (TextView) findViewById(R.id.messageField);
        statusField = (TextView) findViewById(R.id.statusField);

        flightController = ModuleVerificationUtil.getFlightController();

        flightController.setVerticalControlMode(VerticalControlMode.VELOCITY);
        flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);

        primaryVideoFeedView = (VideoFeedView) findViewById(R.id.dboids_primary_videofeed);
        primaryVideoFeedView.registerLiveVideo(VideoFeeder.getInstance().getPrimaryVideoFeed(), true);

        hostname = (EditText) findViewById(R.id.websocketUrl);
        hostname.setText("192.168.88.10");

        Aircraft aircraft = (Aircraft) DJISDKManager.getInstance().getProduct();
        if (aircraft != null) {
            mBattery = aircraft.getBattery();
        }

    }

    @Override
    public int getDescription() {
        return 0;
    }

    @NonNull
    @Override
    public String getHint() {
        return null;
    }

    private void connectWS() {
        if (ws != null) {
            ws.cancel();
            ws = null;
        }
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url("ws://" + hostname.getText().toString() + ":8080").addHeader("dboidsID", serialNumber).build();
        SocketListener socketListener = new SocketListener(this);
        ws = client.newWebSocket(request, socketListener);
    }

    private Void takeoff(FlightController flightController) {
        wait = true;
        flightController.startTakeoff(djiError -> {
            sendResultACK(djiError);
            wait = false;
        });
        return null;
    }

    private void startLanding(FlightController flightController) {
        flightController.startLanding(djiError -> {
            //DialogUtils.showDialogBasedOnError(getContext(), djiError);
            sendResultACK(djiError);
        });
    }

    private void motors(FlightController flightController) {
        if (receivedCommand.has("state")) {
            try {
                if (receivedCommand.getBoolean("state")) {
                    turnOnMotors(flightController);
                } else {
                    turnOffMotors(flightController);
                }
            } catch (JSONException e) {
                Log.d("DEBUG", "ERROR: setting motors state" + e.getMessage());
            }
        }
    }

    private void turnOnMotors(FlightController flightController) {
        flightController.turnOnMotors(djiError -> {
            sendResultACK(djiError);
        });
    }

    private void turnOffMotors(FlightController flightController) {
        flightController.turnOffMotors(djiError -> {
            sendResultACK(djiError);
        });
    }

    public void setMessage(String message) {
        messageField.setText(message);

        try {
            JSONObject obj = new JSONObject(message);
            if (obj.has("command")) {
                receivedCommand = obj;
                handleMessage(obj.getString("command"));
            }

        } catch (JSONException e) {
            Log.d("DEBUG", "Error Parsing JSON");
        }

    }

    public void setStatus(String message) {
        statusField.setText(message);

    }

    public void getSerialNumber() {
        Aircraft aircraft = (Aircraft) App.getProductInstance();
        if (null != aircraft.getFlightController()) {
            flightController = aircraft.getFlightController();
            flightController.getSerialNumber(new CommonCallbacks.CompletionCallbackWith<String>() {
                @Override
                public void onSuccess(String s) {
                    serialNumber = s;
                    model = DJISDKManager.getInstance().getProduct().getModel().getDisplayName();
                    Log.d("DEBUG", "serialNumber: " + s);
                    connectWS();
                }

                @Override
                public void onFailure(DJIError djiError) {
                    ToastUtils.setResultToToast("getSerialNumber failed: " + djiError.getDescription());
                }
            });
        }


    }

    public void sendResultACK(DJIError djiError) {
        JSONObject obj = new JSONObject();
        if (djiError != null) {
            Log.d("DEBUG", "ACK: ERROR: " + djiError.getDescription() + " " + djiError.getErrorCode());
        } else {
            Log.d("DEBUG", "ACK: OK");
        }
    }

    private void startSimulator() {
        if (null != getFlightController()) {
            flightController.getSimulator().start(InitializationData.createInstance(new LocationCoordinate2D(39.933219, -8.892509), 10, 10), djiError -> ToastUtils.setResultToToast(djiError != null ? djiError.getDescription() : "Simulator started"));
        }
    }

    private FlightController getFlightController() {
        if (null == flightController) {
            if (null != App.getAircraftInstance()) {
                return App.getAircraftInstance().getFlightController();
            }
            ToastUtils.setResultToToast("Product is disconnected!");
        }
        return flightController;
    }


    public void handleMessage(String message) {
        switch (message) {
            case "takeoff":
                takeoff(flightController);
                break;
            case "land":
                startLanding(flightController);
                break;
            case "motors":
                motors(flightController);
                break;
            case "virtualSticks":
                virtualSticks(flightController);
                break;
            case "virtualSticksInput":
                virtualSticksInput(flightController);
                break;
            case "startRTMP":
                startRTMP();
                break;
            case "gpsInput":
                gpsInput();
                break;
            case "perform360":
                perform360(flightController);
                break;
            case "gpsInput360Mapping":
                gpsInput360Mapping();
                break;
            case "startGoHome":
                startGoHome(flightController);
                break;
            case "identify":
                identify();
                break;
            case "pauseMission":
                pauseMission();
                break;
            case "stopMission":
                stopMission();
                break;
            case "startMission":
                startMission();
                break;
            case "debug":
                break;
            default:
                Log.d("DEBUG", "ERROR: unknown command");
                break;
        }
    }

    private void identify() {
        boolean status = false;
        if (receivedCommand.has("state")) {
            try {
                status = receivedCommand.getBoolean("state");
            } catch (JSONException e) {
                Log.d("DEBUG", "ERROR: setting identify state" + e.getMessage());
            }
        }

        LEDsSettings.Builder ledsSettings = new LEDsSettings.Builder();
        ledsSettings.frontLEDsOn(status);
        ledsSettings.beaconsOn(status);
        ledsSettings.rearLEDsOn(status);
        ledsSettings.statusIndicatorOn(status);

        flightController.setLEDsEnabledSettings(ledsSettings.build(), error -> Log.d("DEBUG", "LEDs set: " + (error == null ? "Success" : error.getDescription())));
    }

    /**
     * Starts a waypoint mission based on the received command.
     * {
     * "command": "startMission",
     * "startAction": "takeoff",
     * "endAction": "goHome",
     * "repeat": 10,
     * "altitude": 45,
     * "path": [
     * {
     * "lat": 39.932531209102876,
     * "lng": -8.89345900226266
     * },
     * {
     * "lat": 39.93367474928618,
     * "lng": -8.892815267417603
     * },
     * {
     * "lat": 39.93230908037657,
     * "lng": -8.893072761355853
     * },
     * {
     * "lat": 39.93384751268897,
     * "lng": -8.892214448227634
     * },
     * {
     * "lat": 39.93209517795901,
     * "lng": -8.892761622847104
     * },
     * {
     * "lat": 39.93411077036825,
     * "lng": -8.891538526640687
     * }
     * ],
     * "status": "RUNNING"
     * }
     */
    private enum MissionState {
        RUNNING,
        PAUSED,
        STOPPED
    }

    private final Object lock = new Object();
    private MissionState missionState = MissionState.RUNNING;
    private Thread missionThread;

    private void startMission() {
        Log.d("MISSION", "Received command: " + receivedCommand.toString());

        synchronized (lock) {
            if (missionState == MissionState.PAUSED) {
                resumeMission();
                return;
            }

            if (missionThread != null && missionThread.isAlive()) {
                Log.d("MISSION", "Já existe uma missão em execução.");
                return;
            }

            missionState = MissionState.RUNNING;
        }

        String startAction, endAction;
        int repeat;
        float altitude;
        JSONArray mission;

        try {
            startAction = receivedCommand.has("startAction") ? receivedCommand.getString("startAction") : null;
            endAction = receivedCommand.has("endAction") ? receivedCommand.getString("endAction") : null;
            repeat = receivedCommand.has("repeat") ? receivedCommand.getInt("repeat") : 0;
            altitude = receivedCommand.has("altitude") ? (float) receivedCommand.getDouble("altitude") : 0f;
            mission = receivedCommand.has("path") ? receivedCommand.getJSONArray("path") : null;
        } catch (JSONException e) {
            Log.d("MISSION", "Erro ao obter parâmetros do comando: " + e.getMessage());
            stopMission();
            return;
        }

        OnCompletionCallback callback = () -> {
            missionThread = new Thread(() -> {
                try {
                    isTraveling = true;

                    flightController.setVirtualStickModeEnabled(true, null);
                    flightController.setYawControlMode(YawControlMode.ANGLE);
                    flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
                    flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);

                    mission_loop:
                    for (int r = 0; r <= repeat; r++) {
                        for (int i = 0; i < mission.length(); i++) {
                            synchronized (lock) {
                                while (missionState == MissionState.PAUSED) {
                                    try {
                                        lock.wait();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        Log.e("MISSION", "Thread interrompida em pausa.");
                                        break mission_loop;
                                    }
                                }

                                if (missionState == MissionState.STOPPED) {
                                    Log.d("MISSION", "Missão interrompida.");
                                    break mission_loop;
                                }
                            }

                            JSONObject waypoint = mission.getJSONObject(i);
                            double lat = waypoint.getDouble("lat");
                            double lng = waypoint.getDouble("lng");

                            Log.d("MISSION", "Waypoint " + (i + 1) + ": " + lat + ", " + lng);

                            int result = gpsInputTo(lat, lng);

                            if (result == 0) {
                                Log.d("MISSION", "Drone está em deslocamento ou waypoint muito próximo.");
                                continue;
                            } else if (result == -1) {
                                Log.d("MISSION", "Falha ao alcançar o waypoint " + (i + 1));
                                break;
                            }
                        }
                    }

                    try {
                        flightController.setVirtualStickModeEnabled(false, null);
                        flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                    } catch (Exception e) {
                        Log.e("MISSION", "Erro ao desativar virtual stick: " + e.getMessage());
                    }

                    if (flightController.getState().isFlying()) {
                        if (endAction != null && endAction.equals("goHome")) {
                            startGoHome(flightController);
                        } else if (endAction != null && endAction.equals("land")) {
                            startLanding(flightController);
                        }
                    }

                    isTraveling = false;
                    stopMission();
                    Log.d("MISSION", "Missão concluída.");

                } catch (JSONException e) {
                    Log.e("MISSION", "Erro JSON: " + e.getMessage());
                } catch (Exception e) {
                    Log.e("MISSION", "Erro inesperado: " + e.getMessage());
                    Thread.currentThread().interrupt();
                } finally {
                    isTraveling = false;
                    missionState = MissionState.STOPPED;
                }
            });

            missionThread.start();
        };

        if (startAction !=null && startAction.equals("takeoff") && !flightController.getState().isFlying()) {
            takeOffTo(altitude, callback);
        } else {
            callback.run();
        }
    }

    /**
     * Pauses a waypoint mission based on the received command.
     * {
     * "command": "pauseMission"
     * }
     */
    public void pauseMission() {
        synchronized (lock) {
            if (missionState == MissionState.RUNNING) {
                missionState = MissionState.PAUSED;

                flightController.setVirtualStickModeEnabled(false, null);
                flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);

                Log.d("MISSION", "Missão pausada.");
            }
        }
    }

    private void resumeMission() {
        synchronized (lock) {
            if (missionState == MissionState.PAUSED) {
                missionState = MissionState.RUNNING;

                flightController.setVirtualStickModeEnabled(true, null);
                flightController.setYawControlMode(YawControlMode.ANGLE);
                flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
                flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);

                lock.notifyAll();

                Log.d("MISSION", "Missão retomada.");
            }
        }
    }

    /**
     * Stops a waypoint mission and cleans up resources.
     * {
     * "command": "stopMission"
     * }
     */
    public void stopMission() {
        synchronized (lock) {
            missionState = MissionState.STOPPED;

            flightController.setVirtualStickModeEnabled(false, null);
            flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);

            isTraveling = false;
            lock.notifyAll();

            if (missionThread != null && missionThread.isAlive()) {
                missionThread.interrupt();
                missionThread = null;
            }

            Log.d("MISSION", "Missão parada.");
        }
    }

    private int gpsInputTo(double targetLatitude, double targetLongitude) {
        if (!flightController.getState().isFlying()) {
            Log.d("DEBUG", "Drone is not flying");
            return 0;
        }

        float currentLatitude = (float) flightController.getState().getAircraftLocation().getLatitude();
        float currentLongitude = (float) flightController.getState().getAircraftLocation().getLongitude();

        if (Double.isNaN(currentLatitude) || Double.isNaN(currentLongitude)) {
            Log.d("DEBUG", "Current GPS coordinates are invalid");
            return -1;
        }

        if (measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) > 3) {
            Log.d("MISSION", "Starting waypoint navigation");

            PIDController pidController = new PIDController(0.2, 0.0001, 0.2);

            while (measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) > 2) {
                double velocity = calculateVelocity(flightController);
                float targetYaw = getTargetYaw((float) targetLatitude, (float) targetLongitude, currentLatitude, currentLongitude);
                currentLatitude = (float) flightController.getState().getAircraftLocation().getLatitude();
                currentLongitude = (float) flightController.getState().getAircraftLocation().getLongitude();

                pitch = (float) pidController.calculateThrottle(measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude), velocity);

                flightController.sendVirtualStickFlightControlData(
                        new FlightControlData(0, pitch, targetYaw, 0), null
                );

                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // Stop movement and disable virtual sticks
            flightController.sendVirtualStickFlightControlData(
                    new FlightControlData(0, 0, getTargetYaw((float) targetLatitude, (float) targetLongitude, currentLatitude, currentLongitude), 0), null
            );

            Log.d("MISSION", "Arrived to target");
            return 1;
        }

        Log.d("MISSION", "Already traveling or distance too small");
        return 0;
    }

    private void gpsInput() {

        if (!flightController.getState().isFlying()) {
            Log.d("DEBUG", "Drone is not Not flying");
        }
        float targetLatitude = 0;
        try {
            targetLatitude = (float) receivedCommand.getDouble("lat");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        float targetLongitude = 0;
        try {
            targetLongitude = (float) receivedCommand.getDouble("lng");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        float currentLatitude = (float) flightController.getState().getAircraftLocation().getLatitude();
        float currentLongitude = (float) flightController.getState().getAircraftLocation().getLongitude();

        //If the drone is not travelling and the distance is less than 5km
        if (receivedCommand.has("lat") && receivedCommand.has("lng") && !isTraveling && measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) < 5000) {
            try {
                targetLatitude = (float) receivedCommand.getDouble("lat");
                targetLongitude = (float) receivedCommand.getDouble("lng");
                currentLatitude = (float) flightController.getState().getAircraftLocation().getLatitude();
                currentLongitude = (float) flightController.getState().getAircraftLocation().getLongitude();

                if (measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) > 3 && !isTraveling) {

                    isTraveling = true;
                    Log.d("DEBUG", "starting traveling");

                    flightController.setVirtualStickModeEnabled(true, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Log.d("DEBUG", "Error setting virtual stick mode: " + djiError.getDescription());
                            } else {
                                Log.d("DEBUG", "Virtual stick mode set successfully");
                            }
                        }
                    });

                    if (Double.isNaN(flightController.getState().getAircraftLocation().getLatitude())) {
                        currentLatitude = 0;
                    }
                    if (Double.isNaN(flightController.getState().getAircraftLocation().getLongitude())) {
                        currentLongitude = 0;
                    }

                    //Currently not being used but needs optimization
                    float targetYaw = getTargetYaw(targetLatitude, targetLongitude, currentLatitude, currentLongitude);

                    flightController.setYawControlMode(YawControlMode.ANGLE);
                    flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
                    flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);

                    flightController.setVirtualStickModeEnabled(true, null);

                    FlightControlData flightControlData = new FlightControlData(0, 1.0f, targetYaw, 0);

                    flightController.sendVirtualStickFlightControlData(flightControlData, null);

                    PIDController pidController = new PIDController(0.2, 0.0001, 0.2);

                    while (measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) > 2) {
                        double velocity = calculateVelocity(flightController);

                        targetYaw = getTargetYaw(targetLatitude, targetLongitude, currentLatitude, currentLongitude);

                        //Log.d("DEBUG","Distance: " + measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) + "  " + targetLatitude + targetLongitude + "Velocity: " + velocity + "  " + "hdg: " + targetYaw + "  " + "target: " + targetLatitude + targetLongitude);

                        currentLatitude = (float) flightController.getState().getAircraftLocation().getLatitude();
                        currentLongitude = (float) flightController.getState().getAircraftLocation().getLongitude();

                        pitch = (float) pidController.calculateThrottle(measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude), velocity);
                        //Log.d("DEBUG","PID Pitch: " + pitch);

                        FlightControlData flightControlDataUpdate = new FlightControlData(0, pitch, targetYaw, 0);

                        // Send updated flight control data
                        flightController.sendVirtualStickFlightControlData(flightControlDataUpdate, null);

                        // Delay the loop for a short period to avoid excessive updates
                        try {
                            Thread.sleep(200);  // Delay in milliseconds (adjust as needed)
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    //SET AXIS TO 0
                    FlightControlData flightControlDataUpdate = new FlightControlData(0, 0, getTargetYaw(targetLatitude, targetLongitude, currentLatitude, currentLongitude), 0);
                    flightController.sendVirtualStickFlightControlData(flightControlDataUpdate, null);
                    flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                    Log.d("DEBUG", "Arrived to the destination");

                    flightController.setVirtualStickModeEnabled(false, null);
                    isTraveling = false;

                } else {
                    Log.d("DEBUG", "Already traveling");
                }
            } catch (JSONException e) {
                Log.d("DEBUG", "ERROR: setting gpsInput" + e.getMessage());
            }
        } else {
            Log.d("DEBUG", "Already traveling 1º if");
        }
    }

    private void gpsInput360Mapping() {

        if (!flightController.getState().isFlying()) {
            Log.d("DEBUG", "Drone is not Not flying");
            return;
        }
        float targetLatitude = 0;
        try {
            targetLatitude = (float) receivedCommand.getDouble("lat");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        float targetLongitude = 0;
        try {
            targetLongitude = (float) receivedCommand.getDouble("lng");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        float currentLatitude = (float) flightController.getState().getAircraftLocation().getLatitude();
        float currentLongitude = (float) flightController.getState().getAircraftLocation().getLongitude();

        //If the drone is not travelling and the distance is less than 5km
        if (receivedCommand.has("lat") && receivedCommand.has("lng") && !isTraveling && measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) < 5000) {
            try {
                targetLatitude = (float) receivedCommand.getDouble("lat");
                targetLongitude = (float) receivedCommand.getDouble("lng");
                currentLatitude = (float) flightController.getState().getAircraftLocation().getLatitude();
                currentLongitude = (float) flightController.getState().getAircraftLocation().getLongitude();

                if (measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) > 3 && !isTraveling) {

                    isTraveling = true;
                    Log.d("DEBUG", "starting traveling");

                    flightController.setVirtualStickModeEnabled(true, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Log.d("DEBUG", "Error setting virtual stick mode: " + djiError.getDescription());
                            } else {
                                Log.d("DEBUG", "Virtual stick mode set successfully");
                            }
                        }
                    });

                    if (Double.isNaN(flightController.getState().getAircraftLocation().getLatitude())) {
                        currentLatitude = 0;
                    }
                    if (Double.isNaN(flightController.getState().getAircraftLocation().getLongitude())) {
                        currentLongitude = 0;
                    }

                    //Currently not being used but needs optimization
                    float targetYaw = getTargetYaw(targetLatitude, targetLongitude, currentLatitude, currentLongitude);

                    flightController.setYawControlMode(YawControlMode.ANGLE);
                    flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
                    flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);

                    flightController.setVirtualStickModeEnabled(true, null);

                    FlightControlData flightControlData = new FlightControlData(0, 1.0f, targetYaw, 0);

                    flightController.sendVirtualStickFlightControlData(flightControlData, null);

                    PIDController pidController = new PIDController(0.2, 0.0001, 0.2);

                    while (measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) > 2) {
                        double velocity = calculateVelocity(flightController);

                        targetYaw = getTargetYaw(targetLatitude, targetLongitude, currentLatitude, currentLongitude);

                        //Log.d("DEBUG","Distance: " + measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude) + "  " + targetLatitude + targetLongitude + "Velocity: " + velocity + "  " + "hdg: " + targetYaw + "  " + "target: " + targetLatitude + targetLongitude);

                        currentLatitude = (float) flightController.getState().getAircraftLocation().getLatitude();
                        currentLongitude = (float) flightController.getState().getAircraftLocation().getLongitude();

                        pitch = (float) pidController.calculateThrottle(measure(targetLatitude, targetLongitude, currentLatitude, currentLongitude), velocity);
                        //Log.d("DEBUG","PID Pitch: " + pitch);

                        FlightControlData flightControlDataUpdate = new FlightControlData(0, pitch, targetYaw, 0);

                        // Send updated flight control data
                        flightController.sendVirtualStickFlightControlData(flightControlDataUpdate, null);

                        // Delay the loop for a short period to avoid excessive updates
                        try {
                            Thread.sleep(200);  // Delay in milliseconds (adjust as needed)
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    //SET AXIS TO 0
                    FlightControlData flightControlDataUpdate = new FlightControlData(0, 0, getTargetYaw(targetLatitude, targetLongitude, currentLatitude, currentLongitude), 0);
                    flightController.sendVirtualStickFlightControlData(flightControlDataUpdate, null);
                    flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                    Log.d("DEBUG", "Arrived to the destination");

                    flightController.setVirtualStickModeEnabled(false, null);
                    perform360(flightController);
                    //isTraveling = false;
                } else {
                    Log.d("DEBUG", "Already traveling");
                }
            } catch (JSONException e) {
                Log.d("DEBUG", "ERROR: setting gpsInput" + e.getMessage());
            }
        } else {
            Log.d("DEBUG", "Already traveling 1º if");
        }
    }

    public float getTargetYaw(float targetLatitude, float targetLongitude, float currentLatitude, float currentLongitude) {
        float targetYaw = (float) Math.toDegrees(Math.atan2(targetLongitude - currentLongitude, targetLatitude - currentLatitude));
        return targetYaw;
    }

    private double measure(double lat1, double lon1, double lat2, double lon2) {
        // Radius of earth in KM
        double R = 6378.137;

        double dLat = (lat2 * Math.PI / 180) - (lat1 * Math.PI / 180);
        double dLon = (lon2 * Math.PI / 180) - (lon1 * Math.PI / 180);

        double a = (Math.sin(dLat / 2) * Math.sin(dLat / 2)) + (Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon / 2) * Math.sin(dLon / 2));

        double c = 2 * Math.atan2(sqrt(a), sqrt(1 - a));

        double d = R * c;

        // meters
        return d * 1000;
    }

    private void virtualSticks(FlightController flightController) {
        if (receivedCommand.has("state")) {
            try {
                if (receivedCommand.getBoolean("state")) {
                    enableVirtualSticks(flightController);
                } else {
                    disableVirtualSticks(flightController);
                }
            } catch (JSONException e) {
                Log.d("DEBUG", "ERROR: setting virtual sticks state" + e.getMessage());
            }
        }
    }

    private void virtualSticksInput(FlightController flightController) {

        if (receivedCommand.has("pitch")) {
            try {
                pitch = (float) receivedCommand.getDouble("pitch");
                if (Math.abs(pitch) < 0.02) {
                    pitch = 0;
                }
                pitch = pitch * 10;

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if (receivedCommand.has("roll")) {
            try {
                roll = (float) receivedCommand.getDouble("roll");
                if (Math.abs(roll) < 0.02) {
                    roll = 0;
                }

                roll = roll * 10;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if (receivedCommand.has("yaw")) {
            try {
                yaw = (float) receivedCommand.getDouble("yaw");
                if (Math.abs(yaw) < 0.02) {
                    yaw = 0;
                }
                yaw = yaw * 20;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if (receivedCommand.has("throttle")) {
            try {
                throttle = (float) receivedCommand.getDouble("throttle");
                if (Math.abs(throttle) < 0.02) {
                    throttle = 0;
                }
                throttle = throttle * 4;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if (null == sendVirtualStickDataTimer) {
            sendVirtualStickDataTask = new SendVirtualStickDataTask();
            sendVirtualStickDataTimer = new Timer();
            sendVirtualStickDataTimer.schedule(sendVirtualStickDataTask, 0, 200);
        } else {
            sendVirtualStickDataTask.run();
        }
    }

    private void disableVirtualSticks(FlightController flightController) {
        if (sendVirtualStickDataTimer != null) {
            sendVirtualStickDataTimer.cancel(); //O Inicial
        }
        //sendVirtualStickDataTimer.purge();
        //sendVirtualStickDataTask.cancel();
        flightController.setVirtualStickModeEnabled(false, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                Log.d("DEBUG", "Virtual Sticks disabled");
                sendResultACK(djiError);
            }
        });
    }

    private void enableVirtualSticks(FlightController flightController) {
        flightController.setVirtualStickModeEnabled(true, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                flightController.setVirtualStickAdvancedModeEnabled(true);
                Log.d("DEBUG", "Virtual Sticks enabled");
                sendResultACK(djiError);
            }
        });
    }

    private void getBatteryPercent() {
        KeyManager.getInstance().getValue(BbatteryKey, new GetCallback() {
            @Override
            public void onSuccess(final @NonNull Object o) {
                if (o instanceof Integer) {
                    batteryLevel = o.toString();
                }
            }

            @Override
            public void onFailure(@NonNull DJIError djiError) {
//                batteryLevel = "-1";
            }
        });

    }

    private void getBatteryTemp() {
        Aircraft aircraft = (Aircraft) App.getProductInstance();
        if (aircraft != null && aircraft.getBattery() != null) {
            aircraft.getBattery().setStateCallback(batteryState -> batteryTemp = batteryState.getTemperature());
        }
    }

    @Override
    public void onClick(View v) {
        System.out.println(v);
        switch (v.getId()) {

            case R.id.connectws:
                connectWS();
                break;
            case R.id.startSimulator:
                startSimulator();
                break;
            case R.id.abort:
                abort();
                break;
            case R.id.startRTMP:
                startRTMP();
                break;
            default:
                break;
        }
    }

    private void abort() {
        System.exit(0);
    }

    public void startRTMP() {
        if (!isLiveStreamManagerOn()) {
            Log.d("DEBUG", "startLive: LiveStreamManager not on");
        }
        LiveStreamManager streamManager = DJISDKManager.getInstance().getLiveStreamManager();
        if (streamManager.isStreaming()) {
            streamManager.stopStream();
        }
        new Thread(() -> {
            streamManager.setLiveUrl("rtmp://" + hostname.getText().toString() + ":1935/" + serialNumber);
            streamManager.setAudioStreamingEnabled(false);
            streamManager.setLiveVideoResolution(LiveVideoResolution.VIDEO_RESOLUTION_1920_1080);
            streamManager.setLiveVideoBitRateMode(LiveVideoBitRateMode.AUTO);
            streamManager.setLiveVideoBitRate(1.5f * 1024);
            streamManager.setStartTime();
            int result = streamManager.startStream();

            Log.d("DEBUG", "startLive:" + result);
            Log.d("DEBUG", "video encoding enabled: " + DJISDKManager.getInstance().getLiveStreamManager().isVideoEncodingEnabled());
        }).start();
    }

    private boolean isLiveStreamManagerOn() {
        if (DJISDKManager.getInstance().getLiveStreamManager() == null) {
            Log.d("DEBUG", "startLive: LiveStreamManager not on");
            return false;
        }
        return true;
    }

    public void sendStatus() {
        try {
            Thread.sleep(250);
            getBatteryPercent();
            getBatteryTemp();

            latitude = flightController.getState().getAircraftLocation().getLatitude();
            longitude = flightController.getState().getAircraftLocation().getLongitude();

            if (Double.isNaN(flightController.getState().getAircraftLocation().getLatitude())) {
                latitude = 0;
            }
            if (Double.isNaN(flightController.getState().getAircraftLocation().getLongitude())) {
                longitude = 0;
            }

            JSONObject homeLocation = new JSONObject();
            if (flightController.getState().isHomeLocationSet()) {
                try {
                    homeLocation.put("lat", flightController.getState().getHomeLocation().getLatitude());
                    homeLocation.put("lng", flightController.getState().getHomeLocation().getLongitude());
                } catch (JSONException e) {
                    Log.d("DEBUG", "Error creating homeLocation JSON: " + e.getMessage());
                }
            } else {
                try {
                    homeLocation.put("lat", 0);
                    homeLocation.put("lng", 0);
                } catch (JSONException e) {
                    Log.d("DEBUG", "Error creating default homeLocation JSON: " + e.getMessage());
                }
            }

            flightController.getLEDsEnabledSettings(new CommonCallbacks.CompletionCallbackWith<LEDsSettings>() {
                @Override
                public void onSuccess(LEDsSettings leDsSettings) {
                    ledsStatus = leDsSettings.areBeaconsOn() && leDsSettings.areFrontLEDsOn() &&
                            leDsSettings.areRearLEDsOn() && leDsSettings.isStatusIndicatorOn();
                }

                @Override
                public void onFailure(DJIError djiError) {

                }
            });


            JSONObject status = new JSONObject();
            try {
                status.put("lat", latitude);
                status.put("lng", longitude);
                status.put("alt", flightController.getState().getAircraftLocation().getAltitude());
                status.put("velX", flightController.getState().getVelocityX());
                status.put("velY", flightController.getState().getVelocityY());
                status.put("velZ", flightController.getState().getVelocityZ());
                status.put("batLvl", Integer.parseInt(batteryLevel));
                status.put("batTemperature", batteryTemp);
                status.put("hdg", flightController.getCompass().getHeading());
                status.put("satCount", flightController.getState().getSatelliteCount());
                status.put("rft", flightController.getState().getGoHomeAssessment().getRemainingFlightTime());
                status.put("isTraveling", isTraveling);
                status.put("isFlying", flightController.getState().isFlying());
                status.put("model", model);
                status.put("online", Integer.parseInt(batteryLevel) > 0);
                status.put("isGoingHome", flightController.getState().isGoingHome());
                status.put("isHomeLocationSet", flightController.getState().isHomeLocationSet());
                status.put("homeLocation", homeLocation);
                status.put("areMotorsOn", flightController.getState().areMotorsOn());
                status.put("areLightsOn", ledsStatus);

                if (ws != null) {
                    ws.send(status.toString());
                } else {
                    Log.d("DEBUG", "WebSocket is null, cannot send status");
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void startGoHome(FlightController flightController) {
        if (flightController.getState().isFlying()) {
            int altitude = (int) flightController.getState().getAircraftLocation().getAltitude();
            flightController.setGoHomeHeightInMeters(altitude > 20 ? altitude : 80, djiError -> {
                if (djiError != null) {
                    Log.d("DEBUG", "ERROR setting Go Home height: " + djiError.getDescription() + " " + djiError.getErrorCode());
                } else {
                    flightController.startGoHome(djiErrorGoHome -> {
                        if (djiErrorGoHome != null) {
                            Log.d("DEBUG", "ERROR: " + djiErrorGoHome.getDescription() + " " + djiErrorGoHome.getErrorCode());
                        } else {
                            Log.d("DEBUG", "Go Home");
                        }
                    });
                }
            });
        } else {
            Log.d("DEBUG", "Drone is not flying, cannot start Go Home");
        }
    }

    private class SendVirtualStickDataTask extends TimerTask {
        @Override
        public void run() {
            if (flightController != null) {
                flightController.sendVirtualStickFlightControlData(new FlightControlData(roll, pitch, yaw, throttle), djiError -> {
                    if (djiError != null) {
                        Log.d("DEBUG", "ERROR: " + djiError.getDescription() + " " + djiError.getErrorCode());
                    }

                    Log.d("DEBUG", "roll: " + flightController.getState().getAttitude().roll + "pitch: " + flightController.getState().getAttitude().pitch);
                    //TODO - For debugging reasons because there is no controller to send the stop command
//                        throttle = 0;
//                        yaw = 0;
//                        roll = 0;
//                        pitch = 0;

                });
            }
        }
    }

    public static double calculateVelocity(FlightController flightController) {
        double velocity = sqrt(pow(flightController.getState().getVelocityX(), 2) + pow(flightController.getState().getVelocityY(), 2) + pow(flightController.getState().getVelocityZ(), 2));
        return velocity;
    }

    public void perform360(FlightController flightController) {
        Log.d("DEBUG", "performing 360º rotation");
        if (flightController == null) {
            System.out.println("Flight Controller is not initialized.");
            return;
        }

        // Set the yaw control mode to AngularVelocity mode for smooth rotation
        flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);


        // Define the yaw rotation speed (positive value for clockwise, negative for counterclockwise)
        float yawSpeed = 30.0f; // Adjust speed as needed

        float currentHeadingBefore360 = flightController.getCompass().getHeading();

        // Create a loop to perform a full 360-degree rotation
        try {
            flightController.setVirtualStickModeEnabled(true, null);
            float totalRotation = 0;
            while (totalRotation < 360) {
                Log.d("DEBUG", "totalRotation: " + totalRotation);
                // Perform rotation
                flightController.sendVirtualStickFlightControlData(
                        new FlightControlData(0, 0, yawSpeed, 0),
                        new CommonCallbacks.CompletionCallback() {
                            @Override
                            public void onResult(DJIError djiError) {
                                if (djiError != null) {
                                    System.out.println("Yaw control error: " + djiError.getDescription());
                                }
                            }
                        }
                );

                // Wait for a small duration before updating the rotation (adjust as necessary)
                Thread.sleep(100);

                // Update the total rotation (angle = speed * time)
                totalRotation += yawSpeed * (100 / 1000.0); // time in seconds
            }

            // Stop the rotation
            flightController.sendVirtualStickFlightControlData(
                    new FlightControlData(0, 0, 0, 0),
                    new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                System.out.println("Stop rotation error: " + djiError.getDescription());
                            }
                        }
                    }
            );

            flightController.setYawControlMode(YawControlMode.ANGLE);

            FlightControlData flightControlData = new FlightControlData(0, 0, currentHeadingBefore360, 0);

            flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);

            flightController.setVirtualStickModeEnabled(false, null);

            isTraveling = false;

        } catch (InterruptedException e) {
            e.printStackTrace();
            Log.d("DEBUG", "ERROR: performing 360º rotation" + e.getMessage());
        }
    }

    void takeOffTo(float targetAltitude, OnCompletionCallback callback) {
        FlightController fc = flightController;
        fc.turnOnMotors(djiError -> {
            if (djiError != null) {
                Log.e("MISSION", "Decolagem falhou: " + djiError.getDescription());
            } else {
                Log.d("MISSION", "Decolagem em progresso...");

                fc.setVirtualStickModeEnabled(true, err -> {
                    if (err != null) {
                        Log.e("MISSION", "Não foi possível ativar Virtual Stick: " + err.getDescription());
                        return;
                    }

                    final Handler handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            float altitude = fc.getState().getAircraftLocation().getAltitude();
                            if (altitude < targetAltitude - 0.5f) { // 0.5m de tolerância
                                //sobe a 1m/s
                                fc.sendVirtualStickFlightControlData(new FlightControlData(0f, 0f, 0f, 3.0f), null);
                                Log.i("MISSION", "Subindo... Altitude atual: " + altitude + "m");
                                handler.postDelayed(this, 200);
                            } else {
                                fc.sendVirtualStickFlightControlData(new FlightControlData(0f, 0f, 0f, 0f), null);
                                fc.setVirtualStickModeEnabled(false, null);
                                Log.i("MISSION", "Altitude de " + altitude + "m atingida. Hover estável.");
                                callback.run();
                            }
                        }
                    }, 3000);
                });
            }
        });
    }

}