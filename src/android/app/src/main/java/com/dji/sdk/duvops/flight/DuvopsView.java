/**
 * {@code DuvopsView} — View principal de controlo de voo do drone.
 *
 * Combina a interface de utilizador com os gestores (NetworkManager, FlightManager,
 * CameraManager, TelemetryManager) para formar o ecrã de controlo do drone.
 *
 * @author João Parreira
 * @version 2.0
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
import dji.sdk.sdkmanager.LiveStreamManager;
import dji.sdk.sdkmanager.LiveVideoBitRateMode;
import dji.sdk.sdkmanager.LiveVideoResolution;

/**
 * View de controlo principal do drone (drone control view).
 *
 * <h3>Componentes</h3>
 * <table>
 *   <tr><th>Componente</th><th>Papel</th></tr>
 *   <tr><td>{@link NetworkManager}</td><td>WebSocket para comunicação com o servidor</td></tr>
 *   <tr><td>{@link FlightManager}</td><td>Comandos de voo (takeoff, land, virtual sticks)</td></tr>
 *   <tr><td>{@link CameraManager}</td><td>Zoom e modo da câmara</td></tr>
 *   <tr><td>{@link TelemetryManager}</td><td>Polling e envio de telemetria ao servidor</td></tr>
 * </table>
 *
 * <h3>Fluxo de inicialização</h3>
 * <pre>
 * DuvopsView(context) → initUI() → [flight, network, telemetry, camera]
 *     → getSerialNumber() → flightManager.initFlightController()
 *     → connectWS() → NetworkManager.connect()
 * </pre>
 */
public class DuvopsView extends LinearLayout implements View.OnClickListener {

    /** Tag para log. */
    private static final String TAG = "DuvopsView";

    /** Chave para SharedPreferences do CSE. */
    private static final String PREFS_NAME = "duvops_prefs";
    private static final String KEY_SERVER_URL = "server_url";

    /** Host do ACME CSE por defeito (IP na LAN local). */
    private static final String DEFAULT_CSE_HOST = "192.168.1.100";

    /** Porto WebSocket do ACME CSE. */
    private static final int DEFAULT_CSE_WS_PORT = 8180;

    /** SharedPreferences para persistir a configuração entre sessões. */
    private SharedPreferences prefs;

    //region UI Elements

    /** Botão de conexão WebSocket. */
    private Button connectws;

    /** Botão de início do stream RTMP. */
    private Button startRTMP;

    /** Botão de início do stream UDP (desativado). */
    private Button startUDP;

    /** Botão de início do simulador. */
    private Button startSimulator;

    /** Botão de abortar (terminar app). */
    private Button abort;

    /** Campo de texto para o IP do servidor WebSocket. */
    private EditText hostname;

    /** Campo de exibição de mensagens de feedback. */
    private TextView messageField;

    /** Campo de exibição do estado de conexão. */
    private TextView statusField;

    /** View de vídeo principal (H.264 decode + display). */
    private VideoFeedView primaryVideoFeedView;

    //endregion

    //region Managers

    /** Cliente de protocolo — OneM2MSession sobre WebSocket. */
    private ProtocolClient protocolClient;

    /** Gestor de telemetria — captura e envia dados ao servidor. */
    private TelemetryManager telemetryManager;

    /** Gestor de voo — executa comandos no drone. */
    private FlightManager flightManager;

    /** Gestor de câmara — zoom e modo. */
    private CameraManager cameraManager;

    //endregion

    /** Serial number do drone (setado por {@link #getSerialNumber()}). */
    public String serialNumber = "-1";

    /** Nome do modelo do drone (ex: "Mavic 2 Enterprise Advanced"). */
    public String model = "";

    /**
     * Cria a view e inicializa todos os gestores.
     *
     * @param context o contexto da activity
     */
    public DuvopsView(@NonNull Context context) {
        super(context);
        initUI(context);

        // 1. Inicializar Flight Manager com callback de atualizações da UI
        flightManager = new FlightManager(new FlightManager.UiUpdateListener() {
            @Override
            public void onStatusUpdate(String status, boolean isError) {
                post(() -> {
                    statusField.setText(status);
                    int color = isError ? android.R.color.holo_red_light : android.R.color.holo_green_light;
                    statusField.setTextColor(getResources().getColor(color));

                    // Iniciar telemetria quando sessão OneM2M estiver pronta
                    if (status.startsWith("Connected") && !isError) {
                        if (telemetryManager != null) telemetryManager.startTelemetry();
                    }
                });
            }
        });

        // 2. Construir stack de comunicação: OneM2MSession sobre WebSocket
        // session: intercepta eventos de conexão para fazer AE registration e despacha comandos
        // transport: camada WebSocket raw (okhttp3)
        OneM2MSession session = new OneM2MSession(flightManager);
        NetworkManager transport = new NetworkManager(session);
        session.setTransport(transport);
        protocolClient = session;

        // 2a. Listener de debug para comandos recebidos (exibidos no messageField)
        protocolClient.setCommandLogListener(new ProtocolClient.CommandLogListener() {
            @Override
            public void onCommandReceived(String command, String rawJson) {
                post(() -> {
                    messageField.setText("CMD: " + command);
                    messageField.setTextColor(getResources().getColor(android.R.color.holo_blue_light));
                    Log.d(TAG, "Received command: " + command + " " + rawJson);
                });
            }
        });

        // 3. Ligar Telemetria ao Flight Controller
        telemetryManager = new TelemetryManager(protocolClient, flightManager.getFlightController());

        // 4. Inicializar Camera Manager
        cameraManager = new CameraManager();
        flightManager.setCameraManager(cameraManager);

        // 5. Obter serial number do drone
        getSerialNumber();
    }

