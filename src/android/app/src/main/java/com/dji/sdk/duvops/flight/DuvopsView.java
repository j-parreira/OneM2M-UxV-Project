/**
 * {@code DuvopsView} — View principal de controlo de voo e benchmark OneM2M.
 *
 * Orquestra os gestores de voo, telemetria e protocolo OneM2M para
 * a sessão de benchmark. Apresenta:
 * <ul>
 *   <li>Feed de vídeo do drone (H.264 fullscreen)</li>
 *   <li>Barra superior: CSE host, botão Connect/Disconnect, Simulator, Abort</li>
 *   <li>Painel inferior: estado da sessão OneM2M + contador de telemetria</li>
 * </ul>
 *
 * <h3>Fluxo de inicialização</h3>
 * <pre>
 * DuvopsView(context)
 *   → initUI()
 *   → FlightManager + OneM2MSession + NetworkManager
 *   → getSerialNumber() → connectToCse() → OneM2MSession.connect()
 *     → [AE reg → containers → subscription] → startTelemetry()
 * </pre>
 *
 * @author João Parreira
 * @version 3.0
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
import com.dji.sdk.duvops.utils.ModuleVerificationUtil;
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

    /** SharedPreferences — persiste o CSE host entre sessões. */
    private static final String PREFS_NAME    = "duvops_prefs";
    private static final String KEY_SERVER_URL = "server_url";

    /** Host do ACME CSE por defeito (IP do dev machine na LAN). */
    private static final String DEFAULT_CSE_HOST    = "192.168.1.100";

    /** Porto WebSocket do ACME CSE (ver acme.ini → [websocket] port). */
    private static final int    DEFAULT_CSE_WS_PORT = 8180;

    private SharedPreferences prefs;

    // ── UI ───────────────────────────────────────────────────────────────────

    /** Campo de texto para o host do CSE (ex: "192.168.1.100" ou "192.168.1.100:8180"). */
    private EditText hostname;

    /** Botão de ligação/desligação ao CSE (toggle: "Connect CSE" ↔ "Disconnect"). */
    private Button connectws;

    /** Botão para iniciar o simulador DJI com coordenadas de Leiria. */
    private Button startSimulator;

    /** Botão de abort de emergência (System.exit). */
    private Button abort;

    /** Linha de estado da sessão OneM2M (Registering / Ready / Disconnected). */
    private TextView statusField;

    /** Contador de telemetria ("TX: seq=N") e último comando recebido ("CMD: X"). */
    private TextView messageField;

    /** Feed de vídeo H.264 do drone. */
    private VideoFeedView primaryVideoFeedView;

    // ── Managers ─────────────────────────────────────────────────────────────

    /**
     * Sessão OneM2M — referência tipada para acesso a {@link OneM2MSession#setSessionListener}
     * e {@link OneM2MSession#shutdown()}. É também o {@code protocolClient}.
     */
    private OneM2MSession session;

    /** Interface genérica de protocolo — usada por TelemetryManager. */
    private ProtocolClient protocolClient;

    /** Polling de telemetria (250 ms). */
    private TelemetryManager telemetryManager;

    /** Execução de comandos de voo no drone. */
    private FlightManager flightManager;

    /** Controlo de câmara (zoom + modo). */
    private CameraManager cameraManager;

    // ── Estado ───────────────────────────────────────────────────────────────

    /** Serial number do drone (obtido assincronamente pelo DJI SDK). */
    public String serialNumber = "-1";

    /** Modelo do drone (ex: "Mavic 2 Enterprise Advanced"). */
    public String model = "";

    /**
     * {@code true} quando a sessão OneM2M está registada e o botão deve
     * mostrar "Disconnect". Actualizado pelo FlightManager.UiUpdateListener.
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
                    if (isError) {
                        // Desligado ou erro — vermelho, botão volta a "Connect CSE"
                        statusField.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                        sessionReady = false;
                        connectws.setText("Connect CSE");
                    } else if (status.startsWith("Connected")) {
                        // Sessão OneM2M pronta — verde, botão muda para "Disconnect"
                        statusField.setTextColor(getResources().getColor(android.R.color.holo_green_light));
                        sessionReady = true;
                        connectws.setText("Disconnect");
                        if (telemetryManager != null) telemetryManager.startTelemetry();
                    }
                    // Nota: status intermediários (Registering, Creating...) chegam via
                    // SessionListener com cor branca — não alteram o estado do botão.
                });
            }
        });

        // 2. Construir stack: OneM2MSession sobre WebSocket (NetworkManager)
        session   = new OneM2MSession(flightManager);
        NetworkManager transport = new NetworkManager(session);
        session.setTransport(transport);
        protocolClient = session;

        // 2a. SessionListener — actualiza statusField com estados intermédios (branco)
        session.setSessionListener(msg -> post(() -> {
            statusField.setText(msg);
            // Usar branco para estados intermédios; verde/vermelho ficam para os estados finais
            if (statusField.getCurrentTextColor() != getResources().getColor(android.R.color.holo_green_light)
                    && statusField.getCurrentTextColor() != getResources().getColor(android.R.color.holo_red_light)) {
                statusField.setTextColor(getResources().getColor(android.R.color.white));
            }
            // Sobrepor sempre com a mensagem actual
            statusField.setText(msg);
            statusField.setTextColor(getResources().getColor(android.R.color.white));
        }));

        // 2b. CommandLogListener — mostra último comando recebido no messageField (azul)
        protocolClient.setCommandLogListener((command, rawJson) -> post(() -> {
            messageField.setText("CMD: " + command);
            messageField.setTextColor(getResources().getColor(android.R.color.holo_blue_light));
            Log.d(TAG, "Command received: " + command + " " + rawJson);
        }));

        // 3. TelemetryManager — envia telemetria via protocolClient
        telemetryManager = new TelemetryManager(protocolClient, flightManager.getFlightController());

        // 3a. TickListener — mostra contador TX no messageField a cada 1 s (branco)
        telemetryManager.setTickListener(seq -> post(() -> {
            // Só actualizar se não houver uma mensagem de comando recente
            // (o azul indica que um CMD chegou; voltamos a branco após o tick)
            messageField.setText("TX: seq=" + seq);
            messageField.setTextColor(getResources().getColor(android.R.color.white));
        }));

        // 4. CameraManager
        cameraManager = new CameraManager();
        flightManager.setCameraManager(cameraManager);

        // 5. Obter serial number do drone e ligar automaticamente ao CSE
        getSerialNumber();
    }

    // ── UI initialization ────────────────────────────────────────────────────

    /**
     * Infla o layout e liga os elementos da UI.
     *
     * @param context contexto da aplicação
     */
    private void initUI(Context context) {
        setClickable(true);
        setOrientation(HORIZONTAL);
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.view_dboids, this, true);

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Botões
        connectws     = findViewById(R.id.connectws);    connectws.setOnClickListener(this);
        startSimulator = findViewById(R.id.startSimulator); startSimulator.setOnClickListener(this);
        abort          = findViewById(R.id.abort);       abort.setOnClickListener(this);

        // Campos de texto
        statusField  = findViewById(R.id.statusField);
        messageField = findViewById(R.id.messageField);
        hostname     = findViewById(R.id.websocketUrl);

        // Restaurar host do CSE das preferências
        String saved = prefs.getString(KEY_SERVER_URL, null);
        hostname.setText((saved == null || saved.isEmpty()) ? DEFAULT_CSE_HOST : saved);

        initVideoFeed();
    }

    /**
     * Liga a view ao feed de vídeo principal do drone (H.264).
     */
    private void initVideoFeed() {
        primaryVideoFeedView = findViewById(R.id.dboids_primary_videofeed);
        if (VideoFeeder.getInstance() != null && VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
            primaryVideoFeedView.registerLiveVideo(VideoFeeder.getInstance().getPrimaryVideoFeed(), true);
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Liberta todos os recursos: sessão OneM2M, WebSocket e timer de telemetria.
     *
     * <p>Chamado por {@link FlightActivity#onDestroy()} para evitar ghost connections.
     */
    public void cleanup() {
        if (telemetryManager != null) telemetryManager.stopTelemetry();
        if (session != null) session.shutdown();
    }

    // ── Connection ───────────────────────────────────────────────────────────

    /**
     * Obtém o serial number do drone via DJI SDK e liga automaticamente ao CSE.
     *
     * <p>Chamado após o produto DJI estar conectado. Se o drone não estiver
     * disponível, nenhuma ligação é tentada.
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
     * Liga ao ACME CSE usando o host configurado no campo {@code hostname}.
     *
     * <p>Aceita: {@code "192.168.1.100"} ou {@code "192.168.1.100:8180"}.
     * Remove prefixos de protocolo se o utilizador os incluir (ws://, http://).
     */
    private void connectToCse() {
        String input = hostname.getText().toString().trim();
        if (input.isEmpty()) {
            input = DEFAULT_CSE_HOST;
            hostname.setText(DEFAULT_CSE_HOST);
        }
        // Remover prefixo de protocolo
        String host = input.replaceAll("^(ws|wss|http|https)://", "");
        // Separar port do host, se presente
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

    /**
     * Processa cliques nos botões da UI.
     *
     * @param v vista clicada
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {

            case R.id.connectws:
                // Toggle: se sessão activa → desligar; caso contrário → ligar
                if (sessionReady) {
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
                // Inicia o simulador DJI com coordenadas fixas (IPL Leiria)
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
                // Terminar a app de emergência — limpa recursos antes de sair
                cleanup();
                System.exit(0);
                break;

            default:
                break;
        }
    }
}
