/**
 * {@code MqttProtocolClient} — Transporte MQTT para comunicação com o ACME CSE.
 *
 * Implementa {@link ProtocolClient} sobre MQTT (Paho mqttv3). Liga ao broker
 * Mosquitto que serve o ACME CSE, publica pedidos oneM2M no tópico de request
 * e recebe respostas e notificações por subscrição.
 *
 * <h3>Tópicos MQTT (oneM2M TS-0010 MQTT binding)</h3>
 * <pre>
 * TOPIC_REQ   = /oneM2M/req/{aeOriginator}/id-in/json   ← AE → CSE (requests)
 * TOPIC_RESP  = /oneM2M/resp/{aeOriginator}/id-in/json  ← CSE → AE (responses) + AE → CSE (ACKs)
 * TOPIC_NOTIF = /oneM2M/req/id-in/{aeOriginator}/json   ← CSE → AE (notifications push)
 * </pre>
 *
 * <h3>Ciclo de vida</h3>
 * <pre>
 * connect(host, 1883, aeOriginator)
 *   → MqttAsyncClient.connect()
 *   → onSuccess → subscribe(TOPIC_RESP, TOPIC_NOTIF)
 *   → listener.onConnectionStatusChange(true) → OneM2MSession regista AE
 * sendTelemetry(json) → publish TOPIC_REQ  (all oneM2M requests: AE reg, CIN, SUB)
 * sendAck(json)       → publish TOPIC_RESP (ACK de notificações — override do default)
 * messageArrived      → rawMessageListener.onRawMessage() (both TOPIC_RESP + TOPIC_NOTIF)
 * </pre>
 *
 * @see com.dji.sdk.duvops.network.ProtocolClient
 * @see com.dji.sdk.duvops.network.OneM2MSession
 */
package com.dji.sdk.duvops.network;

import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Transporte MQTT que implementa {@link ProtocolClient}.
 *
 * <p>Usa {@code MqttAsyncClient} (pure Java, sem Android Service) com
 * {@code MemoryPersistence} — sem persistência em disco, adequado para
 * mensagens transientes de telemetria e comandos.
 */
public class MqttProtocolClient implements ProtocolClient {

    private static final String TAG = "MqttProtocolClient";

    /** CSE-ID usado no tópico MQTT (deve coincidir com {@code cseID} no acme.ini). */
    private static final String CSE_ID = "id-in";

    /** Timeout de ligação em segundos. */
    private static final int CONNECT_TIMEOUT_S = 10;

    /** Keepalive em segundos — mantém a ligação TCP activa. */
    private static final int KEEPALIVE_S = 60;

    // ── Estado da ligação ────────────────────────────────────────────────────

    /** Cliente MQTT Paho (async, thread-safe). */
    private MqttAsyncClient mqttClient;

    /** {@code true} quando subscrito e pronto para enviar/receber. */
    private volatile boolean connected = false;

    // ── Parâmetros guardados para getPoaUrl() ────────────────────────────────

    private String savedHost;
    private int savedPort;

    // ── Tópicos MQTT (calculados em connect()) ───────────────────────────────

    /** Tópico de pedidos AE → CSE. */
    private String topicReq;

    /** Tópico de respostas e ACKs (ambos os sentidos, mesmo tópico). */
    private String topicResp;

    /** Tópico de notificações push CSE → AE. */
    private String topicNotif;

    // ── Listeners ────────────────────────────────────────────────────────────

    /** Listener de comandos de voo e estado de conexão (normalmente {@code OneM2MSession}). */
    private final DroneCommandListener listener;

    /** Entrega mensagens raw ao {@link OneM2MSession}. */
    private ProtocolClient.RawMessageListener rawMessageListener;

    /** Listener de debug para inspecção de comandos (opcional). */
    private ProtocolClient.CommandLogListener commandLogListener;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cria o cliente MQTT.
     *
     * @param listener receptor de eventos de conexão e comandos (normalmente {@link OneM2MSession})
     */
    public MqttProtocolClient(DroneCommandListener listener) {
        this.listener = listener;
    }

    // ── ProtocolClient ───────────────────────────────────────────────────────

