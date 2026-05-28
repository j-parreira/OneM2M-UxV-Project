/**
 * {@code TelemetryManager} — Coleção e envio de telemetria do drone.
 *
 * Recebe dados de telemetria via DJI SDK (posição, velocidade, bateria, gimbal, camera),
 * polled a 250ms, e envia via {@link com.dji.sdk.duvops.network.ProtocolClient} como JSON.
 *
 * <h3>Campos de benchmark</h3>
 * <ul>
 *   <li>{@code seq} — número de sequência monotónico; permite calcular packet loss no servidor</li>
 *   <li>{@code t_send_ms} — epoch ms no momento do envio; para cálculo de latência one-way</li>
 * </ul>
 *
 * <h3>Campos de bateria do drone</h3>
 * <ul>
 *   <li>{@code lvl} — % restante</li>
 *   <li>{@code temperature} — °C</li>
 *   <li>{@code charging} — a carregar?</li>
 *   <li>{@code connectionState} — estado da ligação</li>
 * </ul>
 *
 * <h3>Campos de bateria do RC</h3>
 * <ul>
 *   <li>{@code lvl} — % restante</li>
 *   <li>{@code remainingMah} — mAh restante</li>
 *   <li>{@code charging} — a carregar?</li>
 * </ul>
 *
 * @author Joao Parreira
 * @version 4.0
 */
package com.dji.sdk.duvops.flight;

import android.util.Log;
import androidx.annotation.NonNull;

import com.dji.sdk.duvops.network.ProtocolClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import dji.common.camera.CameraVideoStreamSource;
import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.LEDsSettings;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.gimbal.Attitude;
import dji.common.model.LocationCoordinate2D;
import dji.common.remotecontroller.BatteryState;
import dji.common.util.CommonCallbacks;
import dji.keysdk.CameraKey;
import dji.keysdk.DJIKey;
import dji.keysdk.GimbalKey;
import dji.keysdk.KeyManager;
import dji.keysdk.RemoteControllerKey;
import dji.keysdk.callback.KeyListener;
import dji.sdk.battery.Battery;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Gestor de telemetria: recolha de dados do drone e envio via WebSocket.
 *
 * <h3>Polling de telemetria</h3>
 * <p>Timer a 250ms que recolhe estados do {@link FlightController}, camera e gimbal,
 * e envia como JSON via {@link NetworkManager}.
 *
 * <h3>Dados lentos vs rapidos</h3>
 * <p>Os dados da bateria do controlador e LEDs sao lidos a cada 1s (a cada 4 ticks).
 * O resto da telemetria e lido a cada tick (250ms).
 */
public class TelemetryManager {

    /** Tag para log. */
    private static final String TAG = "TelemetryManager";

    /** Cliente de protocolo para envio de telemetria ao CSE. */
    private final ProtocolClient protocolClient;

    /** Controlador de voo do drone. */
    private FlightController flightController;

    /** Timer para polling de telemetria (250ms interval). */
    private Timer timer;

    /** Indica se a telemetria esta ativa. */
    private boolean isSending = false;

    /** Contador de ticks (incrementado a cada 250ms). */
    private int tickCount = 0;

    /** Estado atual do voo (atualizado pelo callback do SDK). */
    private volatile FlightControllerState currentFlightState = null;

    //region Cache de dados do drone

    /** Bateria do drone (porcentagem). */
    private int batteryPercent = 0;

    /** Temperatura da bateria do drone (°C). */
    private float batteryTemp = 0;

    /** Indica se a bateria do drone esta a carregar. */
    private boolean batteryCharging = false;

    /** Estado da ligacao da bateria do drone. */
    private String batteryConnectionState = "UNKNOWN";

    /** Bateria do drone — estado atual para aceder a getChargeRemaining() via reflection. */
    private volatile Object currentDroneBatteryState = null;

    /** Voltagem da bateria do drone (mV). */
    private int batteryVoltage = 0;

    /** Corrente da bateria do drone (mA). */
    private int batteryCurrent = 0;

    /** Pitch do gimbal (graus). */
    private float gimbalPitch = 0;

    /** Roll do gimbal (graus). */
    private float gimbalRoll = 0;

    /** Yaw do gimbal (graus). */
    private float gimbalYaw = 0;

    /** Indica se os LEDs do drone estao ligados. */
    private boolean areLightsOn = false;

    /** NIVel de bateria do controlador remoto (porcentagem). */
    private int rcBatLvl = -1;

    /** Capacidade restante da bateria do controlador (mAh). */
    private int rcBatRemainingMah = -1;

