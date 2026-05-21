/**
 * {@code DuvopsView} — View principal de controlo de voo e benchmark OneM2M.
 *
 * Orquestra os gestores de voo, telemetria e protocolo OneM2M para
 * a sessão de benchmark. Apresenta:
 * <ul>
 *   <li>Feed de vídeo do drone (H.264 fullscreen)</li>
 *   <li>Barra superior: CSE host, botão Connect/Disconnect, Simulator, Abort</li>
 *   <li>Painel inferior: estado da sessão OneM2M, contador de telemetria, estado do drone</li>
 * </ul>
 *
 * <h3>Fluxo de inicialização</h3>
 * <pre>
 * DuvopsView(context)
 *   → initUI()
 *   → FlightManager + OneM2MSession + NetworkManager
 *   → getSerialNumber() → connectToCse() → OneM2MSession.connect()
 *     → [AE reg → containers → subscription → ack container] → startTelemetry()
 * </pre>
 *
 * <h3>Comandos de controlo de benchmark recebidos via OneM2M</h3>
 * <ul>
 *   <li>{@code setTelemetryRate} — altera o intervalo do timer de telemetria</li>
 *   <li>Todos os outros comandos de voo passam pelo {@link FlightManager}</li>
 * </ul>
 *
 * @author João Parreira
 * @version 4.0
 */
package com.dji.sdk.duvops.flight;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.dji.sdk.duvops.R;
import com.dji.sdk.duvops.app.App;
import com.dji.sdk.duvops.network.NetworkManager;
import com.dji.sdk.duvops.network.OneM2MSession;
import com.dji.sdk.duvops.network.ProtocolClient;
import com.dji.sdk.duvops.utils.ToastUtils;
import com.dji.sdk.duvops.utils.VideoFeedView;

import dji.common.error.DJIError;
import dji.common.util.CommonCallbacks;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * View de controlo de voo e benchmark — ecrã principal da app.
 */
public class DuvopsView extends LinearLayout implements View.OnClickListener {

    private static final String TAG = "DuvopsView";

    private static final String PREFS_NAME     = "duvops_prefs";
    private static final String KEY_SERVER_URL  = "server_url";
    private static final String DEFAULT_CSE_HOST    = "192.168.1.100";
    private static final int    DEFAULT_CSE_WS_PORT = 8180;

    private SharedPreferences prefs;

    // ── UI ───────────────────────────────────────────────────────────────────

    /** Campo de texto para o host do CSE (ex: "192.168.1.100" ou "192.168.1.100:8180"). */
    private EditText hostname;

    /** Toggle: "Connect CSE" quando desligado, "Disconnect" quando sessão activa. */
    private Button connectws;

    /** Inicia o simulador DJI com coordenadas fixas (IPL Leiria). */
    private Button startSimulator;

    /** Abort de emergência — chama cleanup() antes de System.exit(). */
    private Button abort;

    /**
     * Estado da sessão OneM2M.
     * Branco durante inicialização (SessionListener), verde quando pronto,
     * vermelho em erro (FlightManager.UiUpdateListener).
     */
    private TextView statusField;

    /**
     * Contador de telemetria ("TX: seq=N") actualizado a cada ~1 s pelo TelemetryTickListener.
     * Sobrescrito momentaneamente por "CMD: X" quando um comando chega.
     */
    private TextView messageField;

    /**
     * Estado do drone: bateria, voo, satélites.
     * Actualizado em simultâneo com messageField pelo TelemetryTickListener.
     */
    private TextView droneStateField;

    /** Feed de vídeo H.264 do drone. */
    private VideoFeedView primaryVideoFeedView;

    // ── Managers ─────────────────────────────────────────────────────────────

    /**
     * Referência tipada à sessão OneM2M para acesso a
     * {@link OneM2MSession#setSessionListener}, {@link OneM2MSession#setTelemetryRateListener}
     * e {@link OneM2MSession#shutdown()}.
     */
    private OneM2MSession session;

    /** Interface genérica — usada por TelemetryManager. */
    private ProtocolClient protocolClient;

    /** Timer de telemetria (250 ms por defeito, configurável via setTelemetryRate). */
    private TelemetryManager telemetryManager;

    /** Executor de comandos de voo. */
    private FlightManager flightManager;

    /** Controlo de câmara (zoom + modo). */
    private CameraManager cameraManager;

