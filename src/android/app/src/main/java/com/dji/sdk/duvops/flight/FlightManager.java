/**
 * {@code FlightManager} — Gestor de voo e navegação do drone.
 *
 * Implementa a interface {@link DroneCommandListener} para rececionar comandos
 * do servidor via WebSocket e executá-los no drone. Inclui:
 * <ul>
 *   <li>Comandos básicos: takeoff, land, motors, goHome</li>
 *   <li>Navegação GPS via PID + virtual sticks</li>
 *   <li>Virtual sticks para controlo em tempo real</li>
 *   <li>Execução de missões waypoint com estados (RUNNING/PAUSED/STOPPED)</li>
 *   <li>Delegação de zoom e câmara ao {@link CameraManager}</li>
 * </ul>
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.flight;

import android.util.Log;

import com.dji.sdk.duvops.network.DroneCommandListener;
import com.dji.sdk.duvops.utils.ModuleVerificationUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dji.common.error.DJIError;
import dji.common.flightcontroller.LEDsSettings;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.products.Aircraft;

import static java.lang.Math.pow;
import static java.lang.Math.sqrt;

/**
 * Gestor de voo — executa comandos de voo no drone via DJI SDK.
 *
 * <h3>Modo de operação</h3>
 * <p>O FlightController é configurado para usar velocity-based control:
 * <ul>
 *   <li>Pitch/Roll: velocidade (m/s)</li>
 *   <li>Throttle: velocidade vertical (m/s)</li>
 *   <li>Yaw: velocidade angular (°/s) ou ângulo absoluto (ANGLE)</li>
 * </ul>
 *
 * <h3>Navegação GPS</h3>
 * <p>Usa um controlador PID para navegar até coordenadas GPS:
 * <ul>
 *   <li>{@code kp=0.2, ki=0.0001, kd=0.2}</li>
 *   <li>Limite máximo de pitch: ±15°</li>
 *   <li>Distância de paragem: 2m</li>
 *   <li>Cálculo de distância: Haversine formula</li>
 * </ul>
 *
 * <h3>Missões</h3>
 * <p>Executa uma sequência de waypoints num thread separado.
 * Estados: {@code STOPPED → RUNNING → PAUSED → RUNNING → STOPPED}.
 *
 * <h3>Virtual Sticks</h3>
 * <p>Enviados a 5Hz (200ms) via {@link Timer}. Os valores são atualizados
 * por {@link #onVirtualStickInput(float, float, float, float)} e enviados
 * pelo {@link SendVirtualStickDataTask}.
 */
public class FlightManager implements DroneCommandListener {

    /** Tag para log. */
    private static final String TAG = "FlightManager";

    /** Controlador de voo do drone — obtido via {@link ModuleVerificationUtil}. */
    private FlightController flightController;

    /** Gestor de câmara — delegado para zoom e modo. */
    private CameraManager cameraManager;

    /** Gimbal do drone — obtido via {@link #initGimbal()} ao conectar. */
    private Gimbal gimbal;

    // --- Variáveis de controlo de voo (Virtual Sticks) ---

    /** Última posição de pitch, roll, yaw e throttle recebida. */
    private float pitch, roll, yaw, throttle;

    /** Timer para enviar dados de virtual stick ao drone. */
    private Timer sendVirtualStickDataTimer;

    /** Tarefa do timer que envia os dados de virtual stick. */
    private SendVirtualStickDataTask sendVirtualStickDataTask;

    // --- Variáveis de navegação ---

    /**
     * Indica se o drone está em movimento via GPS.
     * volatile: lido/escrito de múltiplos threads (mission thread + network thread).
     */
    private volatile boolean isTraveling = false;

    // --- Variáveis de missão ---

    /** Lock para sincronização entre thread da missão e estados. */
    private final Object lock = new Object();

    /** Estados da missão: RUNNING, PAUSED, STOPPED. */
    private enum MissionState { RUNNING, PAUSED, STOPPED }
    private MissionState missionState = MissionState.STOPPED;

    /** Thread que executa a missão (waypoints). */
    private Thread missionThread;

    // --- Interface para comunicar com a UI ---

