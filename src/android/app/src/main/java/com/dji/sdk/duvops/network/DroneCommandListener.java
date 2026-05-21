/**
 * {@code DroneCommandListener} — Interface para comandos do drone.
 *
 * Define os métodos que um listener deve implementar para rececionar
 * comandos vindos do servidor via WebSocket. Cada método mapeia para
 * um comando JSON recebido.
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.network;

/**
 * Interface para rececionar comandos do drone vindos do servidor.
 *
 * <h3>Comandos WebSocket suportados</h3>
 * <table>
 *   <tr><th>Comando JSON</th><th>Método</th></tr>
 *   <tr><td>{@code "takeoff"}</td><td>{@link #onTakeOff()}</td></tr>
 *   <tr><td>{@code "land"}</td><td>{@link #onLand()}</td></tr>
 *   <tr><td>{@code "motors"}</td><td>{@link #onMotors(boolean)}</td></tr>
 *   <tr><td>{@code "startGoHome"}</td><td>{@link #onGoHome()}</td></tr>
 *   <tr><td>{@code "virtualSticks"}</td><td>{@link #onVirtualStickState(boolean)}</td></tr>
 *   <tr><td>{@code "virtualSticksInput"}</td><td>{@link #onVirtualStickInput(float,float,float,float)}</td></tr>
 *   <tr><td>{@code "gpsInput"}</td><td>{@link #onMoveTo(double,double)}</td></tr>
 *   <tr><td>{@code "perform360"}</td><td>{@link #onPerform360()}</td></tr>
 *   <tr><td>{@code "identify"}</td><td>{@link #onIdentify(boolean)}</td></tr>
 *   <tr><td>{@code "startMission"}</td><td>{@link #onStartMission(String,String,int,float,String)}</td></tr>
 *   <tr><td>{@code "stopMission"}</td><td>{@link #onStopMission()}</td></tr>
 *   <tr><td>{@code "pauseMission"}</td><td>{@link #onPauseMission()}</td></tr>
 *   <tr><td>{@code "startRTMP"}</td><td>{@link #onStartRTMP()}</td></tr>
 *   <tr><td>{@code "setZoom"}</td><td>{@link #onSetZoom(float)}</td></tr>
 *   <tr><td>{@code "setCameraMode"}</td><td>{@link #onSetCameraMode(String)}</td></tr>
 * </table>
 */
public interface DroneCommandListener {

    /** Notifica a mudança de estado de conexão com o servidor. */
    void onConnectionStatusChange(boolean isConnected, String message);

    // Comandos de Voo Básicos

    /**
     * Recebe o comando de takeoff.
     */
    void onTakeOff();

    /**
     * Recebe o comando de landing.
     */
    void onLand();

    /**
     * Recebe o comando de ligar/desligar motores.
     *
     * @param on {@code true} para ligar, {@code false} para desligar
     */
    void onMotors(boolean on);

    /**
     * Recebe o comando de GoHome (retornar ao ponto de partida).
     */
    void onGoHome();

    // Comandos de Navegação

    /**
     * Recebe o comando de navegação GPS para as coordenadas fornecidas.
     *
     * @param lat latitude do destino
     * @param lng longitude do destino
     */
    void onMoveTo(double lat, double lng);

    /**
     * Recebe os valores de virtual stick.
     *
     * @param roll   roll (-1.0 a 1.0)
     * @param pitch  pitch (-1.0 a 1.0)
     * @param yaw    yaw (-180.0 a 180.0)
     * @param throttle throttle (-1.0 a 1.0)
     */
    void onVirtualStickInput(float roll, float pitch, float yaw, float throttle);

    /**
     * Liga ou desliga o modo virtual stick.
     *
     * @param enabled {@code true} para ativar, {@code false} para desativar
     */
    void onVirtualStickState(boolean enabled);

    // Comandos Especiais

    /**
     * Recebe o comando de rotação 360° em yaw.
     */
    void onPerform360();

    /**
     * Recebe o comando de identificar (LEDs).
     *
     * @param on {@code true} para ligar LEDs, {@code false} para desligar
     */
    void onIdentify(boolean on);

    // Missões

    /**
     * Recebe o comando de iniciar missão de waypoints.
     *
     * @param startAction ação de início ("takeoff" ou null)
     * @param endAction ação de fim ("land", "goHome" ou null)
     * @param repeat número de repetições da rota
     * @param altitude altitude da missão em metros
     * @param pathJson JSON array com os waypoints: [{"lat": x, "lng": y}, ...]
     */
    void onStartMission(String startAction, String endAction, int repeat, float altitude, String pathJson);

    /**
     * Recebe o comando de parar missão.
     */
    void onStopMission();

    /**
     * Recebe o comando de pausar/retomar missão (toggle).
     */
    void onPauseMission();

    // Streaming

    /**
     * Recebe o comando de iniciar stream RTMP.
     */
    void onStartRTMP();

    // --- Câmara ---

    /**
     * Recebe o comando de zoom.
     *
     * @param factor fator de zoom (1.0 a 32.0)
     */
    void onSetZoom(float factor);

    /**
     * Recebe o comando de modo da câmara.
     *
     * @param mode modo: "RGB", "IR", "SPLIT"
     */
    void onSetCameraMode(String mode);

    // --- Gimbal ---

    /**
     * Recebe o comando de definir o ângulo do gimbal.
     *
     * @param pitch pitch em graus (-90 a +30)
     * @param yaw yaw em graus (-75 a +75)
     * @param mode "absolute" ou "relative" (por defeito "absolute")
     */
    void onGimbalAngle(float pitch, float yaw, String mode);

    /**
     * Recebe o comando de reposição do gimbal à posição neutra.
     */
    void onGimbalReset();
}