    // ── Estado ───────────────────────────────────────────────────────────────

    /** Serial number do drone (obtido assincronamente). */
    public String serialNumber = "-1";

    /** Modelo do drone. */
    public String model = "";

    /**
     * {@code true} quando a sessão OneM2M está registada e o timer de telemetria está activo.
     * Controla o comportamento do botão Connect/Disconnect.
     */
    private boolean sessionReady = false;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cria a view e inicializa todos os gestores.
     *
     * @param context contexto da activity
     */
    public DuvopsView(@NonNull Context context) {
        super(context);
        initUI(context);

        // 1. FlightManager — recebe comandos de voo e actualiza a UI
        flightManager = new FlightManager(new FlightManager.UiUpdateListener() {
            @Override
            public void onStatusUpdate(String status, boolean isError) {
                post(() -> {
                    // Actualizar sempre o texto e a cor
                    statusField.setText(status);
                    if (isError) {
                        statusField.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                        sessionReady = false;
                        connectws.setText("Connect CSE");
                    } else if (status.startsWith("Connected")) {
                        statusField.setTextColor(getResources().getColor(android.R.color.holo_green_light));
                        sessionReady = true;
                        connectws.setText("Disconnect");
                        if (telemetryManager != null) telemetryManager.startTelemetry();
                    }
                });
            }
        });

        // 2. Stack: OneM2MSession → NetworkManager (WebSocket)
        session   = new OneM2MSession(flightManager);
        NetworkManager transport = new NetworkManager(session);
        session.setTransport(transport);
        protocolClient = session;

        // 2a. SessionListener — actualiza statusField com estados intermédios (branco)
        session.setSessionListener(msg -> post(() -> {
            statusField.setText(msg);
            statusField.setTextColor(getResources().getColor(android.R.color.white));
        }));

        // 2b. TelemetryRateListener — recebe setTelemetryRate commands e repassa ao timer
        session.setTelemetryRateListener(intervalMs -> {
            if (telemetryManager != null) telemetryManager.setRate(intervalMs);
            post(() -> {
                int rate = Math.max(1, 1000 / intervalMs);
                statusField.setText("Telemetry rate: " + rate + " msg/s (" + intervalMs + " ms)");
                statusField.setTextColor(getResources().getColor(android.R.color.white));
            });
        });

        // 2c. CommandLogListener — "CMD: X" no messageField (azul)
        protocolClient.setCommandLogListener((command, rawJson) -> post(() -> {
            messageField.setText("CMD: " + command);
            messageField.setTextColor(getResources().getColor(android.R.color.holo_blue_light));
            Log.d(TAG, "Command received: " + command + " " + rawJson);
        }));

        // 3. TelemetryManager
        telemetryManager = new TelemetryManager(protocolClient, flightManager.getFlightController());

        // 3a. TelemetryTickListener — actualiza TX counter e estado do drone a cada ~1 s
        telemetryManager.setTickListener((seq, bat, isFlying, sats) -> post(() -> {
            // Repor cor branca (o CMD pode ter posto azul)
            messageField.setText("TX: seq=" + seq);
            messageField.setTextColor(getResources().getColor(android.R.color.white));
            // Estado do drone: bateria, voo, satélites
            String batStr  = bat >= 0 ? bat + "%" : "?%";
            String flyStr  = isFlying ? "Flying" : "Ground";
            String satStr  = sats + " sats";
            droneStateField.setText("Bat: " + batStr + " | " + flyStr + " | " + satStr);
        }));

        // 4. CameraManager
        cameraManager = new CameraManager();
        flightManager.setCameraManager(cameraManager);

        // 5. Obter serial number e ligar automaticamente ao CSE
        getSerialNumber();
    }

    // ── UI initialization ────────────────────────────────────────────────────

    private void initUI(Context context) {
        setClickable(true);
        setOrientation(HORIZONTAL);
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.view_dboids, this, true);

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        connectws      = findViewById(R.id.connectws);     connectws.setOnClickListener(this);
        startSimulator = findViewById(R.id.startSimulator); startSimulator.setOnClickListener(this);
        abort          = findViewById(R.id.abort);          abort.setOnClickListener(this);

        statusField    = findViewById(R.id.statusField);
        messageField   = findViewById(R.id.messageField);
        droneStateField = findViewById(R.id.droneStateField);
        hostname       = findViewById(R.id.websocketUrl);

