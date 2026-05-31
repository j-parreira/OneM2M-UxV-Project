/**
 * {@code NetworkManager} — Transporte WebSocket para comunicação com o ACME CSE.
 *
 * Implementa {@link ProtocolClient} sobre WebSocket (okhttp3). Liga ao CSE,
 * envia frames JSON e entrega mensagens recebidas ao {@link DroneCommandListener}
 * ou, quando em modo OneM2M, ao {@code RawMessageListener} definido
 * por {@link com.dji.sdk.duvops.network.OneM2MSession} via {@link #setRawMessageListener}.
 *
 * <h3>Protocolo de conexão</h3>
 * <ul>
 *   <li>URL construído como {@code ws://<host>:<port>}</li>
 *   <li>Cabeçalho: {@code dboidsID} = aeId do drone</li>
 *   <li>Modo raw: chama directamente {@link DroneCommandListener} com o comando parseado</li>
 *   <li>Modo OneM2M: entrega todas as mensagens ao rawMessageListener sem processar</li>
 * </ul>
 *
 * @author João Parreira
 * @version 4.0
 */
package com.dji.sdk.duvops.network;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;

/**
 * Transporte WebSocket (okhttp3) que implementa {@link ProtocolClient}.
 *
 * <h3>Ciclo de vida</h3>
 * <pre>
 * NetworkManager(droneCommandListener)
 *     → connect(host, port, aeId) → cria WebSocket + SocketListener
 *     → onOpen → notifyConnectionChange(true) → listener.onConnectionStatusChange()
 *     → onMessage(text) → handleRawMessage(text)
 *         ├─ rawMessageListener != null → rawMessageListener.onRawMessage() [modo OneM2M]
 *         └─ rawMessageListener == null → processCommand() → listener [modo raw]
 *     → sendTelemetry(json) → ws.send()
 *     → disconnect() → fecha WebSocket
 * </pre>
 */
public class NetworkManager implements ProtocolClient {

    /** Tag para log. */
    private static final String TAG = "RemoteCommandManager";

    /** Instância WebSocket atual. */
    private WebSocket ws;

    /** Listener para delegar comandos ao drone. */
    private DroneCommandListener listener;

    /** URL do servidor WebSocket. */
    private String serverUrl;

    /** Indica se o WebSocket está actualmente ligado. */
    private boolean connected = false;

    /**
     * Contador de geração do WebSocket.
     *
     * <p>Incrementado em cada chamada a {@link #connect}. O valor é passado ao
     * {@link SocketListener} recém-criado e verificado em {@link #notifyConnectionChange}:
     * se a geração do callback não coincidir com a actual, o callback é descartado.
     * Previne que tentativas de ligação obsoletas (ex: IP antigo com timeout de 10 s)
     * perturbem uma sessão já activa.
     */
    private volatile int wsGeneration = 0;

    /** Listener de debug para comandos recebidos. */
    private ProtocolClient.CommandLogListener commandLogListener;

    /**
     * Listener de mensagens raw — quando definido (por OneM2MSession), todas as
     * mensagens são entregues aqui e o dispatch normal de comandos é ignorado.
     */
    private ProtocolClient.RawMessageListener rawMessageListener;

    /**
     * Define o listener de mensagens raw (usado por {@link OneM2MSession}).
     *
     * <p>Quando definido, {@link #handleRawMessage} entrega a mensagem aqui
     * em vez de processar comandos directamente.
     *
     * @param listener listener a chamar para cada mensagem recebida
     */
    @Override
    public void setRawMessageListener(ProtocolClient.RawMessageListener listener) {
        this.rawMessageListener = listener;
    }

    /**
     * Retorna o URL do Point of Access deste transporte WebSocket.
     *
     * <p>Formato: {@code ws://host:port}. Usado pelo {@link OneM2MSession}
     * no campo {@code poa} do registo do AE.
     *
     * @return URL do poa WebSocket
     */
    @Override
    public String getPoaUrl() {
        return serverUrl;
    }

    /**
     * WebSocket: não apagar o AE antes de recriar — o CSE fecha a ligação WS activa
     * quando o AE associado é apagado, o que mata a sessão imediatamente.
     *
     * @return {@code false} — DELETE é desnecessário e destrutivo para WS
     */
    @Override
    public boolean requiresAeDeleteBeforeRegister() {
        return false;
    }

    /**
     * Define o listener de debug para comandos recebidos.
     *
     * @param listener listener a notificar (pode ser {@code null})
     */
    @Override
    public void setCommandLogListener(ProtocolClient.CommandLogListener listener) {
        this.commandLogListener = listener;
    }