    /**
     * Interface para notificar a UI de mudanças de estado.
     */
    public interface UiUpdateListener {
        /**
         * Notifica a UI de uma mudança de estado.
         *
         * @param status a mensagem de estado
         * @param isError {@code true} se o status é um erro
         */
        void onStatusUpdate(String status, boolean isError);
    }
    private UiUpdateListener uiListener;

    /**
     * Cria o FlightManager com o listener de UI.
     *
     * @param uiListener o listener para notificar a UI
     */
    public FlightManager(UiUpdateListener uiListener) {
        this.uiListener = uiListener;
        initFlightController();
    }

    /**
     * Inicializa ou reobtém o controlador de voo.
     *
     * <p>Configura os modos de controlo para velocidade-based:
     * <ul>
     *   <li>Vertical: VELOCITY</li>
     *   <li>Pitch/Roll: VELOCITY (corpo)</li>
     *   <li>Yaw: ANGULAR_VELOCITY</li>
     * </ul>
     */
    public void initFlightController() {
        flightController = ModuleVerificationUtil.getFlightController();
        if (flightController != null) {
            flightController.setVerticalControlMode(VerticalControlMode.VELOCITY);
            flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
            flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
            flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
        }
    }

    /**
     * Inicializa a referência ao gimbal do drone.
     *
     * <p>Obtém o gimbal via {@link Aircraft#getGimbals()} (índice 0).
     */
    public void initGimbal() {
        Aircraft aircraft = (Aircraft) com.dji.sdk.duvops.app.App.getProductInstance();
        if (aircraft != null && aircraft.getGimbals() != null && !aircraft.getGimbals().isEmpty()) {
            gimbal = aircraft.getGimbals().get(0);
        }
    }

    /**
     * Obtém o controlador de voo, inicializando se necessário.
     *
     * @return o FlightController ou {@code null} se não disponível
     */
    public FlightController getFlightController() {
        if (flightController == null) initFlightController();
        return flightController;
    }

    /** Indica se o drone está em movimento via GPS. */
    public boolean isTraveling() {
        return isTraveling;
    }

    //region DroneCommandListener Implementation

    /**
     * Notifica a UI da mudança de estado de conexão.
     */
    @Override
    public void onConnectionStatusChange(boolean isConnected, String message) {
        if (uiListener != null) uiListener.onStatusUpdate(message, !isConnected);
    }

    /**
     * Executa o takeoff.
     *
     * <p>Verifica se o controlador está disponível antes de enviar o comando.
     */
    @Override
    public void onTakeOff() {
        if (checkController()) {
            Log.d(TAG, "Executing TakeOff");
            flightController.startTakeoff(this::handleResult);
        }
    }

    /**
     * Executa o landing.
     */
    @Override
    public void onLand() {
        if (checkController()) {
            Log.d(TAG, "Executing Landing");
            flightController.startLanding(this::handleResult);
        }
    }

    /**
     * Liga ou desliga os motores.
     *
     * @param on {@code true} para ligar, {@code false} para desligar
     */
    @Override
    public void onMotors(boolean on) {
        if (checkController()) {
            Log.d(TAG, "Setting Motors: " + on);
            if (on) {
                flightController.turnOnMotors(this::handleResult);
            } else {
                flightController.turnOffMotors(this::handleResult);
            }
        }
    }

    /**
     * Executa o comando GoHome (retornar ao ponto de partida).
     */
    @Override
    public void onGoHome() {
        if (checkController()) {
            Log.d(TAG, "Executing GoHome");
            flightController.startGoHome(this::handleResult);
        }
    }

    /**
     * Liga ou desliga os LEDs e faróis do drone.
     *
     * @param on {@code true} para ligar, {@code false} para desligar
     */
    @Override
    public void onIdentify(boolean on) {
        if (checkController()) {
            Log.d(TAG, "Identifying (LEDs): " + on);
            LEDsSettings.Builder ledsSettings = new LEDsSettings.Builder();
            ledsSettings.frontLEDsOn(on);
            ledsSettings.beaconsOn(on);
            ledsSettings.rearLEDsOn(on);
            ledsSettings.statusIndicatorOn(on);
            flightController.setLEDsEnabledSettings(ledsSettings.build(), this::handleResult);
        }
    }