        String saved = prefs.getString(KEY_SERVER_URL, null);
        hostname.setText((saved == null || saved.isEmpty()) ? DEFAULT_CSE_HOST : saved);

        initVideoFeed();
    }

    private void initVideoFeed() {
        primaryVideoFeedView = findViewById(R.id.dboids_primary_videofeed);
        if (VideoFeeder.getInstance() != null && VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
            primaryVideoFeedView.registerLiveVideo(VideoFeeder.getInstance().getPrimaryVideoFeed(), true);
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Liberta todos os recursos: sessão OneM2M, WebSocket e timer de telemetria.
     * Chamado por {@link FlightActivity#onDestroy()}.
     */
    public void cleanup() {
        sessionReady = false;
        if (telemetryManager != null) telemetryManager.stopTelemetry();
        if (session != null) session.shutdown();
    }

    // ── Connection ───────────────────────────────────────────────────────────

    /**
     * Obtém o serial number do drone e liga automaticamente ao CSE.
     */
    public void getSerialNumber() {
        Aircraft aircraft = (Aircraft) App.getProductInstance();
        if (aircraft != null && aircraft.getFlightController() != null) {
            flightManager.initFlightController();
            if (telemetryManager != null) {
                telemetryManager.setFlightController(flightManager.getFlightController());
            }
            aircraft.getFlightController().getSerialNumber(new CommonCallbacks.CompletionCallbackWith<String>() {
                @Override
                public void onSuccess(String s) {
                    serialNumber = s;
                    model = DJISDKManager.getInstance().getProduct().getModel().getDisplayName();
                    if (telemetryManager != null) telemetryManager.setModelName(model);
                    flightManager.initGimbal();
                    Log.d(TAG, "serialNumber: " + s);
                    connectToCse();
                }
                @Override
                public void onFailure(DJIError djiError) {
                    ToastUtils.setResultToToast("getSerialNumber failed: " + djiError.getDescription());
                }
            });
        }
    }

    /**
     * Liga ao ACME CSE com o host configurado no campo {@code hostname}.
     *
     * <p>Reset do estado de sessão antes de iniciar nova ligação — evita
     * botão inconsistente durante transições.
     * Aceita: {@code "192.168.1.100"} ou {@code "192.168.1.100:8180"}.
     */
    private void connectToCse() {
        // Reset do estado anterior para evitar botão inconsistente
        sessionReady = false;
        connectws.setText("Connect CSE");

        String input = hostname.getText().toString().trim();
        if (input.isEmpty()) {
            input = DEFAULT_CSE_HOST;
            hostname.setText(DEFAULT_CSE_HOST);
        }
        String host = input.replaceAll("^(ws|wss|http|https)://", "");
        int port = DEFAULT_CSE_WS_PORT;
        int colonIdx = host.lastIndexOf(':');
        if (colonIdx > 0) {
            try {
                port = Integer.parseInt(host.substring(colonIdx + 1));
                host = host.substring(0, colonIdx);
            } catch (NumberFormatException ignored) {}
        }
        prefs.edit().putString(KEY_SERVER_URL, host).apply();
        protocolClient.connect(host, port, serialNumber);
    }

    // ── Click handling ───────────────────────────────────────────────────────

    @Override
    public void onClick(View v) {
        switch (v.getId()) {

            case R.id.connectws:
                if (sessionReady) {
                    // Desligar explicitamente — cancela reconnect automático
                    sessionReady = false;
                    connectws.setText("Connect CSE");
                    if (telemetryManager != null) telemetryManager.stopTelemetry();
                    protocolClient.disconnect();
                    statusField.setText("Disconnected by user.");
                    statusField.setTextColor(getResources().getColor(android.R.color.white));
                } else {
                    connectToCse();
                }
                break;

            case R.id.startSimulator:
                if (flightManager.getFlightController() != null) {
                    flightManager.getFlightController().getSimulator().start(
                            dji.common.flightcontroller.simulator.InitializationData.createInstance(
                                    new dji.common.model.LocationCoordinate2D(39.933219, -8.892509),
                                    10, 10),
                            djiError -> ToastUtils.setResultToToast(
                                    djiError != null ? djiError.getDescription() : "Simulator started"));
                }
                break;

            case R.id.abort:
                cleanup();
                System.exit(0);
                break;

            default:
                break;
        }
    }
}
