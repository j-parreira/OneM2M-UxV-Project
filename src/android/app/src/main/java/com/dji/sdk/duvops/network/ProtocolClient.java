/**
 * {@code ProtocolClient} — Interface de transporte para comunicação com o ACME CSE.
 *
 * Abstrai o protocolo de transporte (WebSocket, MQTT, HTTP, CoAP) da lógica
 * de voo e telemetria. Cada protocolo tem a sua implementação concreta.
 *
 * <p>O ciclo de vida esperado:
 * <pre>
 * connect(host, port, aeId)    → transport establishes connection
 *     [OneM2MSession does AE registration — calls getPoaUrl() for poa field]
 * sendTelemetry(json)          → repeated every 250 ms by TelemetryManager
 * disconnect()                 → closes transport cleanly
 * </pre>
 *
 * <h3>Mensagens raw</h3>
 * <p>O transporte entrega todas as mensagens recebidas do CSE ao {@link RawMessageListener}
 * registado via {@link #setRawMessageListener}. Para HTTP e CoAP, notificações chegam sem
 * wrapper {@code op=5} — o body é directamente {@code {"m2m:sgn":{...}}} e
 * {@link com.dji.sdk.duvops.network.OneM2MSession} trata o formato.
 *
 * <h3>ACKs de notificação</h3>
 * <p>Quando {@link #requiresExplicitNotifyAck()} retorna {@code true} (WebSocket, MQTT),
 * a sessão chama {@link #sendAck(String)} após processar uma notificação. Para HTTP/CoAP
 * o ACK é o próprio HTTP 200 / CoAP 2.04 Changed — sem mensagem adicional.
 *
 * @see com.dji.sdk.duvops.network.NetworkManager      WebSocket implementation
 * @see com.dji.sdk.duvops.network.MqttProtocolClient  MQTT implementation
 * @see com.dji.sdk.duvops.network.HttpProtocolClient  HTTP implementation
 * @see com.dji.sdk.duvops.network.CoApProtocolClient  CoAP implementation
 * @see com.dji.sdk.duvops.network.OneM2MSession       OneM2M session layer
 */
package com.dji.sdk.duvops.network;

public interface ProtocolClient {

    /**
     * Liga ao CSE e começa a receber mensagens.
     *
     * <p>A ligação é estabelecida assincronamente. O resultado é comunicado ao
     * {@link DroneCommandListener} passado no constructor do transporte
     * via {@code onConnectionStatusChange(true/false, message)}.
     *
     * @param host hostname ou IP do CSE (sem prefixo de protocolo)
     * @param port porto do protocolo (8180 WS, 1883 MQTT, 8080 HTTP, 5683 CoAP)
     * @param aeId originator do AE — normalmente "C" + serialNumber (já computado por OneM2MSession)
     */
    void connect(String host, int port, String aeId);

    /** Desliga e liberta recursos. */
    void disconnect();

    /**
     * Envia payload JSON ao CSE.
     *
     * <p>Em modo OneM2M, este método recebe o frame flat JSON completo
     * construído por {@link com.dji.sdk.duvops.network.OneM2MSession}
     * (campos {@code op}, {@code to}, {@code fr}, {@code rqi}, {@code rvi}, {@code ty}, {@code pc}).
     *
     * <p>Para transportes HTTP/CoAP que não usam JSON flat nativo, o transporte
     * extrai os campos relevantes do JSON e constrói o request protocolar adequado.
     *
     * @param jsonPayload frame JSON serializado (formato flat OneM2M ou raw)
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

    /**
     * Regista o listener de mensagens raw.
     *
     * <p>Chamado por {@link com.dji.sdk.duvops.network.OneM2MSession#setTransport}
     * para receber todas as mensagens do CSE (respostas e notificações) sem processamento.
     *
     * @param listener listener a chamar para cada mensagem recebida
     */
    void setRawMessageListener(RawMessageListener listener);

    /**
     * Retorna o URL do Point of Access (poa) deste transporte.
     *
     * <p>Chamado por {@link com.dji.sdk.duvops.network.OneM2MSession#registerAE}
     * após {@link #connect} ter estabelecido a ligação. O valor retornado é incluído
     * no campo {@code poa} do registo do AE — obrigatório para entrega de notificações.
     *
     * <ul>
     *   <li>WebSocket: {@code ws://cseHost:8180}</li>
     *   <li>MQTT: {@code mqtt://cseHost:1883}</li>
     *   <li>HTTP: {@code http://rcWifiIp:callbackPort}</li>
     *   <li>CoAP: {@code coap://rcWifiIp:callbackPort}</li>
     * </ul>
     *
     * @return URL do poa (nunca {@code null})
     */
    String getPoaUrl();

    /**
     * Indica se este transporte requer ACK explícito para notificações OneM2M.
     *
     * <p>WebSocket e MQTT: {@code true} — a sessão envia um JSON com {@code rsc=2000}
     * após cada notificação recebida.
     * HTTP e CoAP: {@code false} — o ACK é o próprio HTTP 200 / CoAP 2.04 Changed
     * devolvido pelo servidor de callback embebido.
     *
     * @return {@code true} se a sessão deve enviar ACK via {@link #sendAck(String)}
     */
    default boolean requiresExplicitNotifyAck() {
        return true;
    }

    /**
     * Indica se {@link com.dji.sdk.duvops.network.OneM2MSession#registerAE()} deve apagar
     * o AE antes de o recriar.
     *
     * <p>MQTT, HTTP, CoAP: {@code true} — o CSE entrega notificações via {@code poa}; a
     * associação de transporte anterior fica obsoleta se o AE não for apagado primeiro.
     * WebSocket: {@code false} — o CSE entrega notificações pela ligação WS activa;
     * apagar o AE enquanto a ligação está aberta faz o CSE fechar a ligação.
     *
     * @return {@code true} se {@code registerAE()} deve enviar DELETE antes de CREATE
     */
    default boolean requiresAeDeleteBeforeRegister() {
        return true;
    }

    /**
     * Envia um ACK de notificação ao CSE.
     *
     * <p>Por defeito delega a {@link #sendTelemetry(String)} — correcto para WebSocket
     * onde tudo vai no mesmo canal. MQTT sobrepõe este método para publicar no
     * tópico de resposta ({@code /oneM2M/resp/...}) em vez do tópico de pedidos.
     * HTTP/CoAP não chamam este método ({@link #requiresExplicitNotifyAck()} = false).
     *
     * @param json ACK JSON serializado ({@code {"rsc":2000,"rqi":"...","to":"...","fr":"..."}})
     */
    default void sendAck(String json) {
        sendTelemetry(json);
    }

    // ── Interfaces de callback ────────────────────────────────────────────────

    /**
     * Callback de entrega de mensagens raw do CSE ao protocolo OneM2M.
     *
     * <p>Implementado por {@link com.dji.sdk.duvops.network.OneM2MSession}
     * via referência de método ({@code this::onRawMessage}).
     */
    interface RawMessageListener {
        /**
         * Chamado para cada mensagem recebida do CSE.
         *
         * @param json mensagem JSON recebida (resposta ou notificação)
         */
        void onRawMessage(String json);
    }

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