    /**
     * Infla o layout e liga os elementos da UI.
     *
     * @param context o contexto da aplicação
     */
    private void initUI(Context context) {
        setClickable(true);
        setOrientation(HORIZONTAL);
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_dboids, this, true);

        // Inicializar SharedPreferences para persistência entre sessões
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Botões
        connectws = findViewById(R.id.connectws);
        connectws.setOnClickListener(this);
        startRTMP = findViewById(R.id.startRTMP);
        startRTMP.setOnClickListener(this);
        startUDP = findViewById(R.id.startUDP);
        startUDP.setOnClickListener(this);
        startSimulator = findViewById(R.id.startSimulator);
        startSimulator.setOnClickListener(this);
        abort = findViewById(R.id.abort);
        abort.setOnClickListener(this);

        // Campos de texto
        messageField = findViewById(R.id.messageField);
        statusField = findViewById(R.id.statusField);
        hostname = findViewById(R.id.websocketUrl);

        // Carregar host do CSE das preferências ou usar o valor por defeito
        String savedUrl = prefs.getString(KEY_SERVER_URL, null);
        if (savedUrl == null || savedUrl.isEmpty()) {
            savedUrl = DEFAULT_CSE_HOST;
        }
        hostname.setText(savedUrl);

        // Feed de vídeo
        initVideoFeed();
    }

    /**
     * Liga a view ao feed de vídeo principal do drone.
     *
     * <p>O {@link VideoFeedView} decodifica o stream H.264 e exibe o vídeo
     * da câmara do drone em tempo real.
     */
    private void initVideoFeed() {
        primaryVideoFeedView = findViewById(R.id.dboids_primary_videofeed);
        if (VideoFeeder.getInstance() != null && VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
            primaryVideoFeedView.registerLiveVideo(VideoFeeder.getInstance().getPrimaryVideoFeed(), true);
        }
    }

    /**
     * Obtém o serial number do drone e inicializa o controlador de voo.
     *
     * <p>Após obter o serial number, liga automaticamente ao servidor WebSocket.
     */
    public void getSerialNumber() {
        Aircraft aircraft = (Aircraft) App.getProductInstance();
        if (aircraft != null && aircraft.getFlightController() != null) {
            // Atualizar a referência de controlador no Manager
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

                    // Inicializar gimbal após o controlador de voo ficar disponível
                    flightManager.initGimbal();

                    Log.d("DEBUG", "serialNumber: " + s);
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
     * <p>Aceita entradas no formato {@code "192.168.1.100"} ou {@code "192.168.1.100:8180"}.
     * Prefixos de protocolo (ws://, http://) são removidos automaticamente.
     */
    private void connectToCse() {
        String input = hostname.getText().toString().trim();
        if (input.isEmpty()) {
            input = DEFAULT_CSE_HOST;
            hostname.setText(DEFAULT_CSE_HOST);
        }
        // Remover prefixo de protocolo se o utilizador o tiver escrito
        String host = input.replaceAll("^(ws|wss|http|https)://", "");
        // Separar port do host, se presente (ex: "192.168.1.100:8180")
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

    /**
     * Processa cliques nos botões da UI.
     *
     * @param v a vista clicada
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.connectws:
                connectToCse();
                break;

            case R.id.startSimulator:
                // Inicia o simulador com coordenadas fixas de teste
                if (flightManager.getFlightController() != null) {
                    flightManager.getFlightController().getSimulator().start(
                            dji.common.flightcontroller.simulator.InitializationData.createInstance(
                                    new dji.common.model.LocationCoordinate2D(39.933219, -8.892509), 10, 10),
                            djiError -> ToastUtils.setResultToToast(
                                    djiError != null ? djiError.getDescription() : "Simulator started"));
                }
                break;

            case R.id.abort:
                // Termina a aplicação — usar apenas em emergência
                System.exit(0);
                break;

            case R.id.startRTMP:
                startRTMPStream();
                break;

            default:
                break;
        }
    }

    /**
     * Inicia o stream RTMP para streaming de vídeo ao vivo.
     *
     * <p>Configura:
     * <ul>
     *   <li>URL: {@code rtmp://<server>:1935/<serialNumber>}</li>
     *   <li>Resolução: 1080p</li>
     *   <li>Bitrate: auto</li>
     *   <li>Áudio: desativado</li>
     * </ul>
     */
    public void startRTMPStream() {
        if (DJISDKManager.getInstance().getLiveStreamManager() == null) return;

        LiveStreamManager streamManager = DJISDKManager.getInstance().getLiveStreamManager();
        if (streamManager.isStreaming()) streamManager.stopStream();

        new Thread(() -> {
            streamManager.setLiveUrl("rtmp://" + hostname.getText().toString() + ":1935/" + serialNumber);
            streamManager.setAudioStreamingEnabled(false);
            streamManager.setLiveVideoResolution(LiveVideoResolution.VIDEO_RESOLUTION_1920_1080);
            streamManager.setLiveVideoBitRateMode(LiveVideoBitRateMode.AUTO);
            streamManager.setLiveVideoBitRate(1.5f * 1024);
            streamManager.setStartTime();
            int result = streamManager.startStream();
            Log.d(TAG, "startLive Result:" + result);
        }).start();
    }
}
