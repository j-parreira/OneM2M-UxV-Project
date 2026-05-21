/**
 * {@code OneM2MSession} — Camada de protocolo OneM2M sobre um transporte {@link ProtocolClient}.
 *
 * <p>Gere o ciclo de vida completo de uma sessão AE (Application Entity):
 * <ol>
 *   <li>Registo do AE ({@code m2m:ae}) no CSE</li>
 *   <li>Criação dos containers {@code telemetry} e {@code commands}</li>
 *   <li>Criação de subscrição no container {@code commands}</li>
 *   <li>Envio de telemetria como {@code m2m:cin} (fire-and-forget)</li>
 *   <li>Recepção e despacho de comandos via notificações {@code m2m:sgn}</li>
 *   <li>Reconnect automático com exponential backoff em caso de falha</li>
 *   <li>Timeout por request para evitar sessão suspensa</li>
 * </ol>
 *
 * <h3>Stack de comunicação</h3>
 * <pre>
 * DuvopsView / TelemetryManager
 *     → OneM2MSession  (implements ProtocolClient — protocolo OneM2M)
 *         → NetworkManager  (WebSocket raw — implementa ProtocolClient)
 *             → SocketListener
 * </pre>
 *
 * <h3>Sequência de inicialização (ACME CSE v2025.11)</h3>
 * <pre>
 * connect() → transport.connect() (ws://host:8180, subprotocol "oneM2M.json")
 *   → WebSocket abre → onConnectionStatusChange(true)
 *   → registerAE()               [to="id-in",     ty=2, no aei field]
 *   → createTelemetryContainer() [to="cse-in/uxv", ty=3, rn=telemetry]
 *   → createCommandsContainer()  [to="cse-in/uxv", ty=3, rn=commands]
 *   → createSubscription()       [to="cse-in/uxv/commands", ty=23, nu=aeOriginator]
 *   → createAckContainer()       [to="cse-in/uxv", ty=3, rn=ack]
 *   → onSessionReady()           → commandListener.onConnectionStatusChange(true, ...)
 * </pre>
 *
 * <h3>Formato de requests (ACME CSE v2025.11)</h3>
 * <p>JSON flat sem wrapper {@code m2m:rqp}. Campo {@code rvi="3"} obrigatório.
 * <p>Respostas: campo {@code rsc} no top-level (sem wrapper {@code m2m:rsp}).
 * <p>ACKs de notificação: JSON flat com {@code rsc=2000} (sem wrapper {@code m2m:rsp}).
 *
 * <h3>Reconnect backoff</h3>
 * <p>Em caso de falha (WebSocket ou erro OneM2M), retenta após 1 s → 2 s → 4 s → ... → 30 s.
 * O backoff reinicia quando a sessão fica {@code ready}.
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.network;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sessão OneM2M com registo de AE, gestão de containers, subscrição,
 * timeout de requests e reconnect automático.
 */
public class OneM2MSession implements ProtocolClient, DroneCommandListener {

    private static final String TAG = "OneM2MSession";

    // ── OneM2M resource paths ────────────────────────────────────────────────

    /**
     * Identificador do CSE (CSE-ID, sem leading slash) — alvo do registo AE.
     *
     * <p>Corresponde ao {@code cseID} em {@code acme.ini}.
     * Usado APENAS no {@code to} do request de registo do AE.
     * Todos os outros recursos usam {@link #CSE_BASE} (CSE-Base resource name).
     */
    private static final String CSE_ID   = "id-in";

    /**
     * Resource name do CSE-Base — prefixo de todos os caminhos de recursos.
     *
     * <p>Corresponde ao {@code cseName} em {@code acme.ini}.
     * Em ACME CSE v2025.11, os recursos são acessíveis em:
     * {@code /{cseName}/{ae-rn}/{container-rn}/...} = {@code /cse-in/uxv/telemetry/...}
     *
     * <p>Este valor diverge do CSE-ID ({@code /id-in}) — são dois conceitos distintos:
     * o CSE-ID é usado para identificar o CSE na rede oneM2M; o CSE-Base resource name
     * é o path HTTP/WS onde residem os recursos filho.
     */
    private static final String CSE_BASE = "cse-in";

    /** Nome do recurso AE. */
    private static final String AE_NAME  = "uxv";

    /** Application ID do AE (campo {@code api}). */
    private static final String AE_API   = "N.com.uxv.onem2m";

