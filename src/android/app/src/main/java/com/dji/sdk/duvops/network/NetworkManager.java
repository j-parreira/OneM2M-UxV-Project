/**
 * {@code NetworkManager} — Gestor de comunicação WebSocket com o servidor.
 *
 * Liga o servidor WebSocket, envia telemetria e processa comandos
 * recebidos delegando ao {@link DroneCommandListener}.
 *
 * <h3>Protocolo de conexão</h3>
 * <ul>
 *   <li>URL: {@code ws://<server>} (auto-prefixed com {@code ws://} se faltar)</li>
 *   <li>Cabeçalho: {@code dboidsID} = serial number do drone</li>
 *   <li>Telemetria: JSON a cada 250ms</li>
 * </ul>
 *
 * @author João Parreira
 * @version 3.0
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
 * Gestor de camada de rede — WebSocket para comunicação drone-server.
 *
 * <h3>Ciclo de vida</h3>
 * <pre>
 * NetworkManager(listener)
 *     → connect(url, serialNumber) → cria WebSocket + SocketListener
 *     → onOpen → notifyConnectionChange(true)
 *     → onMessage(text) → handleRawMessage(text) → processCommand() → listener
 *     → sendStatus(json) → envia telemetria
 *     → sendResponse(json) → envia resposta do getter ao frontend
 *     → disconnect() → fecha WebSocket
 * </pre>
 */
public class NetworkManager {

    /** Tag para log. */
    private static final String TAG = "RemoteCommandManager";

    /** Instância WebSocket atual. */
    private WebSocket ws;

    /** Listener para delegar comandos ao drone. */
    private DroneCommandListener listener;

    /** URL do servidor WebSocket. */
    private String serverUrl;

    /** Listener para log de comandos recebidos (debug). */
    private CommandLogListener commandLogListener;

    /**
     * Interface para notificar o último comando recebido (debug).
     */
    public interface CommandLogListener {
        /**
         * Chamado quando um comando é recebido do servidor.
         *
         * @param command nome do comando
         * @param rawJSON o JSON bruto recebido
         */
        void onCommandReceived(String command, String rawJSON);
    }

    /**
     * Define o listener para log de comandos.
     *
     * @param listener o listener que recebe as notificações de comandos
     */
    public void setCommandLogListener(CommandLogListener listener) {
        this.commandLogListener = listener;
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
     * Liga ao servidor WebSocket.
     *
     * <p>Fecha qualquer ligação anterior antes de abrir uma nova.
     * Se o URL não tiver prefixo {@code ws://} ou {@code wss://},
     * adiciona automaticamente o prefixo {@code ws://}.
     *
     * @param url o URL do servidor (ex: "uvws.jparreira.dev")
     * @param serialNumber o serial number do drone
     */
    public void connect(String url, String serialNumber) {
        // Fechar a ligação anterior se existir
        disconnect();

        this.serverUrl = url;
        this.droneId = serialNumber;

        // Notificar a UI que estamos a tentar ligar
        notifyConnectionChange(false, "Connecting to " + url + "...");

        OkHttpClient client = new OkHttpClient();

        // Garante que o URL tem o prefixo correto
        String fullUrl = url.startsWith("ws://") || url.startsWith("wss://")
                ? url
                : "ws://" + url;

        Request request = new Request.Builder()
                .url(fullUrl)
                .addHeader("dboidsID", serialNumber)
                .build();

        SocketListener socketListener = new SocketListener(this);
        ws = client.newWebSocket(request, socketListener);
    }

    /**
     * Fecha a ligação WebSocket se estiver aberta.
     */
    public void disconnect() {
        if (ws != null) {
            ws.close(1000, "App closing");
            ws = null;
        }
    }

    /**
     * Envia a telemetria ao servidor.
     *
     * @param jsonStatus o JSON com os dados de telemetria
     */
    public void sendStatus(String jsonStatus) {
        if (ws != null) {
            ws.send(jsonStatus);
        }
    }

    /**
     * Processa uma mensagem recebida do servidor.
     *
     * <p>Extrai o campo "command" do JSON e delega ao {@link #processCommand(String, JSONObject)}.
     *
     * @param text a mensagem JSON recebida
     */
    public void handleRawMessage(String text) {
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
     * @param connected {@code true} se a conexão é bem-sucedida
     * @param msg mensagem a exibir na UI
     */
    public void notifyConnectionChange(boolean connected, String msg) {
        if (listener != null) {
            if (connected) {
                // Incluir o URL na mensagem de sucesso
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
            case "startRTMP":
                listener.onStartRTMP();
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
