/**
 * {@code OneM2MSession} — Camada de protocolo OneM2M sobre um transporte {@link ProtocolClient}.
 *
 * <p>Gere o ciclo de vida de uma sessão AE (Application Entity) com o ACME CSE:
 * <ol>
 *   <li>Registo do AE ({@code m2m:ae}) no CSE</li>
 *   <li>Criação dos containers {@code telemetry} e {@code commands}</li>
 *   <li>Criação de uma subscrição no container {@code commands} para receber notificações</li>
 *   <li>Envio de telemetria como {@code m2m:cin} (contentInstance)</li>
 *   <li>Recepção e despacho de comandos via notificações {@code m2m:sgn}</li>
 * </ol>
 *
 * <h3>Integração na stack</h3>
 * <pre>
 * DuvopsView / TelemetryManager
 *     → protocolClient: OneM2MSession  (implements ProtocolClient)
 *         → transport: NetworkManager  (raw WebSocket, implements ProtocolClient)
 *         → commandListener: FlightManager  (executa comandos no drone)
 * </pre>
 *
 * <h3>Sequência de inicialização</h3>
 * <pre>
 * connect()
 *   → transport.connect() → WebSocket abre
 *   → onConnectionStatusChange(true) → registerAE()
 *   → resp 2001/4105 → createTelemetryContainer()
 *   → resp 2001/4105 → createCommandsContainer()
 *   → resp 2001/4105 → createSubscription()
 *   → resp 2001/4105 → onSessionReady()
 *   → commandListener.onConnectionStatusChange(true, ...) → startTelemetry()
 * </pre>
 *
 * @author João Parreira
 * @version 1.0
 */
package com.dji.sdk.duvops.network;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sessão OneM2M: registo de AE, gestão de containers e despacho de comandos.
 *
 * <p>Implementa {@link ProtocolClient} para ser usado por {@code DuvopsView} e
 * {@code TelemetryManager}. Implementa {@link DroneCommandListener} para ser
 * passado ao {@link NetworkManager} como receptor dos eventos de conexão.
 */
public class OneM2MSession implements ProtocolClient, DroneCommandListener {

    private static final String TAG = "OneM2MSession";

    // ── OneM2M resource paths ────────────────────────────────────────────────

    /** Base do CSE configurada em acme.ini → cseID = id-in */
    private static final String CSE_BASE = "/id-in";

    /** Nome do recurso AE (Application Entity) */
    private static final String AE_NAME = "uxv";

    /** API identifier do AE (oneM2M application ID) */
    private static final String AE_API = "N.com.uxv.onem2m";

    // ── OneM2M operation codes ───────────────────────────────────────────────

    private static final int OP_CREATE = 1;
    private static final int OP_NOTIFY = 5;

    // ── OneM2M resource type codes ───────────────────────────────────────────

    private static final int TY_AE  = 2;
    private static final int TY_CNT = 3;
    private static final int TY_CIN = 4;
    private static final int TY_SUB = 23;

    // ── OneM2M response status codes ─────────────────────────────────────────

    private static final int RSC_OK      = 2000;
    private static final int RSC_CREATED = 2001;
    /** Recurso já existe — tratar como sucesso na inicialização. */
    private static final int RSC_CONFLICT = 4105;

    // ── Estado da sessão ─────────────────────────────────────────────────────

    /** Transporte raw (WebSocket). Injectado via {@link #setTransport}. */
    private NetworkManager transport;

    /** Executa os comandos de voo no drone. */
    private final DroneCommandListener commandListener;

    /** Listener de debug para comandos recebidos. */
    private ProtocolClient.CommandLogListener commandLogListener;

    /** Originator do AE: "C" + serialNumber (limpo). Definido em connect(). */
    private String aeOriginator;

    /** Contador para gerar request IDs únicos. Thread-safe. */
    private final AtomicInteger rqiCounter = new AtomicInteger(0);

    /**
     * Mapa de pedidos pendentes: rqi → callback a executar em caso de sucesso.
     * Thread-safe (respostas chegam no thread do OkHttp).
     */
    private final ConcurrentHashMap<String, Runnable> pending = new ConcurrentHashMap<>();