    /** Indica se a bateria do controlador esta a carregar. */
    private boolean rcBatCharging = false;

    //endregion

    //region Cache da camera

    /** Fator de zoom atual (ex: 1.0, 2.5, 32.0). */
    private double cameraZoom = 1.0;

    /** Modo da camera: RGB, IR, SPLIT ou UNKNOWN. */
    private String cameraMode = "UNKNOWN";

    //endregion

    //region Chaves SDK

    /** Chave para bateria do controlador remoto. */
    private RemoteControllerKey rcBatteryKey;

    /** Chave para atitude do gimbal (pitch/roll/yaw). */
    private DJIKey gimbalAttitudeKey;

    /** Chave para source de video da camera (WIDE, INFRARED_THERMAL). */
    private DJIKey videoSourceKey;

    /** Chave para modo de display (PIP para split-screen). */
    private DJIKey displayModeKey;

    /** Chave para zoom hibrido (focal length) — M2EA especifico. */
    private DJIKey hybridZoomKey;

    //endregion

    /** Indica se o drone esta em viagem (modo de navegacao). */
    private boolean isTraveling = false;

    /** Nome do modelo do drone (ex: Mavic 2 Enterprise Advanced). */
    private String modelName = "Unknown";

    /**
     * Contador de sequência para detecção de packet loss no servidor.
     *
     * <p>Incrementado atomicamente a cada mensagem enviada; o servidor detecta
     * mensagens perdidas por gaps no valor de {@code seq}. Reinicia em 0 quando
     * a sessão é criada (não persiste entre sessões OneM2M).
     */
    private final AtomicInteger seqCounter = new AtomicInteger(0);

    /**
     * Intervalo entre mensagens de telemetria em ms.
     *
     * <p>Valores de benchmark: 1000 ms (1 msg/s), 200 ms (5 msg/s), 100 ms (10 msg/s).
     * Alterado via {@link #setRate(int)} a partir do comando OneM2M {@code setTelemetryRate}.
     */
    private int intervalMs = 250;

    /**
     * Callback de tick lento (≈1 s, a cada 4 ticks do timer principal) para
     * actualizar a UI com métricas de telemetria e estado do drone.
     */
    public interface TelemetryTickListener {
        /**
         * @param seq            valor actual do contador de sequência
         * @param batteryPercent percentagem de bateria do drone (0-100, -1 se desconhecido)
         * @param isFlying       {@code true} se o drone está em voo
         * @param satCount       número de satélites GPS visíveis
         */
        void onSlowTick(int seq, int batteryPercent, boolean isFlying, int satCount);
    }

    /** Listener de tick lento — pode ser {@code null}. */
    private TelemetryTickListener tickListener;

    /**
     * Define o listener de tick lento.
     *
     * @param listener listener a notificar a cada ~1 s (pode ser {@code null})
     */
    public void setTickListener(TelemetryTickListener listener) {
        this.tickListener = listener;
    }

    /**
     * Altera a taxa de envio de telemetria durante um run de benchmark.
     *
     * <p>Reinicia o timer com o novo intervalo se a telemetria já estiver activa.
     * Valores típicos de benchmark:
     * <ul>
     *   <li>1000 ms → 1 msg/s (Cenário 1, taxa baixa)</li>
     *   <li>200 ms → 5 msg/s (Cenário 1, taxa média)</li>
     *   <li>100 ms → 10 msg/s (Cenário 1, taxa alta)</li>
     * </ul>
     *
     * @param newIntervalMs intervalo em ms (mínimo 50 ms = 20 msg/s)
     */
    public void setRate(int newIntervalMs) {
        this.intervalMs = Math.max(50, newIntervalMs);
        Log.d(TAG, "Telemetry rate changed to " + this.intervalMs + " ms (" +
                (1000 / this.intervalMs) + " msg/s)");
        if (isSending) {
            stopTelemetry();
            startTelemetry();
        }
    }

    /**
     * Cria um novo gestor de telemetria.
     *
     * @param protocolClient cliente de protocolo para envio de dados ao CSE
     * @param flightController o controlador de voo do drone
     */
    public TelemetryManager(ProtocolClient protocolClient, FlightController flightController) {
        this.protocolClient = protocolClient;
        this.flightController = flightController;
        initKeys();
        setupListeners();
    }

