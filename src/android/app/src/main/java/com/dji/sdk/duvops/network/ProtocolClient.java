/**
 * {@code ProtocolClient} — Interface de transporte para comunicação com o ACME CSE.
 *
 * Abstrai o protocolo de transporte (WebSocket, MQTT, HTTP, CoAP) da lógica
 * de voo e telemetria. Cada protocolo tem a sua implementação concreta.
 *
 * <p>O ciclo de vida esperado:
 * <pre>
 * connect(host, port, aeId)  → transport establishes connection
 *     [OneM2MSession does AE registration]
 * sendTelemetry(json)        → repeated every 250 ms by TelemetryManager
 * disconnect()               → closes transport cleanly
 * </pre>
 *
 * @see com.dji.sdk.duvops.network.NetworkManager  WebSocket implementation
 * @see com.dji.sdk.duvops.network.OneM2MSession   OneM2M session layer
 */
package com.dji.sdk.duvops.network;

public interface ProtocolClient {

    /**
     * Liga ao CSE e começa a receber comandos.
     *
     * @param host hostname ou IP do CSE (sem prefixo de protocolo)
     * @param port porto do protocolo (8180 WS, 1883 MQTT, 8080 HTTP, 5683 CoAP)
     * @param aeId identificador do AE — normalmente o serial number do drone
     */
    void connect(String host, int port, String aeId);

    /** Desliga e liberta recursos. */
    void disconnect();

    /**
     * Envia payload de telemetria ao CSE.
     *
     * <p>Em modo OneM2M, este método é interceptado por {@link OneM2MSession}
     * que envolve o payload num {@code m2m:cin} contentInstance antes de
     * passar ao transporte. No transporte raw, envia a string directamente.
     *
     * @param jsonPayload payload serializado (JSON de telemetria ou frame OneM2M)
     */
    void sendTelemetry(String jsonPayload);

    /**
     * Indica se o cliente está ligado e pronto para enviar/receber.
     *
     * <p>Em modo OneM2M, só retorna {@code true} após a sessão estar registada
     * (AE registration + containers + subscription completos).
     *
     * @return {@code true} se operacional
     */
    boolean isConnected();

    /**
     * Define o listener de debug para comandos recebidos.
     *
     * @param listener listener a notificar (pode ser {@code null} para remover)
     */
    void setCommandLogListener(CommandLogListener listener);

    /** Callback de debug para inspecção de comandos recebidos. */
    interface CommandLogListener {
        /**
         * Chamado quando um comando é recebido e parseado pelo protocolo.
         *
         * @param command nome do comando (ex: "takeoff", "setZoom")
         * @param rawJson JSON bruto do comando tal como chegou
         */
        void onCommandReceived(String command, String rawJson);
    }
}