    /**
     * Liga ou desliga o modo virtual stick.
     *
     * <p>Quando ativado, também ativa o modo avançado para controlo Attitude.
     *
     * @param enabled {@code true} para ativar, {@code false} para desativar
     */
    @Override
    public void onVirtualStickState(boolean enabled) {
        if (checkController()) {
            if (enabled) {
                flightController.setVirtualStickModeEnabled(true, djiError -> {
                    if (djiError == null) flightController.setVirtualStickAdvancedModeEnabled(true);
                    handleResult(djiError);
                });
            } else {
                stopVirtualStickSending();
                flightController.setVirtualStickModeEnabled(false, this::handleResult);
            }
        }
    }

    /**
     * Atualiza os valores de pitch, roll, yaw e throttle para envio via virtual stick.
     *
     * <p>Se o timer de envio não estiver ativo, inicia-o automaticamente.
     *
     * @param r valor de roll (-1.0 a 1.0)
     * @param p valor de pitch (-1.0 a 1.0)
     * @param y valor de yaw (-180.0 a 180.0)
     * @param t valor de throttle (-1.0 a 1.0)
     */
    /** Multiplier para pitch/roll (legacy factor: 10x). */
    private static final float VIRTUAL_STICK_SPATIAL_SCALE = 10.0f;

    /** Multiplier para yaw (legacy factor: 20x). */
    private static final float VIRTUAL_STICK_YAW_SCALE = 20.0f;

    /** Multiplier para throttle (legacy factor: 4x). */
    private static final float VIRTUAL_STICK_THROTTLE_SCALE = 4.0f;

    @Override
    public void onVirtualStickInput(float r, float p, float y, float t) {
        // Aplicar scaling identico ao legacy para manter compatibilidade com o servidor
        this.roll = Math.abs(r) < 0.02f ? 0 : r * VIRTUAL_STICK_SPATIAL_SCALE;
        this.pitch = Math.abs(p) < 0.02f ? 0 : p * VIRTUAL_STICK_SPATIAL_SCALE;
        this.yaw = Math.abs(y) < 0.02f ? 0 : y * VIRTUAL_STICK_YAW_SCALE;
        this.throttle = Math.abs(t) < 0.02f ? 0 : t * VIRTUAL_STICK_THROTTLE_SCALE;

        if (sendVirtualStickDataTimer == null) {
            startVirtualStickSending();
        }
    }

    /**
     * Navega o drone para as coordenadas GPS fornecidas.
     *
     * <p>Ignora o comando se já estiver em movimento ({@code isTraveling == true}).
     * Usa um PID controller para navegação suave.
     *
     * @param lat latitude do destino
     * @param lng longitude do destino
     */
    @Override
    public void onMoveTo(double lat, double lng) {
        if (!checkController()) return;
        if (isTraveling) {
            Log.d(TAG, "Já em movimento, comando ignorado.");
            return;
        }
        executeGpsMove(lat, lng);
    }

    /**
     * Executa uma rotação de 360° em yaw.
     */
    @Override
    public void onPerform360() {
        if (checkController()) perform360Logic();
    }

    //endregion

    //region Mission Management

    /**
     * Inicia uma missão de waypoints.
     *
     * @param startAction ação de início ("takeoff" ou null)
     * @param endAction ação de fim ("land", "goHome" ou null)
     * @param repeat número de repetições da rota
     * @param altitude altitude da missão em metros
     * @param pathJson JSON array com os waypoints: [{"lat": x, "lng": y}, ...]
     */
    @Override
    public void onStartMission(String startAction, String endAction, int repeat, float altitude, String pathJson) {
        try {
            JSONArray missionPath = new JSONArray(pathJson);
            startMissionLogic(startAction, endAction, repeat, altitude, missionPath);
        } catch (Exception e) {
            Log.e(TAG, "Invalid mission path JSON: " + e.getMessage());
        }
    }

    /**
     * Para a missão imediatamente.
     *
     * <p>Define isTraveling=false para interromper o PID loop em gpsInputTo(),
     * desativa virtual sticks, reseta YawControlMode e notifica o thread da missão.
     * Espelha stopMission() do legacy.
     */
    @Override
    public void onStopMission() {
        synchronized (lock) {
            missionState = MissionState.STOPPED;
            isTraveling = false;           // interrompe o while loop em gpsInputTo()
            if (flightController != null) {
                flightController.setVirtualStickModeEnabled(false, null);
                flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
            }
            lock.notifyAll();
            Log.d(TAG, "Comando Stop Mission recebido.");
        }
    }