    // ── OneM2M operation codes ───────────────────────────────────────────────
    private static final int OP_CREATE = 1;
    private static final int OP_NOTIFY = 5;

    // ── OneM2M resource type codes ───────────────────────────────────────────
    private static final int TY_AE  = 2;
    private static final int TY_CNT = 3;
    private static final int TY_CIN = 4;
    private static final int TY_SUB = 23;

    // ── OneM2M response status codes ─────────────────────────────────────────
    private static final int RSC_OK       = 2000;
    private static final int RSC_CREATED  = 2001;
    /** Recurso já existe — tratado como sucesso para permitir reconnect. */
    private static final int RSC_CONFLICT = 4105;

    // ── Tempo de timeout por request (segundos) ───────────────────────────────
    private static final int REQUEST_TIMEOUT_S    = 10;

    // ── Reconnect backoff ────────────────────────────────────────────────────
    private static final int MAX_RECONNECT_DELAY_S = 30;

    // ── Dependências ─────────────────────────────────────────────────────────

    /** Transporte raw (WebSocket). Injectado via {@link #setTransport}. */
    private NetworkManager transport;

    /** Executor de comandos de voo (FlightManager). */
    private final DroneCommandListener commandListener;

    /** Listener de debug para comandos recebidos. */
    private ProtocolClient.CommandLogListener commandLogListener;

    // ── Estado da sessão ─────────────────────────────────────────────────────

    /**
     * Callback de estado intermédio da sessão — actualiza a UI durante
     * a sequência de registo e nos eventos de reconnect.
     */
    public interface SessionListener {
        /**
         * Chamado em cada mudança de estado da sessão.
         *
         * @param message mensagem descritiva do estado actual
         */
        void onSessionStatus(String message);
    }

    /** Listener de estado (normalmente DuvopsView.statusField). */
    private SessionListener sessionListener;

    /**
     * Callback para comandos de controlo de benchmark que não são comandos de voo.
     *
     * <p>Actualmente usado para {@code setTelemetryRate} — redireccionado para
     * {@link com.dji.sdk.duvops.flight.TelemetryManager#setRate(int)} via {@code DuvopsView}.
     */
    public interface TelemetryRateListener {
        /**
         * @param intervalMs novo intervalo de envio de telemetria em ms
         */
        void onSetTelemetryRate(int intervalMs);
    }

    /** Listener de taxa de telemetria (normalmente DuvopsView → TelemetryManager). */
    private TelemetryRateListener telemetryRateListener;

    /** Originator do AE: {@code "C" + serialNumber} (limpo, ≤ 32 chars). */
    private String aeOriginator;

    /** {@code true} quando a sessão está registada e pronta a enviar telemetria. */
    private volatile boolean ready = false;

    /**
     * {@code true} quando o utilizador chamou {@link #connect} e não chamou
     * {@link #disconnect}. Controla o loop de reconnect.
     */
    private volatile boolean shouldReconnect = false;

    // ── Reconnect state ──────────────────────────────────────────────────────

    /** Delay actual do backoff (dobra a cada falha, máx. {@value MAX_RECONNECT_DELAY_S} s). */
    private int reconnectDelayS = 1;

    /** Parâmetros guardados para reconectar sem input do utilizador. */
    private String savedHost;
    private int savedPort;
    private String savedAeId;

    // ── Request tracking ─────────────────────────────────────────────────────

    /** Gerador de request IDs únicos (thread-safe). */
    private final AtomicInteger rqiCounter = new AtomicInteger(0);

    /**
     * Pedidos pendentes: {@code rqi → callback a executar em caso de sucesso}.
     * Thread-safe — respostas chegam no thread do OkHttp.
     */
    private final ConcurrentHashMap<String, Runnable> pending = new ConcurrentHashMap<>();

    /**
     * Futuros de timeout por request. Cancelados quando a resposta chega.
     */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();