    /**
     * Indica se o WebSocket está actualmente ligado.
     *
     * @return {@code true} se a ligação está estabelecida
     */
    @Override
    public boolean isConnected() {
        return connected;
    }

    /** Serial number do drone (usado como ID). */
    private String droneId;

    /**
     * Cria o NetworkManager com o listener de comandos.
     *
     * @param listener o listener que processa os comandos do drone
     */
    public NetworkManager(DroneCommandListener listener) {
        this.listener = listener;
    }

    /**
     * Liga ao ACME CSE via WebSocket.
     *
     * <p>Fecha qualquer ligação anterior antes de abrir uma nova.
     * Constrói o URL como {@code ws://host:port}.
     *
     * @param host hostname ou IP do CSE (sem prefixo de protocolo)
     * @param port porto WebSocket do CSE (normalmente 8180)
     * @param aeId identificador do AE (serial number do drone)
     */
    @Override
    public void connect(String host, int port, String aeId) {
        disconnect();

        this.serverUrl = "ws://" + host + ":" + port;
        this.droneId = aeId;

        // Do NOT call notifyConnectionChange(false,...) here: that would fire
        // onConnectionStatusChange(false) while shouldReconnect=true, triggering
        // an immediate spurious scheduleReconnect(). OneM2MSession already calls
        // notifyStatus("Connecting to...") which updates the UI without side-effects.

        // Invalidate callbacks from any previous WebSocket instance
        wsGeneration++;

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(serverUrl)
                .addHeader("dboidsID", aeId)
                // oneM2M WebSocket subprotocol — required by ACME CSE v2025.11
                // Without this header the server returns HTTP 400 "missing subprotocol"
                .addHeader("Sec-WebSocket-Protocol", "oneM2M.json")
                // oneM2M originator in the WS upgrade headers — required by ACME CSE v2025.11
                // The CSE associates this connection with the originator from this header.
                // Without it, all non-CAdmin requests return 4103 "no X-M2M-Origin header".
                .addHeader("X-M2M-Origin", aeId)
                .build();

        SocketListener socketListener = new SocketListener(this, wsGeneration);
        ws = client.newWebSocket(request, socketListener);
    }

    /**
     * Fecha a ligação WebSocket se estiver aberta.
     *
     * <p>Incrementa {@link #wsGeneration} ANTES do {@code ws.close()} para que os
     * callbacks {@code onClosing}/{@code onClosed}/{@code onFailure} deste socket —
     * e de qualquer TCP connect ainda em flight (ex: tentativa ao IP antigo com
     * timeout de 10 s) — sejam descartados pelo {@link #notifyConnectionChange} do
     * NetworkManager que os criou, mesmo após este ser substituído por um novo transporte.
     */
    @Override
    public void disconnect() {
        connected = false;
        // Invalidate all pending SocketListener callbacks before closing the socket.
        // Critical for the cross-NM case: when a new NetworkManager replaces this one,
        // any in-flight TCP connects (e.g. old IP with 10 s timeout) still reference
        // THIS object. Incrementing here ensures those late onFailure callbacks are
        // dropped even though they match the old wsGeneration.
        wsGeneration++;
        if (ws != null) {
            ws.close(1000, "App closing");
            ws = null;
        }
    }

    /**
     * Envia um payload JSON ao CSE via WebSocket.
     *
     * <p>Em modo raw, envia telemetria directamente. Em modo OneM2M,
     * {@link OneM2MSession} envolve o payload num frame {@code m2m:rqp}
     * antes de chamar este método.
     *
     * @param jsonPayload payload JSON serializado a enviar
     */
    @Override
    public void sendTelemetry(String jsonPayload) {
        if (ws != null) {
            ws.send(jsonPayload);
        }
    }

