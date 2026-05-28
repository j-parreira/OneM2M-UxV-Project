/**
 * {@code CoApProtocolClient} — Transporte CoAP para comunicação com o ACME CSE.
 *
 * Implementa {@link ProtocolClient} sobre CoAP/UDP (Californium 2.7.4).
 * Usa mensagens Confirmable (CON) para garantir entrega. Sem DTLS — ACME CSE
 * v2025.11 não suporta DTLS no binding CoAP.
 *
 * <h3>Mapeamento flat JSON → CoAP</h3>
 * <pre>
 * op=1 (CREATE)   → CoAP POST
 * to               → URI path: coap://host:5683/{to}
 * body             → flat JSON completo (mesmo formato do WebSocket binding)
 * rsc (resposta)   → mapeado do código de resposta CoAP (2.01→2001, 2.04→2004…)
 *                    ou lido do body flat JSON se o CSE o incluir
 * </pre>
 *
 * <h3>Formato do payload</h3>
 * O ACME CSE v2025.11 aceita flat JSON no payload CoAP (mesmos campos do binding
 * WebSocket). Todos os campos oneM2M ({@code op, to, fr, rqi, rvi, ty, pc}) vão
 * no body — sem query params nem opções CoAP separadas.
 * O endpoint outgoing é partilhado ({@code sharedEndpoint}) para reutilizar o
 * socket UDP entre requests consecutivos.
 *
 * <h3>Notificações push</h3>
 * O CSE envia PUT/POST CoAP para {@code coap://rcIp:callbackPort/notify} com
 * body {@code {"m2m:sgn":{...}}}. O servidor responde 2.04 Changed — esse é o ACK.
 * Por isso {@link #requiresExplicitNotifyAck()} retorna {@code false}.
 *
 * @see com.dji.sdk.duvops.network.ProtocolClient
 * @see com.dji.sdk.duvops.network.OneM2MSession
 */
package com.dji.sdk.duvops.network;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Transporte CoAP oneM2M com servidor de callback Californium embebido.
 *
 * <p>Utiliza Californium 2.7.4 (Java 8 compatível). Californium 3.x requer Java 11
 * que é incompatível com {@code compileOptions JavaVersion.VERSION_1_8}.
 */
public class CoApProtocolClient implements ProtocolClient {

    private static final String TAG = "CoApProtocolClient";

    /** Porto do servidor de callback CoAP (UDP). */
    private static final int CALLBACK_PORT = 5684;

    /** Content-Format 50 = application/json (RFC 7252). */
    private static final int CONTENT_FORMAT_JSON = MediaTypeRegistry.APPLICATION_JSON;

    /** Timeout de request CoAP em ms (Californium default = 32000 ms). */
    private static final long REQUEST_TIMEOUT_MS = 10_000L;

    // ── Dependências ─────────────────────────────────────────────────────────

    private final Context context;
    private final DroneCommandListener listener;

    // ── Estado ───────────────────────────────────────────────────────────────

    private CoapServer callbackServer;
    /** Endpoint partilhado para pedidos CoAP outgoing — evita criar novo socket por request. */
    private CoapEndpoint sharedEndpoint;
    private volatile boolean connected = false;

    private String savedHost;
    private int savedPort;
    private String wifiIp;

    // ── Listeners ────────────────────────────────────────────────────────────

    private ProtocolClient.RawMessageListener rawMessageListener;
    private ProtocolClient.CommandLogListener commandLogListener;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param context contexto Android (para WifiManager)
     * @param listener receptor de eventos de conexão (normalmente {@link OneM2MSession})
     */
    public CoApProtocolClient(Context context, DroneCommandListener listener) {
        this.context  = context;
        this.listener = listener;
    }

    // ── ProtocolClient ───────────────────────────────────────────────────────