    /**
     * Scheduler partilhado para timeouts de request e delays de reconnect.
     * Thread único — evita races entre os dois tipos de runnables.
     */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "onem2m-scheduler");
                t.setDaemon(true);
                return t;
            });

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cria a sessão OneM2M.
     *
     * @param commandListener receptor dos comandos de voo (normalmente {@code FlightManager})
     */
    public OneM2MSession(DroneCommandListener commandListener) {
        this.commandListener = commandListener;
    }

    /**
     * Define o transporte WebSocket e regista-se como receptor de mensagens raw.
     *
     * <p>Deve ser chamado imediatamente após a criação, antes de {@link #connect}.
     *
     * @param transport instância de {@link NetworkManager} criada com {@code this} como listener
     */
    public void setTransport(NetworkManager transport) {
        this.transport = transport;
        transport.setRawMessageListener(this::onRawMessage);
    }

    /**
     * Define o listener de taxa de telemetria.
     *
     * @param listener listener a chamar quando o comando {@code setTelemetryRate} é recebido
     */
    public void setTelemetryRateListener(TelemetryRateListener listener) {
        this.telemetryRateListener = listener;
    }

    /**
     * Define o listener de estado intermédio da sessão.
     *
     * <p>Chamado durante a sequência de registo e em eventos de reconnect.
     * Normalmente ligado ao {@code statusField} da UI.
     *
     * @param listener listener a chamar (pode ser {@code null})
     */
    public void setSessionListener(SessionListener listener) {
        this.sessionListener = listener;
    }

    // ── ProtocolClient ───────────────────────────────────────────────────────

    /**
     * Liga ao CSE. Guarda os parâmetros para reconnect automático.
     *
     * @param host  IP ou hostname do CSE
     * @param port  porto WebSocket (normalmente 8180)
     * @param aeId  serial number do drone (base para o originator OneM2M)
     */
    @Override
    public void connect(String host, int port, String aeId) {
        shouldReconnect = true;
        reconnectDelayS = 1;
        savedHost = host;
        savedPort = port;
        savedAeId = aeId;
        ready = false;
        pending.clear();
        cancelAllTimeouts();

        // Originator deve começar com "C" (oneM2M spec)
        String cleaned = aeId.replaceAll("[^a-zA-Z0-9]", "");
        aeOriginator = "C" + (cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned);

        notifyStatus("Connecting to ws://" + host + ":" + port + "...");
        // Pass aeOriginator (not raw aeId) so NetworkManager sends the correct
        // X-M2M-Origin header in the WebSocket upgrade request.
        transport.connect(host, port, aeOriginator);
    }

    /**
     * Desliga explicitamente e cancela o reconnect automático.
     */
    @Override
    public void disconnect() {
        shouldReconnect = false;
        ready = false;
        pending.clear();
        cancelAllTimeouts();
        transport.disconnect();
        notifyStatus("Disconnected.");
    }

    /**
     * Envia payload de telemetria como {@code m2m:cin} no container {@code telemetry}.
     *
     * <p>Fire-and-forget — sem aguardar ACK. Silenciado se a sessão não estiver pronta.
     *
     * @param jsonPayload JSON de telemetria (gerado pelo {@code TelemetryManager})
     */
    @Override
    public void sendTelemetry(String jsonPayload) {
        if (!ready) return;
        try {
            // cnf omitido — "application/json" falha validação em ACME CSE v2025.11
            JSONObject pc = new JSONObject()
                    .put("m2m:cin", new JSONObject()
                            .put("con", jsonPayload));
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME + "/telemetry", TY_CIN, pc, null);
        } catch (JSONException e) {
            Log.e(TAG, "sendTelemetry: " + e.getMessage());
        }
    }

    /**
     * @return {@code true} se a sessão OneM2M está registada e pronta
     */
    @Override
    public boolean isConnected() {
        return ready;
    }

    /** {@inheritDoc} */
    @Override
    public void setCommandLogListener(ProtocolClient.CommandLogListener listener) {
        this.commandLogListener = listener;
    }

    /**
     * Liberta todos os recursos: cancela o reconnect, para o scheduler.
     *
     * <p>Deve ser chamado em {@code FlightActivity.onDestroy()} via {@code DuvopsView.cleanup()}.
     */
    public void shutdown() {
        shouldReconnect = false;
        ready = false;
        pending.clear();
        cancelAllTimeouts();
        if (!scheduler.isShutdown()) scheduler.shutdownNow();
        if (transport != null) transport.disconnect();
    }

    // ── DroneCommandListener ─────────────────────────────────────────────────

    /**
     * Chamado pelo {@link NetworkManager} quando o WebSocket abre ou fecha.
     *
     * <ul>
     *   <li>Ligado → inicia sequência de registo AE.</li>
     *   <li>Desligado → notifica UI; agenda reconnect se {@link #shouldReconnect}.</li>
     * </ul>
     */
    @Override
    public void onConnectionStatusChange(boolean isConnected, String message) {
        if (isConnected) {
            reconnectDelayS = 1; // reset backoff
            registerAE();
        } else {
            ready = false;
            commandListener.onConnectionStatusChange(false, message);
            if (shouldReconnect) scheduleReconnect();
        }
    }

    // ── Handlers de mensagens raw ────────────────────────────────────────────

    /**
     * Processa mensagens JSON recebidas do CSE (formato flat — ACME CSE v2025.11).
     *
     * <p>Formato flat: sem wrappers {@code m2m:rsp} ou {@code m2m:rqp}. O tipo
     * de mensagem é determinado pela presença dos campos {@code rsc} (resposta)
     * ou {@code op} (request/notificação).
     *
     * <ul>
     *   <li>Resposta: campo {@code rsc} no topo → {@link #handleResponse}</li>
     *   <li>Notificação: campo {@code op=5} no topo → {@link #handleNotification} + ACK</li>
     * </ul>
     */
    private void onRawMessage(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            // Flat format: response has "rsc" at top level
            if (obj.has("rsc")) {
                handleResponse(obj);
            // Flat format: notification has "op": 5 at top level
            } else if (obj.optInt("op", 0) == OP_NOTIFY) {
                handleNotification(obj);
                sendNotifyAck(obj.optString("rqi", ""));
            }
        } catch (JSONException e) {
            Log.e(TAG, "onRawMessage: " + e.getMessage());
        }
    }

    /**
     * Processa uma resposta do CSE.
     *
     * <p>Cancela o timeout correspondente e executa o callback de sucesso,
     * ou chama {@link #handleRegistrationFailure} em caso de erro.
     */
    private void handleResponse(JSONObject rsp) throws JSONException {
        String rqi = rsp.optString("rqi", "");
        int rsc    = rsp.optInt("rsc", 0);

        ScheduledFuture<?> timeoutFuture = timeouts.remove(rqi);
        if (timeoutFuture != null) timeoutFuture.cancel(false);

        Runnable callback = pending.remove(rqi);
        boolean ok = (rsc == RSC_CREATED || rsc == RSC_OK || rsc == RSC_CONFLICT);

        if (callback != null && ok) {
            callback.run();
        } else if (callback != null) {
            Log.e(TAG, "Request failed rsc=" + rsc + " rqi=" + rqi);
            handleRegistrationFailure("OneM2M error rsc=" + rsc);
        }
        // callback==null → fire-and-forget (telemetria CIN) — ignorar
    }

    /**
     * Extrai e despacha o comando JSON contido numa notificação do CSE.
     *
     * <p>Formato esperado: {@code rqp.pc.m2m:sgn.nev.rep.m2m:cin.con = "{\"command\": ...}"}.
     */
    private void handleNotification(JSONObject rqp) throws JSONException {
        JSONObject pc  = rqp.optJSONObject("pc");    if (pc  == null) return;
        JSONObject sgn = pc.optJSONObject("m2m:sgn"); if (sgn == null) return;
        JSONObject nev = sgn.optJSONObject("nev");    if (nev == null) return;
        JSONObject rep = nev.optJSONObject("rep");    if (rep == null) return;
        JSONObject cin = rep.optJSONObject("m2m:cin");if (cin == null) return;
        String con = cin.optString("con", "");
        if (!con.isEmpty()) dispatchCommand(con);
    }

    /**
     * Envia ACK ao CSE para a notificação recebida (obrigatório para evitar reenvio).
     *
     * <p>Formato flat (sem wrapper {@code m2m:rsp}) — ACME CSE v2025.11.
     */
    private void sendNotifyAck(String rqi) {
        try {
            // Flat ACK response — no m2m:rsp wrapper
            JSONObject ack = new JSONObject()
                    .put("rsc", RSC_OK)
                    .put("rqi", rqi)
                    .put("to",  aeOriginator)
                    .put("fr",  aeOriginator);
            transport.sendTelemetry(ack.toString());
        } catch (JSONException e) {
            Log.e(TAG, "sendNotifyAck: " + e.getMessage());
        }
    }

    /**
     * Parseia e despacha um JSON de comando ao {@code commandListener}.
     *
     * <p>Regista {@code t_recv_ms} antes do switch e envia um ACK CIN a
     * {@code cse-in/uxv/ack} após dispatch para medição de latência (Cenário 2).
     * O Streamlit deve incluir {@code t_cmd_ms} e {@code seq_cmd} no CIN de comando.
     *
     * @param commandJson JSON do campo {@code con} do CIN recebido
     */
    private void dispatchCommand(String commandJson) {
        // Registar timestamp de recepção antes de qualquer processamento
        final long tRecvMs = System.currentTimeMillis();
        try {
            JSONObject data = new JSONObject(commandJson);
            if (!data.has("command")) return;
            String command = data.getString("command");
            if (commandLogListener != null) commandLogListener.onCommandReceived(command, commandJson);
            switch (command) {
                case "takeoff":    commandListener.onTakeOff(); break;
                case "land":       commandListener.onLand(); break;
                case "motors":
                    commandListener.onMotors(data.optBoolean("state", false)); break;
                case "startGoHome": commandListener.onGoHome(); break;
                case "virtualSticks":
                    commandListener.onVirtualStickState(data.optBoolean("state", false)); break;
                case "virtualSticksInput":
                    commandListener.onVirtualStickInput(
                            (float) data.optDouble("roll",     0),
                            (float) data.optDouble("pitch",    0),
                            (float) data.optDouble("yaw",      0),
                            (float) data.optDouble("throttle", 0)); break;
                case "gpsInput":
                case "gpsInput360Mapping":
                    if (data.has("lat") && data.has("lng"))
                        commandListener.onMoveTo(data.getDouble("lat"), data.getDouble("lng"));
                    break;
                case "perform360":  commandListener.onPerform360(); break;
                case "identify":
                    commandListener.onIdentify(data.optBoolean("state", false)); break;
                case "startMission":
                    commandListener.onStartMission(
                            data.optString("startAction"),
                            data.optString("endAction"),
                            data.optInt("repeat", 0),
                            (float) data.optDouble("altitude", 0),
                            data.has("path") ? data.getJSONArray("path").toString() : null); break;
                case "stopMission":  commandListener.onStopMission(); break;
                case "pauseMission": commandListener.onPauseMission(); break;
                case "setZoom":
                    commandListener.onSetZoom((float) data.optDouble("factor", 1.0)); break;
                case "setCameraMode":
                    commandListener.onSetCameraMode(data.optString("mode", "RGB")); break;
                case "gimbalAngle":
                    commandListener.onGimbalAngle(
                            (float) data.optDouble("pitch", 0),
                            (float) data.optDouble("yaw",   0),
                            data.optString("mode", "absolute")); break;
                case "gimbalReset":  commandListener.onGimbalReset(); break;
                case "setTelemetryRate":
                    // Comando de controlo de benchmark — não é um comando de voo
                    int newIntervalMs = data.optInt("intervalMs", 250);
                    if (telemetryRateListener != null) {
                        telemetryRateListener.onSetTelemetryRate(newIntervalMs);
                    }
                    break;
                default: Log.d(TAG, "Unknown command: " + command);
            }
            // Enviar ACK para medição de latência de comandos (Cenário 2).
            // O Streamlit mede: latência = t_recv_ms - t_cmd_ms.
            sendCommandAck(command,
                    data.optInt("seq_cmd", -1),
                    data.optLong("t_cmd_ms", 0),
                    tRecvMs);
        } catch (JSONException e) {
            Log.e(TAG, "dispatchCommand: " + e.getMessage());
        }
    }

    /**
     * Envia ACK de comando ao container {@code cse-in/uxv/ack} (fire-and-forget).
     *
     * <p>Campos do ACK:
     * <ul>
     *   <li>{@code command} — nome do comando executado</li>
     *   <li>{@code seq_cmd} — sequência do Streamlit (-1 se não fornecido)</li>
     *   <li>{@code t_cmd_ms} — timestamp de envio pelo Streamlit (0 se não fornecido)</li>
     *   <li>{@code t_recv_ms} — timestamp de recepção na app (epoch ms)</li>
     *   <li>{@code t_exec_ms} — timestamp após dispatch (epoch ms)</li>
     * </ul>
     */
    private void sendCommandAck(String command, int seqCmd, long tCmdMs, long tRecvMs) {
        try {
            JSONObject ackData = new JSONObject()
                    .put("command",   command)
                    .put("seq_cmd",   seqCmd)
                    .put("t_cmd_ms",  tCmdMs)
                    .put("t_recv_ms", tRecvMs)
                    .put("t_exec_ms", System.currentTimeMillis());
            // cnf omitido — validação falha em ACME CSE v2025.11 com "application/json"
            JSONObject pc = new JSONObject()
                    .put("m2m:cin", new JSONObject()
                            .put("con", ackData.toString()));
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME + "/ack", TY_CIN, pc, null);
        } catch (JSONException e) {
            Log.e(TAG, "sendCommandAck: " + e.getMessage());
        }
    }

    // ── Sequência de registo OneM2M ──────────────────────────────────────────

    /**
     * Passo 1: registo do AE no CSE.
     *
     * <p>Usa {@code to = CSE_ID} ("id-in", CSE-relative sem leading slash) — o ACME CSE
     * v2025.11 rejeita {@code "/id-in"} (too short) e {@code "/id-in/cse-in"} cria o AE
     * numa localização inesperada. Apenas {@code "id-in"} cria o AE correctamente em
     * {@code /cse-in/uxv} (acessível via {@code CSE_BASE + "/" + AE_NAME}).
     *
     * <p>O campo {@code aei} NÃO é incluído no body — é um atributo não-provision em
     * v2025.11 (non-provision attribute). O CSE atribui {@code aei = originator}
     * automaticamente a partir do campo {@code fr} do request.
     *
     * <p>Conflito (4105) = AE já existe → tratar como sucesso e continuar.
     */
    private void registerAE() {
        notifyStatus("Registering AE (" + aeOriginator + ")...");
        try {
            // poa (Point of Access) = CSE WebSocket address.
            // REQUIRED for notification delivery: without poa the CSE discards all
            // subscription notifications silently (no poa → no delivery route).
            // The CSE uses this URL to route notifications; since our WS connection
            // is already associated with aeOriginator, it reuses the existing socket.
            String wsPoA = "ws://" + savedHost + ":" + savedPort;
            JSONObject pc = new JSONObject()
                    .put("m2m:ae", new JSONObject()
                            .put("rn",  AE_NAME)
                            .put("api", AE_API)
                            // aei NOT included — non-provision attribute in v2025.11
                            .put("srv", new JSONArray().put("3"))
                            .put("rr",  true)
                            .put("poa", new JSONArray().put(wsPoA)));
            // to = CSE_ID ("id-in") — CSE-relative identifier of the CSE-Base
            sendRequest(OP_CREATE, CSE_ID, TY_AE, pc, this::createTelemetryContainer);
        } catch (JSONException e) { Log.e(TAG, "registerAE: " + e.getMessage()); }
    }

    /** Passo 2: criação do container {@code cse-in/uxv/telemetry} (mni=10). */
    private void createTelemetryContainer() {
        notifyStatus("Creating telemetry container...");
        try {
            JSONObject pc = new JSONObject()
                    .put("m2m:cnt", new JSONObject()
                            .put("rn",  "telemetry")
                            .put("mni", 10));
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME, TY_CNT, pc,
                    this::createCommandsContainer);
        } catch (JSONException e) { Log.e(TAG, "createTelemetryContainer: " + e.getMessage()); }
    }

    /** Passo 3: criação do container {@code cse-in/uxv/commands} (mni=5). */
    private void createCommandsContainer() {
        notifyStatus("Creating commands container...");
        try {
            JSONObject pc = new JSONObject()
                    .put("m2m:cnt", new JSONObject()
                            .put("rn",  "commands")
                            .put("mni", 5));
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME, TY_CNT, pc,
                    this::createSubscription);
        } catch (JSONException e) { Log.e(TAG, "createCommandsContainer: " + e.getMessage()); }
    }

    /**
     * Passo 4: subscrição ao container de comandos.
     *
     * <p>O CSE entrega notificações via WebSocket quando um novo CIN é criado em
     * {@code cse-in/uxv/commands}. O campo {@code nu} deve ser o **originator** do AE
     * (ex: {@code C3LKFD12ABC}), não o URI do recurso AE ({@code /id-in/uxv}).
     *
     * <p>O ACME CSE associa ligações WebSocket ao originator — só entrega a notificação
     * na ligação cujo originator coincide com o {@code nu}. Usar o URI do recurso
     * resulta em subscrição criada mas notificações nunca entregues.
     *
     * <p>O CSE envia um NOTIFY de verificação ({@code vrq=true}) após criar a subscrição.
     * O {@link #sendNotifyAck} responde com 2000 OK, confirmando o endpoint.
     */
    private void createSubscription() {
        notifyStatus("Creating subscription...");
        try {
            JSONObject pc = new JSONObject()
                    .put("m2m:sub", new JSONObject()
                            .put("rn",  "sub-commands")
                            // net=3: notificar na criação de filho directo (novo CIN de comando)
                            .put("enc", new JSONObject().put("net", new JSONArray().put(3)))
                            // nu = aeOriginator: ACME CSE entrega na ligação WS do originator
                            .put("nu",  new JSONArray().put(aeOriginator)));
                            // nct omitido — nct=2 + net=[3] é inválido em ACME CSE v2025.11
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME + "/commands", TY_SUB, pc,
                    this::createAckContainer);
        } catch (JSONException e) { Log.e(TAG, "createSubscription: " + e.getMessage()); }
    }

    /**
     * Passo 5: criação do container de ACKs de comandos ({@code cse-in/uxv/ack}).
     *
     * <p>Após cada comando recebido, a app envia um CIN aqui com:
     * {@code {command, seq_cmd, t_cmd_ms, t_recv_ms, t_exec_ms}}.
     * O Streamlit subscreve este container para medir latência de comandos (Cenário 2).
     * {@code mni=200} dá buffer suficiente para um burst de 50 comandos com margem.
     */
    private void createAckContainer() {
        notifyStatus("Creating ack container...");
        try {
            JSONObject pc = new JSONObject()
                    .put("m2m:cnt", new JSONObject()
                            .put("rn",  "ack")
                            .put("mni", 200));
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME, TY_CNT, pc,
                    this::onSessionReady);
        } catch (JSONException e) { Log.e(TAG, "createAckContainer: " + e.getMessage()); }
    }

    /**
     * Passo 6 (final): sessão pronta — notifica o {@code FlightManager} que pode
     * começar a receber comandos. Dispara {@code telemetryManager.startTelemetry()} em cascata.
     */
    private void onSessionReady() {
        ready = true;
        Log.d(TAG, "OneM2M session ready — AE=" + aeOriginator);
        notifyStatus("OneM2M ready — " + aeOriginator);
        commandListener.onConnectionStatusChange(true,
                "Connected to OneM2M CSE [/" + CSE_ID + "]");
    }

    // ── Tratamento de erros e reconnect ──────────────────────────────────────

    /**
     * Chamado quando um request da sequência de registo falha ou faz timeout.
     *
     * <p>Notifica a UI, reporta o erro ao {@code commandListener} e agenda reconnect
     * se {@link #shouldReconnect}.
     */
    private void handleRegistrationFailure(String reason) {
        Log.e(TAG, "Registration failure: " + reason);
        notifyStatus("Error: " + reason);
        commandListener.onConnectionStatusChange(false, "OneM2M error: " + reason);
        if (shouldReconnect && !scheduler.isShutdown()) scheduleReconnect();
    }

    /**
     * Agenda um reconnect com exponential backoff.
     *
     * <p>Limpa pedidos pendentes e timeouts antes de agendar para garantir
     * que a próxima sessão começa limpa.
     */
    private void scheduleReconnect() {
        pending.clear();
        cancelAllTimeouts();
        int delay = reconnectDelayS;
        reconnectDelayS = Math.min(reconnectDelayS * 2, MAX_RECONNECT_DELAY_S);
        notifyStatus("Reconnecting in " + delay + "s...");
        if (scheduler.isShutdown()) return;
        scheduler.schedule(() -> {
            if (!shouldReconnect || transport == null) return;
            notifyStatus("Reconnecting...");
            pending.clear();
            transport.connect(savedHost, savedPort, savedAeId);
        }, delay, TimeUnit.SECONDS);
    }

    // ── Utilitários ──────────────────────────────────────────────────────────

    /**
     * Constrói e envia um request OneM2M via transporte, com timeout opcional.
     *
     * <p>Formato flat JSON (sem wrapper {@code m2m:rqp}) conforme ACME CSE v2025.11.
     * O campo {@code rvi="3"} é obrigatório nesta versão.
     *
     * <p>Se {@code onSuccess != null}, agenda um timeout de {@value REQUEST_TIMEOUT_S} s.
     * Se a resposta chegar antes do timeout, o timeout é cancelado. Se o timeout disparar
     * sem resposta, chama {@link #handleRegistrationFailure}.
     *
     * @param op        código de operação (OP_CREATE=1, OP_NOTIFY=5)
     * @param to        path do recurso alvo (CSE-relative, ex: "cse-in/uxv/telemetry")
     * @param ty        tipo de recurso (TY_AE=2, TY_CNT=3, TY_CIN=4, TY_SUB=23)
     * @param pc        conteúdo do pedido
     * @param onSuccess callback em caso de resposta de sucesso; {@code null} para fire-and-forget
     */
    private void sendRequest(int op, String to, int ty, JSONObject pc, Runnable onSuccess) {
        String rqi = "rqi-" + rqiCounter.incrementAndGet();
        try {
            // Flat format (no m2m:rqp wrapper) — required by ACME CSE v2025.11 WebSocket binding
            JSONObject request = new JSONObject()
                    .put("op",  op)
                    .put("to",  to)
                    .put("fr",  aeOriginator)
                    .put("rqi", rqi)
                    .put("rvi", "3")   // release version indicator — mandatory in v2025.11
                    .put("ty",  ty)
                    .put("pc",  pc);

            if (onSuccess != null) {
                pending.put(rqi, onSuccess);
                // Agendar timeout — se o CSE não responder, não ficamos suspensos
                if (!scheduler.isShutdown()) {
                    final String rqiFinal = rqi;
                    ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
                        Runnable cb = pending.remove(rqiFinal);
                        timeouts.remove(rqiFinal);
                        if (cb != null) {
                            Log.e(TAG, "Timeout on " + rqiFinal);
                            handleRegistrationFailure("timeout on " + rqiFinal);
                        }
                    }, REQUEST_TIMEOUT_S, TimeUnit.SECONDS);
                    timeouts.put(rqi, timeoutFuture);
                }
            }

            transport.sendTelemetry(request.toString());

        } catch (JSONException e) {
            Log.e(TAG, "sendRequest rqi=" + rqi + ": " + e.getMessage());
        }
    }

    /** Cancela todos os timeouts pendentes. */
    private void cancelAllTimeouts() {
        for (ScheduledFuture<?> f : timeouts.values()) f.cancel(false);
        timeouts.clear();
    }

    /** Publica uma mensagem de estado ao SessionListener e ao log. */
    private void notifyStatus(String message) {
        Log.d(TAG, message);
        if (sessionListener != null) sessionListener.onSessionStatus(message);
    }

    // ── DroneCommandListener — delegação ao commandListener ──────────────────
    // Estes métodos existem porque OneM2MSession é passado ao NetworkManager como
    // DroneCommandListener (para interceptar onConnectionStatusChange). Em modo OneM2M,
    // os comandos chegam via notificações parseadas em dispatchCommand() — estes
    // delegates apenas garantem compilação correcta da interface.

    @Override public void onTakeOff()                          { commandListener.onTakeOff(); }
    @Override public void onLand()                             { commandListener.onLand(); }
    @Override public void onMotors(boolean on)                 { commandListener.onMotors(on); }
    @Override public void onGoHome()                           { commandListener.onGoHome(); }
    @Override public void onMoveTo(double lat, double lng)     { commandListener.onMoveTo(lat, lng); }
    @Override public void onVirtualStickInput(float r, float p, float y, float t) {
        commandListener.onVirtualStickInput(r, p, y, t);
    }
    @Override public void onVirtualStickState(boolean enabled) { commandListener.onVirtualStickState(enabled); }
    @Override public void onPerform360()                       { commandListener.onPerform360(); }
    @Override public void onIdentify(boolean on)               { commandListener.onIdentify(on); }
    @Override public void onStartMission(String sa, String ea, int r, float alt, String path) {
        commandListener.onStartMission(sa, ea, r, alt, path);
    }
    @Override public void onStopMission()                      { commandListener.onStopMission(); }
    @Override public void onPauseMission()                     { commandListener.onPauseMission(); }
    @Override public void onSetZoom(float factor)              { commandListener.onSetZoom(factor); }
    @Override public void onSetCameraMode(String mode)         { commandListener.onSetCameraMode(mode); }
    @Override public void onGimbalAngle(float pitch, float yaw, String mode) {
        commandListener.onGimbalAngle(pitch, yaw, mode);
    }
    @Override public void onGimbalReset()                      { commandListener.onGimbalReset(); }
}