    /**
     * Pausa ou retoma a missão (toggle).
     *
     * <p><b>RUNNING → PAUSED:</b> Desativa virtual sticks imediatamente (drone
     * para fisicamente) e define isTraveling=false para sair do PID loop. O thread
     * da missão pausa no início do próximo waypoint via lock.wait().
     *
     * <p><b>PAUSED → RUNNING:</b> Reativa virtual sticks com todos os modos GPS
     * (ANGLE + BODY + VELOCITY) antes de notificar o thread, para que a navegação
     * retome corretamente. Espelha pauseMission()/resumeMission() do legacy.
     */
    @Override
    public void onPauseMission() {
        synchronized (lock) {
            if (missionState == MissionState.RUNNING) {
                missionState = MissionState.PAUSED;
                isTraveling = false;       // sai do while loop em gpsInputTo()
                if (flightController != null) {
                    flightController.setVirtualStickModeEnabled(false, null);
                    flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                }
                Log.d(TAG, "Missão pausada.");
            } else if (missionState == MissionState.PAUSED) {
                missionState = MissionState.RUNNING;
                isTraveling = true;        // reautoriza o PID loop no próximo waypoint
                // Para o timer de sticks manuais se estiver ativo (pode ter sido iniciado
                // por virtualSticksInput durante a pausa) — evita concorrência com o PID.
                stopVirtualStickSending();
                if (flightController != null) {
                    flightController.setVirtualStickModeEnabled(true, null);
                    flightController.setYawControlMode(YawControlMode.ANGLE);
                    flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
                    flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
                }
                lock.notifyAll();
                Log.d(TAG, "Missão retomada.");
            }
        }
    }

    /**
     * Executa a lógica da missão num thread separado.
     *
     * <p>Fases da missão:
     * <ol>
     *   <li><b>Takeoff:</b> se startAction="takeoff", descola o drone</li>
     *   <li><b>Waypoints:</b> loop de navegação GPS com pausa/stop support</li>
     *   <li><b>Fim:</b> land ou goHome conforme endAction</li>
     * </ol>
     *
     * @param startAction ação de início
     * @param endAction ação de fim
     * @param repeat número de repetições
     * @param altitude altitude da missão
     * @param path array JSON dos waypoints
     */
    private void startMissionLogic(String startAction, String endAction, int repeat, float altitude, JSONArray path) {
        synchronized (lock) {
            if (missionState != MissionState.STOPPED && missionThread != null && missionThread.isAlive()) {
                Log.d(TAG, "Missão já em curso. Ignorado.");
                return;
            }
            missionState = MissionState.RUNNING;
        }

        missionThread = new Thread(() -> {
            try {
                Log.d(TAG, "Iniciando thread da missão...");

                if (!checkController()) throw new Exception("FlightController not ready");

                // Fase 1: Takeoff
                if ("takeoff".equals(startAction) && !flightController.getState().isFlying()) {
                    Log.d(TAG, "Missão: A descolar...");
                    if (!waitForTakeoff(altitude)) {
                        Log.e(TAG, "Missão: Takeoff falhou, abortar.");
                        return;
                    }
                }

                // Parar qualquer timer de sticks manual que possa estar ativo —
                // evita concorrência entre o PID loop e o SendVirtualStickDataTask.
                stopVirtualStickSending();

                // Configurar voo para waypoints
                isTraveling = true;
                flightController.setVirtualStickModeEnabled(true, null);
                flightController.setYawControlMode(YawControlMode.ANGLE);
                flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
                flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);

                // Fase 2: Loop de Waypoints com pausa/stop support
                mission_loop:
                for (int r = 0; r <= repeat; r++) {
                    if (path == null) break;

                    for (int i = 0; i < path.length(); i++) {
                        // Suporte a pausa (aguarda) e stop (aborta).
                        // Nota: não enviamos zero data aqui — onPauseMission() já desativou
                        // os virtual sticks, pelo que não é necessário (e evitamos SDK timeout).
                        synchronized (lock) {
                            while (missionState == MissionState.PAUSED) {
                                lock.wait();
                            }
                            if (missionState == MissionState.STOPPED) break mission_loop;
                        }

                        JSONObject wp = path.getJSONObject(i);
                        double lat = wp.getDouble("lat");
                        double lng = wp.getDouble("lng");

                        Log.d(TAG, "Missão: A voar para WP " + (i + 1));
                        int result = gpsInputTo(lat, lng);

                        if (result == -1) {
                            Log.e(TAG, "Missão: Erro GPS, abortar.");
                            break mission_loop;
                        }
                    }
                }

                // Fase 3: Ação final
                if (missionState != MissionState.STOPPED) {
                    if ("land".equals(endAction)) {
                        Log.d(TAG, "Missão: A aterrar.");
                        flightController.startLanding(null);
                    } else if ("goHome".equals(endAction)) {
                        Log.d(TAG, "Missão: Go Home.");
                        flightController.startGoHome(null);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Erro na missão: " + e.getMessage());
            } finally {
                // Limpeza: desativar virtual sticks e resetar YawControlMode para
                // ANGULAR_VELOCITY (igual ao estado inicial e ao que o legacy faz em
                // stopMission). Sem este reset, manual sticks ficam em ANGLE mode.
                if (flightController != null) {
                    flightController.setVirtualStickModeEnabled(false, null);
                    flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                }
                isTraveling = false;
                missionState = MissionState.STOPPED;
                Log.d(TAG, "Missão terminada.");
            }
        });
        missionThread.start();
    }

    @Override
    public void onStartRTMP() {
        // RTMP é gerido na View (precisa de UI/Context)
    }

    /**
     * Espera pelo takeoff completar ou timeout de 15s.
     *
     * @param targetAltitude altitude alvo para o takeoff (usa goHome altitude se <= 0)
     * @return {@code true} se o takeoff succeeded, {@code false} se timeout ou erro
     */
    private boolean waitForTakeoff(float targetAltitude) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] succeeded = {false};