    /**
     * Inicializa as chaves SDK para subscricao de dados.
     *
     * <p>Configura as chaves para bateria do controlador, gimbal,
     * source de video, modo de display e zoom hibrido (M2EA).
     */
    private void initKeys() {
        rcBatteryKey = RemoteControllerKey.create(RemoteControllerKey.BATTERY_STATE);
        gimbalAttitudeKey = GimbalKey.create(GimbalKey.ATTITUDE_IN_DEGREES);

        // M2EA: Usar HYBRID ZOOM em vez de zoom linear
        hybridZoomKey = CameraKey.create(CameraKey.HYBRID_ZOOM_FOCAL_LENGTH);
        videoSourceKey = CameraKey.create(CameraKey.CAMERA_VIDEO_STREAM_SOURCE);
        displayModeKey = CameraKey.create(CameraKey.DISPLAY_MODE);
    }

    /**
     * Atualiza a referencia do controlador de voo.
     *
     * @param fc o novo FlightController
     */
    public void setFlightController(FlightController fc) {
        this.flightController = fc;
        setupListeners();
    }

    /**
     * Define o estado de viagem (navegacao por waypoints).
     *
     * @param traveling {@code true} se esta em modo de navegacao
     */
    public void setTraveling(boolean traveling) {
        this.isTraveling = traveling;
    }

    /**
     * Define o nome do modelo do drone.
     *
     * @param name o nome do modelo
     */
    public void setModelName(String name) {
        this.modelName = name;
    }

    /**
     * Regista listeners para dados em tempo real via DJI KeySDK.
     *
     * <p>Subscreve: estado do voo, bateria da aeronave, atitude do gimbal,
     * source de video, modo de display e zoom hibrido.
     */
    private void setupListeners() {
        KeyManager keyManager = KeyManager.getInstance();
        Aircraft aircraft = (Aircraft) DJISDKManager.getInstance().getProduct();

        if (flightController != null) {
            flightController.setStateCallback(new FlightControllerState.Callback() {
                @Override
                public void onUpdate(@NonNull FlightControllerState state) {
                    currentFlightState = state;
                }
            });
        }

        if (aircraft != null) {
            Battery battery = aircraft.getBattery();
            if (battery != null) {
                battery.setStateCallback(state -> {
                    currentDroneBatteryState = state;
                    batteryPercent = state.getChargeRemainingInPercent();
                    batteryTemp = (float) state.getTemperature();
                    batteryCharging = state.isBeingCharged();
                    // getConnectionState() can return null on initial callback — guard before toString()
                    batteryConnectionState = state.getConnectionState() != null
                            ? state.getConnectionState().toString() : "UNKNOWN";
                    // Campos adicionais extraidos do BatteryState push data
                    batteryVoltage = state.getVoltage();       // mV
                    batteryCurrent = state.getCurrent();        // mA
                });
            }
        }

        if (keyManager == null) return;

        // Gimbal attitude (pitch/roll/yaw)
        if (gimbalAttitudeKey != null) {
            keyManager.addListener(gimbalAttitudeKey, (oldValue, newValue) -> {
                if (newValue instanceof Attitude) {
                    Attitude attitude = (Attitude) newValue;
                    gimbalPitch = attitude.getPitch();
                    gimbalRoll = attitude.getRoll();
                    gimbalYaw = attitude.getYaw();
                }
            });
        }

        // CAMERA SOURCE (RGB vs IR)
        if (videoSourceKey != null) {
            keyManager.addListener(videoSourceKey, (oldVal, newVal) -> {
                updateCameraModeFromSource(newVal);
            });
        }

        // DISPLAY MODE (Detetar PIP → SPLIT)
        if (displayModeKey != null) {
            keyManager.addListener(displayModeKey, (oldVal, newVal) -> {
                if (newVal instanceof SettingsDefinitions.DisplayMode) {
                    if (newVal == SettingsDefinitions.DisplayMode.PIP) {
                        cameraMode = "SPLIT";
                    } else {
                        // Saiu do PIP — releitura do source para saber se e RGB ou IR
                        Object source = KeyManager.getInstance().getValue(videoSourceKey);
                        updateCameraModeFromSource(source);
                    }
                }
            });
        }

        // ZOOM (Hybrid Focal Length)
        if (hybridZoomKey != null) {
            keyManager.addListener(hybridZoomKey, (oldVal, newVal) -> {
                if (newVal instanceof Integer) {
                    int focalLength = (Integer) newVal;
                    // Conversao M2EA: 240 = 1.0x
                    cameraZoom = focalLength / 240.0;
                    // Arredondar para 1 casa decimal
                    cameraZoom = Math.round(cameraZoom * 10.0) / 10.0;
                }
            });
        }
    }