    /**
     * Processa uma mensagem recebida do CSE.
     *
     * <p>Se {@link #rawMessageListener} estiver definido (modo OneM2M), entrega a
     * mensagem sem processar — {@link OneM2MSession} trata do protocolo OneM2M.
     * Caso contrário, extrai o campo {@code "command"} e delega ao
     * {@link #processCommand(String, JSONObject)} (modo raw legado).
     *
     * @param text a mensagem JSON recebida
     */
    public void handleRawMessage(String text) {
        if (rawMessageListener != null) {
            rawMessageListener.onRawMessage(text);
            return;
        }
        try {
            JSONObject obj = new JSONObject(text);
            if (obj.has("command")) {
                processCommand(obj.getString("command"), obj);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error Parsing JSON: " + e.getMessage());
        }
    }

    /**
     * Notifica o listener da mudança de estado de conexão.
     *
     * <p>Se {@code generation} não coincidir com {@link #wsGeneration} actual, o callback
     * é de um WebSocket obsoleto (ex: tentativa anterior com timeout ainda a correr) e é
     * descartado silenciosamente para não perturbar a sessão activa.
     *
     * @param connected  {@code true} se a conexão é bem-sucedida
     * @param msg        mensagem a exibir na UI (usada apenas em caso de falha)
     * @param generation geração do WebSocket que gerou este callback
     */
    public void notifyConnectionChange(boolean connected, String msg, int generation) {
        if (generation != wsGeneration) {
            Log.d(TAG, "Dropping stale ws callback gen=" + generation + " current=" + wsGeneration
                    + " connected=" + connected + " msg=" + msg);
            return;
        }
        this.connected = connected;
        if (listener != null) {
            if (connected) {
                listener.onConnectionStatusChange(true, "Connected to: " + this.serverUrl);
            } else {
                listener.onConnectionStatusChange(false, msg);
            }
        }
    }

    /**
     * Processa um comando individual recebido do servidor.
     *
     * <p>Mapa de comandos:
     * <table>
     *   <tr><th>Comando</th><th>Ação</th></tr>
     *   <tr><td>{@code "takeoff"}</td><td>{@code onTakeOff()}</td></tr>
     *   <tr><td>{@code "setZoom"}</td><td>{@code onSetZoom(factor)}</td></tr>
     *   <tr><td>{@code "getZoom"}</td><td>Devolve zoom atual via sendResponse</td></tr>
     *   <tr><td>...</td><td>...</td></tr>
     * </table>
     *
     * @param command o nome do comando
     * @param data o JSON com os parâmetros do comando
     */
    private void processCommand(String command, JSONObject data) throws JSONException {
        // Notificar listener de log de comandos (debug)
        if (commandLogListener != null) {
            commandLogListener.onCommandReceived(command, data.toString());
        }
        if (listener == null) return;

        switch (command) {
            case "takeoff":
                listener.onTakeOff();
                break;
            case "land":
                listener.onLand();
                break;
            case "motors":
                boolean motorsState = data.has("state") && data.getBoolean("state");
                listener.onMotors(motorsState);
                break;
            case "startGoHome":
                listener.onGoHome();
                break;
            case "virtualSticks":
                boolean vsState = data.has("state") && data.getBoolean("state");
                listener.onVirtualStickState(vsState);
                break;
            case "virtualSticksInput":
                float pitch = (float) data.optDouble("pitch", 0);
                float roll = (float) data.optDouble("roll", 0);
                float yaw = (float) data.optDouble("yaw", 0);
                float throttle = (float) data.optDouble("throttle", 0);
                listener.onVirtualStickInput(roll, pitch, yaw, throttle);
                break;
            case "gpsInput":
            case "gpsInput360Mapping":
                if (data.has("lat") && data.has("lng")) {
                    listener.onMoveTo(data.getDouble("lat"), data.getDouble("lng"));
                }
                break;
            case "perform360":
                listener.onPerform360();
                break;
            case "identify":
                boolean idState = data.has("state") && data.getBoolean("state");
                listener.onIdentify(idState);
                break;
            case "startMission":
                String startAction = data.optString("startAction");
                String endAction = data.optString("endAction");
                int repeat = data.optInt("repeat", 0);
                float alt = (float) data.optDouble("altitude", 0);
                JSONArray path = data.optJSONArray("path");
                listener.onStartMission(startAction, endAction, repeat, alt,
                        path != null ? path.toString() : null);
                break;
            case "stopMission":
                listener.onStopMission();
                break;
            case "pauseMission":
                listener.onPauseMission();
                break;
            case "setZoom":
                float zoomFactor = (float) data.optDouble("factor", 1.0);
                listener.onSetZoom(zoomFactor);
                break;
            case "setCameraMode":
                String cameraMode = data.optString("mode", "RGB");
                listener.onSetCameraMode(cameraMode);
                break;
            case "gimbalAngle":
                float gimbalPitch = (float) data.optDouble("pitch", 0);
                float gimbalYaw = (float) data.optDouble("yaw", 0);
                String gMode = data.optString("mode", "absolute");
                listener.onGimbalAngle(gimbalPitch, gimbalYaw, gMode);
                break;
            case "gimbalReset":
                listener.onGimbalReset();
                break;
            default:
                Log.d(TAG, "Unknown command: " + command);
        }
    }

}