        flightController.startTakeoff(djiError -> {
            if (djiError == null) {
                Log.d(TAG, "Takeoff completed successfully.");
            } else {
                Log.e(TAG, "Takeoff failed: " + djiError.getDescription());
            }
            succeeded[0] = djiError == null;
            latch.countDown();
        });

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted while waiting for takeoff.");
            Thread.currentThread().interrupt();
        }
        return succeeded[0];
    }

    //endregion

    //region Virtual Sticks

    /** Inicia o timer para enviar dados de virtual stick a 5Hz (200ms). */
    private void startVirtualStickSending() {
        if (sendVirtualStickDataTimer == null) {
            sendVirtualStickDataTask = new SendVirtualStickDataTask();
            sendVirtualStickDataTimer = new Timer();
            sendVirtualStickDataTimer.schedule(sendVirtualStickDataTask, 0, 200);
        }
    }

    /** Para o timer de envio de virtual sticks e envia valores zero. */
    private void stopVirtualStickSending() {
        if (sendVirtualStickDataTimer != null) {
            sendVirtualStickDataTimer.cancel();
            sendVirtualStickDataTimer = null;
        }
    }

    /**
     * Tarefa timer que envia os dados de virtual stick ao drone.
     *
     * <p>Executa a cada 200ms (5Hz) enquanto o timer estiver ativo.
     */
    private class SendVirtualStickDataTask extends TimerTask {
        @Override
        public void run() {
            if (flightController != null) {
                flightController.sendVirtualStickFlightControlData(
                        new FlightControlData(roll, pitch, yaw, throttle), null);
            }
        }
    }

    //endregion

    //region GPS Navigation

    /**
     * Executa a navegação GPS para as coordenadas fornecidas.
     *
     * <p>Inicia um thread que configura o virtual stick para GPS mode
     * e chama {@link #gpsInputTo(double, double)} para o PID loop.
     *
     * @param targetLatitude latitude alvo
     * @param targetLongitude longitude alvo
     */
    private void executeGpsMove(double targetLatitude, double targetLongitude) {
        new Thread(() -> {
            try {
                isTraveling = true;
                Log.d(TAG, "Navigating to: " + targetLatitude + ", " + targetLongitude);

                flightController.setVirtualStickModeEnabled(true, null);
                flightController.setYawControlMode(YawControlMode.ANGLE);
                flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
                flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);

                gpsInputTo(targetLatitude, targetLongitude);

            } catch (Exception e) {
                Log.e(TAG, "Navigation Error: " + e.getMessage());
            } finally {
                if (flightController != null) {
                    // Reset YawControlMode ANTES do zero send: em ANGLE mode, yaw=0 seria
                    // "vira para Norte". Em ANGULAR_VELOCITY, yaw=0 = parar rotação. Igual ao legacy.
                    flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                    flightController.sendVirtualStickFlightControlData(new FlightControlData(0, 0, 0, 0), null);
                    flightController.setVirtualStickModeEnabled(false, null);
                }
                isTraveling = false;
            }
        }).start();
    }

    /**
     * Navega para as coordenadas alvo usando um PID controller.
     *
     * <p>Algoritmo:
     * <ol>
     *   <li>Calcula a distância atual ao alvo (Haversine)</li>
     *   <li>Se > 3m, calcula yaw e pitch via PID</li>
     *   <li>Envia virtual stick a cada 200ms</li>
     *   <li>Para quando a distância < 2m ou {@code isTraveling == false}</li>
     * </ol>
     *
     * @param targetLatitude latitude alvo
     * @param targetLongitude longitude alvo
     * @return 1 se chegou ao alvo, -1 se erro, 0 se já perto
     */
    private int gpsInputTo(double targetLatitude, double targetLongitude) {
        if (flightController.getState() == null || !flightController.getState().isFlying()) {
            Log.d(TAG, "Drone not flying");
            return 0;
        }

        double curLat = flightController.getState().getAircraftLocation().getLatitude();
        double curLng = flightController.getState().getAircraftLocation().getLongitude();

        // Validação para NaN (estado não inicializado)
        if (Double.isNaN(curLat) || Double.isNaN(curLng)) return -1;

        if (measure(targetLatitude, targetLongitude, curLat, curLng) > 3) {
            PIDController pidController = new PIDController(0.2, 0.0001, 0.2);

            while (measure(targetLatitude, targetLongitude, curLat, curLng) > 2 && isTraveling) {
                double velocity = calculateVelocity();
                float targetYaw = getTargetYaw((float) targetLatitude, (float) targetLongitude, (float) curLat, (float) curLng);

                curLat = flightController.getState().getAircraftLocation().getLatitude();
                curLng = flightController.getState().getAircraftLocation().getLongitude();

                float pidPitch = (float) pidController.calculateThrottle(
                        measure(targetLatitude, targetLongitude, curLat, curLng), velocity);

                flightController.sendVirtualStickFlightControlData(
                        new FlightControlData(0, pidPitch, targetYaw, 0), null);

                try { Thread.sleep(200); } catch (InterruptedException e) {}
            }

            // Yaw final para alinhar com o destino
            float finalYaw = getTargetYaw((float) targetLatitude, (float) targetLongitude, (float) curLat, (float) curLng);
            flightController.sendVirtualStickFlightControlData(new FlightControlData(0, 0, finalYaw, 0), null);

            Log.d(TAG, "Arrived at target");
            return 1;
        }
        return 0;
    }

    /**
     * Executa uma rotação de 360° em yaw para identificação visual.
     *
     * <p>Gira a 30°/s durante ~12s até completar 360°.
     */
    private void perform360Logic() {
        if (!checkController()) return;
        new Thread(() -> {
            try {
                flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                flightController.setVirtualStickModeEnabled(true, null);

                float yawSpeed = 30.0f;
                float totalRotation = 0;

                while (totalRotation < 360) {
                    flightController.sendVirtualStickFlightControlData(new FlightControlData(0, 0, yawSpeed, 0), null);
                    Thread.sleep(100);
                    totalRotation += yawSpeed * 0.1;
                }

                flightController.sendVirtualStickFlightControlData(new FlightControlData(0, 0, 0, 0), null);
                flightController.setVirtualStickModeEnabled(false, null);

            } catch (InterruptedException e) {
                Log.e(TAG, "360 Error");
            }
        }).start();
    }

    //endregion

    //region Math Helpers

    /**
     * Calcula o yaw alvo em graus para ir do ponto atual ao destino.
     *
     * @param targetLat latitude alvo
     * @param targetLng longitude alvo
     * @param currentLat latitude atual
     * @param currentLng longitude atual
     * @return o ângulo em graus (-180 a 180)
     */
    public float getTargetYaw(float targetLat, float targetLng, float currentLat, float currentLng) {
        return (float) Math.toDegrees(Math.atan2(targetLng - currentLng, targetLat - currentLat));
    }

    /**
     * Calcula a distância entre dois pontos GPS usando a fórmula de Haversine.
     *
     * @param lat1 latitude do ponto 1
     * @param lon1 longitude do ponto 1
     * @param lat2 latitude do ponto 2
     * @param lon2 longitude do ponto 2
     * @return a distância em metros
     */
    private double measure(double lat1, double lon1, double lat2, double lon2) {
        double R = 6378.137; // Raio da Terra em km
        double dLat = (lat2 * Math.PI / 180) - (lat1 * Math.PI / 180);
        double dLon = (lon2 * Math.PI / 180) - (lon1 * Math.PI / 180);
        double a = (Math.sin(dLat / 2) * Math.sin(dLat / 2)) +
                (Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon / 2) * Math.sin(dLon / 2));
        double c = 2 * Math.atan2(sqrt(a), sqrt(1 - a));
        return R * c * 1000;
    }

    /**
     * Calcula a velocidade total do drone a partir dos componentes X, Y, Z.
     *
     * @return a velocidade em m/s
     */
    private double calculateVelocity() {
        if (flightController.getState() == null) return 0;
        return sqrt(pow(flightController.getState().getVelocityX(), 2) +
                pow(flightController.getState().getVelocityY(), 2) +
                pow(flightController.getState().getVelocityZ(), 2));
    }

    //endregion

    //region Utils

    /**
     * Verifica se o controlador de voo está disponível, inicializando se necessário.
     *
     * @return {@code true} se o controlador está disponível
     */
    private boolean checkController() {
        if (flightController == null) initFlightController();
        return flightController != null;
    }

    /**
     * Liga o resultado de um comando ao log.
     *
     * @param error o erro retornado pelo SDK (null se sucesso)
     */
    private void handleResult(DJIError error) {
        if (error != null) Log.e(TAG, "Command failed: " + error.getDescription());
    }

    /**
     * Define o gestor de câmara para delegação de comandos.
     *
     * @param cm o CameraManager
     */
    public void setCameraManager(CameraManager cm) {
        this.cameraManager = cm;
    }

    @Override
    public void onSetZoom(float factor) {
        if (cameraManager != null) {
            cameraManager.setZoom(factor);
        } else {
            Log.w(TAG, "CameraManager not initialized");
        }
    }

    @Override
    public void onSetCameraMode(String mode) {
        if (cameraManager != null) {
            cameraManager.setCameraMode(mode);
        } else {
            Log.w(TAG, "CameraManager not initialized");
        }
    }

    @Override
    public void onGimbalAngle(float pitch, float yaw, String mode) {
        // Validar e Clamp dos valores de pitch/yaw
        pitch = Math.max(-90, Math.min(30, pitch));
        yaw = Math.max(-75, Math.min(75, yaw));

        if (gimbal == null) {
            initGimbal();
        }
        if (gimbal == null) {
            Log.w(TAG, "Gimbal not available");
            return;
        }

        RotationMode rotationMode = "relative".equalsIgnoreCase(mode)
                ? RotationMode.RELATIVE_ANGLE
                : RotationMode.ABSOLUTE_ANGLE;

        Rotation rotation = new Rotation.Builder()
                .pitch(pitch)
                .yaw(yaw)
                .mode(rotationMode)
                .time(1.0)
                .build();

        final float finalPitch = pitch;
        final float finalYaw = yaw;
        gimbal.rotate(rotation, new dji.common.util.CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    Log.d(TAG, "Gimbal rotated to pitch=" + finalPitch + " yaw=" + finalYaw + " mode=" + rotationMode);
                } else {
                    Log.e(TAG, "Gimbal rotation failed: " + error.getDescription());
                }
            }
        });
    }

    @Override
    public void onGimbalReset() {
        if (gimbal == null) {
            initGimbal();
        }
        if (gimbal == null) {
            Log.w(TAG, "Gimbal not available");
            return;
        }

        gimbal.reset(new dji.common.util.CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    Log.d(TAG, "Gimbal reset to neutral position");
                } else {
                    Log.e(TAG, "Gimbal reset failed: " + error.getDescription());
                }
            }
        });
    }
    //endregion
}