    /** {@code true} após a sequência de inicialização completa. */
    private volatile boolean ready = false;

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
        // Interceptar todas as mensagens antes do dispatch normal de comandos
        transport.setRawMessageListener(this::onRawMessage);
    }

    // ── ProtocolClient ───────────────────────────────────────────────────────

    /**
     * Liga ao CSE. O transporte é aberto aqui; o registo AE começa quando a
     * ligação é confirmada (via {@link #onConnectionStatusChange}).
     *
     * @param host  IP ou hostname do CSE
     * @param port  porto WebSocket (normalmente 8180)
     * @param aeId  serial number do drone (base para o originator OneM2M)
     */
    @Override
    public void connect(String host, int port, String aeId) {
        ready = false;
        pending.clear();
        // Originator deve começar com "C" (regra oneM2M)
        String cleaned = aeId.replaceAll("[^a-zA-Z0-9]", "");
        this.aeOriginator = "C" + (cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned);
        transport.connect(host, port, aeId);
    }

    /** {@inheritDoc} */
    @Override
    public void disconnect() {
        ready = false;
        pending.clear();
        transport.disconnect();
    }

    /**
     * Envia telemetria ao CSE como {@code m2m:cin} (contentInstance).
     *
     * <p>Só envia quando a sessão está pronta ({@link #ready}). O payload é
     * envolvido num frame {@code m2m:rqp} com {@code op=1} (CREATE) e {@code ty=4} (CIN).
     * A resposta do CSE é ignorada (fire-and-forget) para não bloquear o timer de 250 ms.
     *
     * @param jsonPayload JSON de telemetria gerado pelo {@code TelemetryManager}
     */
    @Override
    public void sendTelemetry(String jsonPayload) {
        if (!ready) return;
        try {
            JSONObject pc = new JSONObject()
                    .put("m2m:cin", new JSONObject()
                            .put("cnf", "application/json")
                            .put("con", jsonPayload));
            // fire-and-forget: sem callback → resposta ignorada
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME + "/telemetry", TY_CIN, pc, null);
        } catch (JSONException e) {
            Log.e(TAG, "sendTelemetry: " + e.getMessage());
        }
    }

    /** @return {@code true} se a sessão OneM2M está registada e pronta. */
    @Override
    public boolean isConnected() {
        return ready;
    }

    /** {@inheritDoc} */
    @Override
    public void setCommandLogListener(ProtocolClient.CommandLogListener listener) {
        this.commandLogListener = listener;
    }

    // ── DroneCommandListener (recebe eventos do transport) ───────────────────

    /**
     * Chamado pelo {@link NetworkManager} quando o WebSocket abre ou fecha.
     *
     * <p>Em caso de ligação bem-sucedida, inicia a sequência de registo AE.
     * O {@code commandListener} só é notificado após o registo estar completo.
     */
    @Override
    public void onConnectionStatusChange(boolean isConnected, String message) {
        if (isConnected) {
            Log.d(TAG, "Transport connected — starting AE registration");
            registerAE();
        } else {
            ready = false;
            commandListener.onConnectionStatusChange(false, message);
        }
    }

    // ── Handlers de mensagens raw ────────────────────────────────────────────

    /**
     * Processa uma mensagem JSON recebida do CSE.
     *
     * <p>Distingue respostas ({@code m2m:rsp}) de notificações de comandos
     * ({@code m2m:rqp} com {@code op=5}).
     *
     * @param json mensagem JSON crua recebida do WebSocket
     */
    private void onRawMessage(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("m2m:rsp")) {
                handleResponse(obj.getJSONObject("m2m:rsp"));
            } else if (obj.has("m2m:rqp")) {
                JSONObject rqp = obj.getJSONObject("m2m:rqp");
                if (rqp.optInt("op", 0) == OP_NOTIFY) {
                    handleNotification(rqp);
                    // ACK obrigatório para o CSE não reenviar a notificação
                    sendNotifyAck(rqp.optString("rqi", ""));
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "onRawMessage parse error: " + e.getMessage());
        }
    }

    /**
     * Processa uma resposta do CSE a um pedido anterior.
     *
     * <p>Consulta {@link #pending} pelo {@code rqi} e executa o callback se o
     * pedido foi bem-sucedido (2001 Created, 2000 OK, ou 4105 Conflict — já existe).
     *
     * @param rsp objecto JSON {@code m2m:rsp}
     */
    private void handleResponse(JSONObject rsp) throws JSONException {
        String rqi = rsp.optString("rqi", "");
        int rsc = rsp.optInt("rsc", 0);
        Runnable callback = pending.remove(rqi);
        boolean ok = (rsc == RSC_CREATED || rsc == RSC_OK || rsc == RSC_CONFLICT);
        if (callback != null && ok) {
            callback.run();
        } else if (callback != null) {
            Log.e(TAG, "Request failed rsc=" + rsc + " rqi=" + rqi);
            commandListener.onConnectionStatusChange(false, "OneM2M error: rsc=" + rsc);
        }
        // Se callback==null é resposta fire-and-forget (ex: telemetry CIN) — ignorar
    }

    /**
     * Processa uma notificação de comando recebida do CSE.
     *
     * <p>Extrai o campo {@code con} do {@code m2m:cin} dentro da notificação
     * e delega o JSON de comando ao {@link #dispatchCommand}.
     *
     * @param rqp objecto JSON {@code m2m:rqp} com {@code op=5}
     */
    private void handleNotification(JSONObject rqp) throws JSONException {
        JSONObject pc  = rqp.optJSONObject("pc");  if (pc  == null) return;
        JSONObject sgn = pc.optJSONObject("m2m:sgn"); if (sgn == null) return;
        JSONObject nev = sgn.optJSONObject("nev");    if (nev == null) return;
        JSONObject rep = nev.optJSONObject("rep");    if (rep == null) return;
        JSONObject cin = rep.optJSONObject("m2m:cin");if (cin == null) return;
        String con = cin.optString("con", "");
        if (!con.isEmpty()) dispatchCommand(con);
    }

    /**
     * Envia um ACK ao CSE para confirmar a recepção de uma notificação.
     *
     * @param rqi request ID da notificação a confirmar
     */
    private void sendNotifyAck(String rqi) {
        try {
            JSONObject rsp = new JSONObject()
                    .put("m2m:rsp", new JSONObject()
                            .put("rsc", RSC_OK)
                            .put("rqi", rqi)
                            .put("to",  aeOriginator)
                            .put("fr",  aeOriginator));
            transport.sendTelemetry(rsp.toString());
        } catch (JSONException e) {
            Log.e(TAG, "sendNotifyAck: " + e.getMessage());
        }
    }

    /**
     * Parseia e despacha um JSON de comando ao {@code commandListener}.
     *
     * <p>O formato esperado é o mesmo do modo raw: {@code {"command": "takeoff", ...}}.
     *
     * @param commandJson JSON do comando tal como chegou no campo {@code con} do CIN
     */
    private void dispatchCommand(String commandJson) {
        try {
            JSONObject data = new JSONObject(commandJson);
            if (!data.has("command")) return;
            String command = data.getString("command");

            if (commandLogListener != null) {
                commandLogListener.onCommandReceived(command, commandJson);
            }

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
                    if (data.has("lat") && data.has("lng")) {
                        commandListener.onMoveTo(data.getDouble("lat"), data.getDouble("lng"));
                    } break;
                case "perform360":  commandListener.onPerform360(); break;
                case "startRTMP":   commandListener.onStartRTMP(); break;
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
                default:
                    Log.d(TAG, "Unknown command: " + command);
            }
        } catch (JSONException e) {
            Log.e(TAG, "dispatchCommand: " + e.getMessage());
        }
    }

    // ── Sequência de registo OneM2M ──────────────────────────────────────────

    /**
     * Passo 1: registo do AE no CSE.
     *
     * <p>Se o AE já existir (rsc 4105), considera-se sucesso e avança para
     * a criação dos containers.
     */
    private void registerAE() {
        try {
            JSONObject pc = new JSONObject()
                    .put("m2m:ae", new JSONObject()
                            .put("rn",  AE_NAME)
                            .put("api", AE_API)
                            .put("aei", aeOriginator)
                            .put("srv", new JSONArray().put("3"))
                            .put("rr",  true));
            sendRequest(OP_CREATE, CSE_BASE, TY_AE, pc, this::createTelemetryContainer);
        } catch (JSONException e) {
            Log.e(TAG, "registerAE: " + e.getMessage());
        }
    }

    /**
     * Passo 2: criação do container de telemetria ({@code /id-in/uxv/telemetry}).
     */
    private void createTelemetryContainer() {
        try {
            JSONObject pc = new JSONObject()
                    .put("m2m:cnt", new JSONObject()
                            .put("rn", "telemetry")
                            .put("mni", 10));   // máximo 10 instâncias em cache
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME, TY_CNT, pc,
                    this::createCommandsContainer);
        } catch (JSONException e) {
            Log.e(TAG, "createTelemetryContainer: " + e.getMessage());
        }
    }

    /**
     * Passo 3: criação do container de comandos ({@code /id-in/uxv/commands}).
     */
    private void createCommandsContainer() {
        try {
            JSONObject pc = new JSONObject()
                    .put("m2m:cnt", new JSONObject()
                            .put("rn", "commands")
                            .put("mni", 5));    // poucos comandos em fila
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME, TY_CNT, pc,
                    this::createSubscription);
        } catch (JSONException e) {
            Log.e(TAG, "createCommandsContainer: " + e.getMessage());
        }
    }

    /**
     * Passo 4: criação de subscrição no container de comandos.
     *
     * <p>O CSE enviará uma notificação ({@code m2m:sgn}) via WebSocket sempre
     * que um novo CIN for criado em {@code /id-in/uxv/commands}.
     */
    private void createSubscription() {
        try {
            JSONObject pc = new JSONObject()
                    .put("m2m:sub", new JSONObject()
                            .put("rn",  "sub-commands")
                            // net=3: notificar na criação de filho directo (novo CIN)
                            .put("enc", new JSONObject().put("net", new JSONArray().put(3)))
                            // nu: entregar notificação ao nosso AE (mesma ligação WS)
                            .put("nu",  new JSONArray().put(CSE_BASE + "/" + AE_NAME))
                            // nct=2: incluir todos os atributos do recurso na notificação
                            .put("nct", 2));
            sendRequest(OP_CREATE, CSE_BASE + "/" + AE_NAME + "/commands", TY_SUB, pc,
                    this::onSessionReady);
        } catch (JSONException e) {
            Log.e(TAG, "createSubscription: " + e.getMessage());
        }
    }

    /**
     * Passo 5 (final): sessão pronta — notifica o {@code FlightManager} que pode
     * começar a receber comandos. Isto desencadeia o início do envio de telemetria
     * em {@code DuvopsView.onStatusUpdate()}.
     */
    private void onSessionReady() {
        ready = true;
        Log.d(TAG, "OneM2M session ready — AE=" + aeOriginator);
        commandListener.onConnectionStatusChange(true, "Connected to OneM2M CSE [" + CSE_BASE + "]");
    }

    // ── Utilitários ──────────────────────────────────────────────────────────

    /**
     * Constrói e envia um pedido OneM2M via transporte.
     *
     * <p>Gera um {@code rqi} único, armazena o callback em {@link #pending}
     * (se não for {@code null}) e envia o frame via {@code transport.sendTelemetry()}.
     *
     * @param op         código de operação (OP_CREATE, etc.)
     * @param to         caminho do recurso alvo (ex: {@code /id-in/uxv/commands})
     * @param ty         tipo de recurso (TY_AE, TY_CNT, etc.)
     * @param pc         conteúdo do pedido (objecto {@code m2m:ae}, {@code m2m:cnt}, etc.)
     * @param onSuccess  callback a executar quando o CSE responde com sucesso; {@code null} para fire-and-forget
     */
    private void sendRequest(int op, String to, int ty, JSONObject pc, Runnable onSuccess) {
        String rqi = "rqi-" + rqiCounter.incrementAndGet();
        try {
            JSONObject request = new JSONObject()
                    .put("m2m:rqp", new JSONObject()
                            .put("op",  op)
                            .put("to",  to)
                            .put("fr",  aeOriginator)
                            .put("rqi", rqi)
                            .put("ty",  ty)
                            .put("pc",  pc));
            if (onSuccess != null) pending.put(rqi, onSuccess);
            transport.sendTelemetry(request.toString());
        } catch (JSONException e) {
            Log.e(TAG, "sendRequest rqi=" + rqi + ": " + e.getMessage());
        }
    }

    // ── DroneCommandListener — delegação directa ao commandListener ──────────
    // Estes métodos existem porque OneM2MSession é passado ao NetworkManager como
    // DroneCommandListener (para interceptar onConnectionStatusChange). Em modo OneM2M,
    // os restantes métodos nunca são chamados pelo NetworkManager — os comandos chegam
    // via notificações parseadas em dispatchCommand(). Os delegates ficam aqui para
    // compatibilidade com a interface, caso o transport seja usado em modo raw.

    @Override public void onTakeOff()                        { commandListener.onTakeOff(); }
    @Override public void onLand()                           { commandListener.onLand(); }
    @Override public void onMotors(boolean on)               { commandListener.onMotors(on); }
    @Override public void onGoHome()                         { commandListener.onGoHome(); }
    @Override public void onMoveTo(double lat, double lng)   { commandListener.onMoveTo(lat, lng); }
    @Override public void onVirtualStickInput(float roll, float pitch, float yaw, float throttle) {
        commandListener.onVirtualStickInput(roll, pitch, yaw, throttle);
    }
    @Override public void onVirtualStickState(boolean enabled) { commandListener.onVirtualStickState(enabled); }
    @Override public void onPerform360()                     { commandListener.onPerform360(); }
    @Override public void onIdentify(boolean on)             { commandListener.onIdentify(on); }
    @Override public void onStartMission(String startAction, String endAction, int repeat, float altitude, String pathJson) {
        commandListener.onStartMission(startAction, endAction, repeat, altitude, pathJson);
    }
    @Override public void onStopMission()                    { commandListener.onStopMission(); }
    @Override public void onPauseMission()                   { commandListener.onPauseMission(); }
    @Override public void onStartRTMP()                      { commandListener.onStartRTMP(); }
    @Override public void onSetZoom(float factor)            { commandListener.onSetZoom(factor); }
    @Override public void onSetCameraMode(String mode)       { commandListener.onSetCameraMode(mode); }
    @Override public void onGimbalAngle(float pitch, float yaw, String mode) {
        commandListener.onGimbalAngle(pitch, yaw, mode);
    }
    @Override public void onGimbalReset()                    { commandListener.onGimbalReset(); }
}