    /**
     * Inicia o servidor CoAP de callback e notifica o listener.
     *
     * <p>CoAP sobre UDP é connectionless — "connect" significa:
     * 1. Obter IP WiFi do RC.
     * 2. Iniciar {@code CoapServer} na porta {@value CALLBACK_PORT} (UDP).
     * 3. Notificar o listener com {@code onConnectionStatusChange(true)}.
     *
     * @param host hostname ou IP do CSE
     * @param port porto CoAP do CSE (normalmente 5683)
     * @param aeId originator do AE (para logging)
     */
    @Override
    public void connect(String host, int port, String aeId) {
        disconnect();

        savedHost = host;
        savedPort = port;
        wifiIp    = getWifiIpAddress();

        callbackServer = new CoapServer();
        // Adicionar resource /notify que recebe as notificações push do CSE
        callbackServer.add(new NotifyResource());
        // Bind na porta UDP de callback em todas as interfaces
        callbackServer.addEndpoint(new CoapEndpoint.Builder()
                .setInetSocketAddress(new InetSocketAddress("0.0.0.0", CALLBACK_PORT))
                .build());

        // Endpoint outgoing com porta efémera — partilhado por todos os pedidos
        sharedEndpoint = new CoapEndpoint.Builder()
                .setInetSocketAddress(new InetSocketAddress("0.0.0.0", 0))
                .build();

        try {
            callbackServer.start();
            sharedEndpoint.start();
            connected = true;
            Log.d(TAG, "CoAP ready — callback server on " + wifiIp + ":" + CALLBACK_PORT);
            listener.onConnectionStatusChange(true,
                    "CoAP connected (callback: " + wifiIp + ":" + CALLBACK_PORT + ")");
        } catch (Exception e) {
            Log.e(TAG, "CoAP server start failed: " + e.getMessage());
            listener.onConnectionStatusChange(false,
                    "CoAP callback server failed: " + e.getMessage());
        }
    }

    /**
     * Para o servidor de callback CoAP e o endpoint outgoing.
     */
    @Override
    public void disconnect() {
        connected = false;
        if (callbackServer != null) {
            callbackServer.destroy();
            callbackServer = null;
        }
        if (sharedEndpoint != null) {
            sharedEndpoint.destroy();
            sharedEndpoint = null;
        }
    }