    /**
     * Liga ao broker MQTT e subscreve os tópicos de resposta e notificação.
     *
     * <p>A ligação é assíncrona. O resultado chega via {@code IMqttActionListener}
     * e é repropagado ao {@link DroneCommandListener} passado no constructor.
     *
     * @param host hostname ou IP do broker MQTT (normalmente o mesmo host do CSE)
     * @param port porto do broker (normalmente 1883)
     * @param aeId originator do AE (ex: {@code C3LKFD12ABC}) — usado como clientId e nos tópicos
     */
    @Override
    public void connect(String host, int port, String aeId) {
        disconnect();

        savedHost = host;
        savedPort = port;

        // Calcular tópicos MQTT para este originator
        topicReq   = "/oneM2M/req/"  + aeId + "/" + CSE_ID + "/json";
        topicResp  = "/oneM2M/resp/" + aeId + "/" + CSE_ID + "/json";
        topicNotif = "/oneM2M/req/"  + CSE_ID + "/" + aeId + "/json";

        try {
            String serverUri = "tcp://" + host + ":" + port;
            // aeId como clientId — único por sessão (o CSE usa clientId para routing)
            mqttClient = new MqttAsyncClient(serverUri, aeId, new MemoryPersistence());

            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    connected = false;
                    String reason = (cause != null) ? cause.getMessage() : "unknown";
                    Log.w(TAG, "MQTT connection lost: " + reason);
                    listener.onConnectionStatusChange(false,
                            "MQTT disconnected: " + reason);
                }

                /**
                 * Entrega todas as mensagens dos tópicos subscritos ao rawMessageListener.
                 * Tanto TOPIC_RESP (respostas a requests) como TOPIC_NOTIF (notificações push)
                 * chegam aqui e são entregues sem filtro — OneM2MSession distingue pelo formato.
                 */
                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    if (rawMessageListener != null) {
                        rawMessageListener.onRawMessage(new String(message.getPayload()));
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // Fire-and-forget para telemetria — sem acção
                }
            });

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);              // sem sessão persistente entre ligações
            opts.setConnectionTimeout(CONNECT_TIMEOUT_S);
            opts.setKeepAliveInterval(KEEPALIVE_S);

            mqttClient.connect(opts, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    // Subscribir antes de notificar OneM2MSession — garante que as respostas
                    // à sequência de registo AE chegam antes de tentar processar
                    try {
                        mqttClient.subscribe(
                                new String[]{topicResp, topicNotif},
                                new int[]{1, 1}  // QoS 1 — at-least-once delivery
                        );
                        connected = true;
                        Log.d(TAG, "MQTT connected: " + host + ":" + port);
                        listener.onConnectionStatusChange(true,
                                "MQTT connected to " + host + ":" + port);
                    } catch (MqttException e) {
                        Log.e(TAG, "MQTT subscribe failed: " + e.getMessage());
                        listener.onConnectionStatusChange(false,
                                "MQTT subscribe failed: " + e.getMessage());
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    connected = false;
                    String reason = (exception != null) ? exception.getMessage() : "unknown";
                    Log.e(TAG, "MQTT connect failed: " + reason);
                    listener.onConnectionStatusChange(false,
                            "MQTT connect failed: " + reason);
                }
            });

        } catch (MqttException e) {
            Log.e(TAG, "MQTT init error: " + e.getMessage());
            listener.onConnectionStatusChange(false, "MQTT error: " + e.getMessage());
        }
    }

    /**
     * Desliga do broker e liberta o cliente.
     */
    @Override
    public void disconnect() {
        connected = false;
        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected()) {
                    // Desligar com quiesce para garantir que mensagens em trânsito são entregues
                    mqttClient.disconnect(0L);
                }
            } catch (MqttException e) {
                Log.w(TAG, "MQTT disconnect: " + e.getMessage());
            } finally {
                try { mqttClient.close(); } catch (MqttException ignored) {}
                mqttClient = null;
            }
        }
    }

    /**
     * Publica um frame JSON oneM2M no tópico de pedidos (AE → CSE).
     *
     * <p>Usado para: registo AE, criação de containers, criação de subscrição, CINs de telemetria.
     * QoS 1 garante entrega ao broker (o broker entrega ao CSE se este estiver subscrito).
     *
     * @param jsonPayload frame JSON serializado (formato flat oneM2M)
     */
    @Override
    public void sendTelemetry(String jsonPayload) {
        publishInternal(topicReq, jsonPayload);
    }

    /**
     * Publica um ACK de notificação no tópico de resposta (AE → CSE).
     *
     * <p>Override do default {@code sendTelemetry()} — para MQTT, os ACKs de
     * notificação devem ir para {@code TOPIC_RESP}, não para {@code TOPIC_REQ}.
     * O CSE subscreve {@code TOPIC_RESP} para receber respostas do AE.
     *
     * @param json ACK JSON serializado ({@code {"rsc":2000,"rqi":"...","to":"...","fr":"..."}})
     */
    @Override
    public void sendAck(String json) {
        publishInternal(topicResp, json);
    }

    /**
     * @return {@code true} se o cliente está ligado e subscrito
     */
    @Override
    public boolean isConnected() {
        return connected;
    }

    /** {@inheritDoc} */
    @Override
    public void setCommandLogListener(ProtocolClient.CommandLogListener listener) {
        this.commandLogListener = listener;
    }

    /** {@inheritDoc} */
    @Override
    public void setRawMessageListener(ProtocolClient.RawMessageListener listener) {
        this.rawMessageListener = listener;
    }

    /**
     * Retorna o URL do Point of Access MQTT deste transporte.
     *
     * @return URL no formato {@code mqtt://host:port}
     */
    @Override
    public String getPoaUrl() {
        return "mqtt://" + savedHost + ":" + savedPort;
    }

    /**
     * MQTT requer ACK explícito — o CSE não tem canal de retorno implícito como em WebSocket.
     *
     * @return {@code true}
     */
    @Override
    public boolean requiresExplicitNotifyAck() {
        return true;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Publica um payload JSON num tópico MQTT com QoS 1.
     *
     * @param topic   tópico alvo
     * @param payload payload JSON serializado
     */
    private void publishInternal(String topic, String payload) {
        if (mqttClient == null || !mqttClient.isConnected() || topic == null) return;
        try {
            MqttMessage msg = new MqttMessage(payload.getBytes());
            msg.setQos(1);
            msg.setRetained(false);
            mqttClient.publish(topic, msg);
        } catch (MqttException e) {
            Log.e(TAG, "MQTT publish to " + topic + ": " + e.getMessage());
        }
    }
}