    /**
     * Atualiza o modo da camera com base no source de video.
     *
     * @param value o valor atual da source de video
     */
    private void updateCameraModeFromSource(Object value) {
        if (value instanceof CameraVideoStreamSource) {
            CameraVideoStreamSource source = (CameraVideoStreamSource) value;
            if (source == CameraVideoStreamSource.WIDE || source == CameraVideoStreamSource.ZOOM) {
                cameraMode = "RGB";
            } else if (source == CameraVideoStreamSource.INFRARED_THERMAL) {
                cameraMode = "IR";
            } else {
                cameraMode = source.name();
            }
        }
    }

    /**
     * Inicia o polling de telemetria a 250ms.
     *
     * <p>Os dados lentos (bateria do controlador, LEDs) sao atualizados
     * a cada 1s (a cada 4 ticks). O resto e lido a cada tick.
     */
    public void startTelemetry() {
        if (isSending) return;

        forceInitialCameraRead(); // Leitura inicial da camera

        Log.d(TAG, "Starting Telemetry Stream...");
        isSending = true;
        tickCount = 0;
        // Calcular quantos ticks constituem ~1 s para o slow tick
        final int ticksPerSecond = Math.max(1, 1000 / intervalMs);
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (tickCount % ticksPerSecond == 0) {
                    updateSlowData();
                    if (tickListener != null) {
                        boolean flying = currentFlightState != null && currentFlightState.isFlying();
                        int sats = currentFlightState != null ? currentFlightState.getSatelliteCount() : 0;
                        tickListener.onSlowTick(seqCounter.get(), batteryPercent, flying, sats);
                    }
                }
                tickCount++;
                collectAndSend();
            }
        }, 0, intervalMs);
    }

    /**
     * Forca a leitura inicial dos valores da camera (zoom, mode, display).
     *
     * <p>Util para obter o estado atual imediatamente, sem esperar pelos callbacks.
     */
    private void forceInitialCameraRead() {
        if (KeyManager.getInstance() == null) return;

        Object zoomVal = KeyManager.getInstance().getValue(hybridZoomKey);
        if (zoomVal instanceof Integer) {
            cameraZoom = (Integer) zoomVal / 240.0;
            cameraZoom = Math.round(cameraZoom * 10.0) / 10.0;
        }

        Object sourceVal = KeyManager.getInstance().getValue(videoSourceKey);
        updateCameraModeFromSource(sourceVal);

        // Verificar se esta em PIP (split-screen)
        Object displayVal = KeyManager.getInstance().getValue(displayModeKey);
        if (displayVal == SettingsDefinitions.DisplayMode.PIP) {
            cameraMode = "SPLIT";
        }
    }

    /**
     * Para o polling de telemetria.
     *
     * <p>Cancela o timer e liberta recursos.
     */
    public void stopTelemetry() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        isSending = false;
    }

    /**
     * Atualiza dados lentos: bateria do controlador remoto e configuracao de LEDs.
     *
     * <p>Chamado a cada 1s (a cada 4 ticks do timer principal).
     */
    private void updateSlowData() {
        if (KeyManager.getInstance() != null && rcBatteryKey != null) {
            Object value = KeyManager.getInstance().getValue(rcBatteryKey);
            if (value instanceof BatteryState) {
                BatteryState state = (BatteryState) value;
                rcBatLvl = state.getRemainingChargeInPercent();
                rcBatRemainingMah = state.getRemainingChargeInmAh();
                rcBatCharging = state.isCharging();
            }
        }

        if (flightController != null) {
            flightController.getLEDsEnabledSettings(new CommonCallbacks.CompletionCallbackWith<LEDsSettings>() {
                @Override
                public void onSuccess(LEDsSettings settings) {
                    areLightsOn = settings.areBeaconsOn() && settings.areFrontLEDsOn();
                }
                @Override
                public void onFailure(DJIError djiError) {}
            });
        }
    }

    /**
     * Recolhe todos os dados de telemetria e envia via WebSocket.
     *
     * <p>Inclui: posicao GPS, velocidade, bateria, gimbal, camera, compasso,
     * estado de voo e home location.
     */
    private void collectAndSend() {
        JSONObject status = new JSONObject();

        try {
            FlightControllerState state = currentFlightState;
            if (state == null && flightController != null) {
                state = flightController.getState();
            }

            if (state != null) {
                LocationCoordinate3D loc = state.getAircraftLocation();
                double lat = (loc != null) ? safeDouble(loc.getLatitude()) : 0;
                double lng = (loc != null) ? safeDouble(loc.getLongitude()) : 0;
                double alt = (loc != null) ? safeFloat(loc.getAltitude()) : 0;

                status.put("lat", lat);
                status.put("lng", lng);
                status.put("alt", alt);

                status.put("velX", safeDouble(state.getVelocityX()));
                status.put("velY", safeDouble(state.getVelocityY()));
                status.put("velZ", safeDouble(state.getVelocityZ()));

                status.put("isFlying", state.isFlying());
                status.put("satCount", state.getSatelliteCount());
                status.put("rft", state.getGoHomeAssessment().getRemainingFlightTime());
                status.put("isGoingHome", state.isGoingHome());
                status.put("areMotorsOn", state.areMotorsOn());
                status.put("isHomeLocationSet", state.isHomeLocationSet());

                JSONObject homeLoc = new JSONObject();
                if (state.isHomeLocationSet()) {
                    LocationCoordinate2D hLoc = state.getHomeLocation();
                    homeLoc.put("lat", hLoc != null ? safeDouble(hLoc.getLatitude()) : 0);
                    homeLoc.put("lng", hLoc != null ? safeDouble(hLoc.getLongitude()) : 0);
                } else {
                    homeLoc.put("lat", 0);
                    homeLoc.put("lng", 0);
                }
                status.put("homeLocation", homeLoc);
            } else {
                status.put("lat", 0); status.put("lng", 0); status.put("alt", 0);
                status.put("velX", 0); status.put("velY", 0); status.put("velZ", 0);
                status.put("isFlying", false);
                status.put("satCount", 0);
                status.put("rft", 0);
                status.put("homeLocation", new JSONObject().put("lat", 0).put("lng", 0));
            }

            if (flightController != null && flightController.getCompass() != null) {
                status.put("hdg", safeFloat(flightController.getCompass().getHeading()));
            } else {
                status.put("hdg", 0);
            }

            status.put("isTraveling", isTraveling);
            status.put("model", modelName);

            // --- Bateria do drone (agrupada) ---
            JSONObject bat = new JSONObject();
            bat.put("lvl", batteryPercent);
            bat.put("remaining", batteryPercent > 0 && currentDroneBatteryState != null
                    ? getDroneBatRemaining() : 0);
            bat.put("temperature", batteryTemp);
            bat.put("charging", batteryCharging);
            bat.put("connectionState", batteryConnectionState);
            bat.put("voltage", batteryVoltage);      // mV
            bat.put("current", batteryCurrent);       // mA
            status.put("bat", bat);

            // --- Bateria do controlador (agrupada) ---
            JSONObject rcBat = new JSONObject();
            rcBat.put("lvl", rcBatLvl);
            rcBat.put("remainingMah", rcBatRemainingMah);
            rcBat.put("charging", rcBatCharging);
            status.put("rcBat", rcBat);

            // --- Gimbal (agrupado) ---
            JSONObject gimbal = new JSONObject();
            gimbal.put("pitch", gimbalPitch);
            gimbal.put("roll", gimbalRoll);
            gimbal.put("yaw", gimbalYaw);
            status.put("gimbal", gimbal);

            // Camera
            status.put("zoom", cameraZoom);
            status.put("cameraMode", cameraMode);

            // Campos de benchmark: sequência e timestamp de envio
            // seq: detectar packet loss — gaps indicam mensagens perdidas
            // t_send_ms: latência one-way quando relógios sincronizados via NTP
            status.put("seq", seqCounter.incrementAndGet());
            status.put("t_send_ms", System.currentTimeMillis());

            protocolClient.sendTelemetry(status.toString());

        } catch (JSONException e) {
            Log.e(TAG, "Erro ao criar JSON: " + e.getMessage());
        }
    }

    /**
     * Acede a BatteryState.getChargeRemaining() via reflection.
     *
     * <p>A classe BatteryState do drone nao e importavel diretamente no DJI SDK v4.
     *
     * @return mAh restante ou 0 se nao disponivel
     */
    private int getDroneBatRemaining() {
        if (currentDroneBatteryState == null) return 0;
        try {
            java.lang.reflect.Method m = currentDroneBatteryState.getClass()
                    .getMethod("getChargeRemaining");
            Object result = m.invoke(currentDroneBatteryState);
            if (result instanceof Integer) return (Integer) result;
        } catch (Exception e) {
            Log.e(TAG, "Nao foi possivel obter getChargeRemaining: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Converte double para 0.0 se for NaN.
     *
     * @param val o valor a validar
     * @return o valor se valido, 0.0 caso contrario
     */
    private double safeDouble(double val) {
        return Double.isNaN(val) ? 0.0 : val;
    }

    /**
     * Converte float para 0.0 se for NaN.
     *
     * @param val o valor a validar
     * @return o valor se valido, 0.0 caso contrario
     */
    private double safeFloat(float val) {
        return Float.isNaN(val) ? 0.0 : val;
    }
}