    /**
     * Envia um request oneM2M como POST CoAP CON ao CSE.
     *
     * <p>O payload CoAP é o flat JSON completo (mesmo formato do WebSocket binding).
     * O ACME CSE v2025.11 aceita flat JSON no body CoAP em vez de opções CoAP separadas.
     * O endpoint outgoing é partilhado entre todos os pedidos (reutiliza o socket UDP).
     *
     * @param jsonPayload frame flat JSON oneM2M serializado
     */
    @Override
    public void sendTelemetry(String jsonPayload) {
        if (!connected || sharedEndpoint == null) return;
        try {
            JSONObject req = new JSONObject(jsonPayload);
            String to  = req.optString("to",  "");
            String rqi = req.optString("rqi", "");

            // URI: path apenas (sem query params) — campos oneM2M vão no body flat JSON
            String uri = "coap://" + savedHost + ":" + savedPort + "/" + to;

            CoapClient coapClient = new CoapClient(uri);
            // Reutilizar endpoint partilhado — evita criar novo socket UDP por request
            coapClient.setEndpoint(sharedEndpoint);
            coapClient.setTimeout(REQUEST_TIMEOUT_MS);

            final String rqiFinal = rqi;
            // POST assíncrono CON (Confirmable) com o flat JSON completo como body
            coapClient.post(new CoapHandler() {
                @Override
                public void onLoad(CoapResponse response) {
                    try {
                        String respBody = response.getResponseText();
                        JSONObject flatResp;
                        if (respBody != null && !respBody.isEmpty()) {
                            try {
                                JSONObject parsed = new JSONObject(respBody);
                                if (parsed.has("rsc")) {
                                    // ACME CSE retornou flat JSON com rsc — usar directamente
                                    if (!parsed.has("rqi")) parsed.put("rqi", rqiFinal);
                                    flatResp = parsed;
                                } else {
                                    // Body é conteúdo do recurso — mapear código CoAP para rsc
                                    int rsc = mapCoapCodeToRsc(response.getCode());
                                    flatResp = new JSONObject()
                                            .put("rsc", rsc)
                                            .put("rqi", rqiFinal)
                                            .put("pc", parsed);
                                }
                            } catch (JSONException e) {
                                // Body não é JSON válido — mapear código CoAP
                                int rsc = mapCoapCodeToRsc(response.getCode());
                                flatResp = new JSONObject()
                                        .put("rsc", rsc)
                                        .put("rqi", rqiFinal);
                            }
                        } else {
                            // Resposta sem body — mapear código CoAP para rsc
                            int rsc = mapCoapCodeToRsc(response.getCode());
                            flatResp = new JSONObject()
                                    .put("rsc", rsc)
                                    .put("rqi", rqiFinal);
                        }

                        if (rawMessageListener != null) {
                            rawMessageListener.onRawMessage(flatResp.toString());
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "CoAP response parse: " + e.getMessage());
                    }
                }

                @Override
                public void onError() {
                    Log.e(TAG, "CoAP request failed rqi=" + rqiFinal);
                    // Sem resposta → timeout de OneM2MSession vai disparar
                }
            }, jsonPayload, CONTENT_FORMAT_JSON);

        } catch (JSONException e) {
            Log.e(TAG, "sendTelemetry parse: " + e.getMessage());
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isConnected() {
        return connected;
    }

    /** {@inheritDoc} */
    @Override
    public void setCommandLogListener(ProtocolClient.CommandLogListener l) {
        this.commandLogListener = l;
    }

    /** {@inheritDoc} */
    @Override
    public void setRawMessageListener(ProtocolClient.RawMessageListener l) {
        this.rawMessageListener = l;
    }

    /**
     * @return URL do poa no formato {@code coap://rcIp:callbackPort}
     */
    @Override
    public String getPoaUrl() {
        // /notify path is required — Californium CoapServer has no catch-all handler
        return "coap://" + (wifiIp != null ? wifiIp : "0.0.0.0") + ":" + CALLBACK_PORT + "/notify";
    }

    /**
     * CoAP: o ACK é o CoAP 2.04 Changed devolvido pelo servidor de callback — sem JSON adicional.
     *
     * @return {@code false}
     */
    @Override
    public boolean requiresExplicitNotifyAck() {
        return false;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Mapeia código de resposta CoAP para código de resposta oneM2M (rsc).
     *
     * <p>ACME CSE v2025.11 tipicamente devolve flat JSON com {@code rsc} no body —
     * este mapeamento é usado apenas quando o body está vazio ou sem campo {@code rsc}.
     * O {@code Log.d} abaixo permite verificar empiricamente o que o CSE envia.
     *
     * @param code código de resposta Californium
     * @return rsc oneM2M correspondente
     */
    private int mapCoapCodeToRsc(CoAP.ResponseCode code) {
        if (code == null) return 0;
        // Diagnóstico: log do código raw para verificação empírica contra ACME CSE v2025.11
        Log.d(TAG, "CoAP response code: " + code + " (value=" + code.value + ")");
        switch (code) {
            case CREATED:   return 2001;
            case CHANGED:   return 2004; // 2.04 — verificar empiricamente se CSE usa flat JSON
            case DELETED:   return 2002;
            case CONTENT:   return 2000;
            case CONFLICT:  return 4105; // 4.09 — ACME CSE: recurso já existe
            // 4.03 Forbidden — ACME CSE pode enviar este em vez de 4.09 para recursos existentes
            case FORBIDDEN: return 4105;
            default:        return 5000; // erro genérico
        }
    }

    /**
     * Obtém o endereço IP do RC na rede WiFi.
     *
     * <p>{@code WifiManager.getConnectionInfo()} está deprecated desde API 31, mas o RC
     * corre Android ≤ API 29 pelo que a API está disponível e funcional neste target.
     *
     * @return IP no formato dotted-decimal, ou "0.0.0.0" se não disponível
     */
    @SuppressWarnings("deprecation")
    private String getWifiIpAddress() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "0.0.0.0";
            int ipInt = wm.getConnectionInfo().getIpAddress();
            return String.format("%d.%d.%d.%d",
                    ipInt & 0xFF,
                    (ipInt >> 8)  & 0xFF,
                    (ipInt >> 16) & 0xFF,
                    (ipInt >> 24) & 0xFF);
        } catch (Exception e) {
            Log.e(TAG, "getWifiIpAddress: " + e.getMessage());
            return "0.0.0.0";
        }
    }

    /**
     * Resource CoAP que recebe notificações push do CSE no path {@code /notify}.
     *
     * <p>O CSE envia POST com body {@code {"m2m:sgn":{...}}}.
     * A resposta 2.04 Changed é o ACK da notificação.
     */
    private class NotifyResource extends CoapResource {

        NotifyResource() {
            super("notify");
        }

        @Override
        public void handlePOST(CoapExchange exchange) {
            String json = exchange.getRequestText();
            if (json != null && !json.isEmpty() && rawMessageListener != null) {
                rawMessageListener.onRawMessage(json);
            }
            // 2.04 Changed = ACK da notificação (requiresExplicitNotifyAck = false)
            exchange.respond(CoAP.ResponseCode.CHANGED);
        }

        @Override
        public void handlePUT(CoapExchange exchange) {
            // ACME CSE pode usar PUT em vez de POST para notificações
            handlePOST(exchange);
        }
    }
}
