/**
 * {@code CoApProtocolClient} — Transporte CoAP para comunicação com o ACME CSE.
 *
 * Implementa {@link ProtocolClient} sobre CoAP/UDP (Californium 2.7.4).
 * Usa mensagens Confirmable (CON) para garantir entrega. Sem DTLS — ACME CSE
 * v2025.11 não suporta DTLS no binding CoAP.
 *
 * <h3>Mapeamento flat JSON → CoAP (oneM2M TS-0008 CoAP binding)</h3>
 * <pre>
 * Flat JSON field  → CoAP element
 * op=1 (CREATE)   → POST
 * to               → URI path: coap://host:5683/{to}
 * fr               → option 2048 (X-M2M-Origin), ou header no payload
 * rqi              → option 2053 (X-M2M-RI)
 * rvi              → option 2055 (X-M2M-RVI)
 * ty               → Content-Format option + URI query ty={ty}
 * pc               → CoAP payload (JSON)
 * rsc (response)   → option 2050 (X-M2M-RSC) na resposta CoAP
 * </pre>
 *
 * <h3>Simplificação implementada</h3>
 * O ACME CSE v2025.11 aceita os campos oneM2M como JSON no payload (flat JSON
 * como no WebSocket) em vez de opções CoAP separadas. Esta implementação
 * embute todos os campos no payload JSON para maximizar a compatibilidade.
 * O rsc é lido da opção CoAP 2050 se presente, senão mapeado do código de resposta CoAP.
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

        try {
            callbackServer.start();
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
     * Para o servidor de callback CoAP.
     */
    @Override
    public void disconnect() {
        connected = false;
        if (callbackServer != null) {
            callbackServer.destroy();
            callbackServer = null;
        }
    }

    /**
     * Envia um request oneM2M como POST CoAP CON ao CSE.
     *
     * <p>Parseia o frame flat JSON de {@link OneM2MSession} para extrair os campos.
     * O payload CoAP é o valor do campo {@code pc} (conteúdo do recurso).
     * A resposta CoAP é convertida para flat JSON e entregue ao rawMessageListener.
     *
     * @param jsonPayload frame flat JSON oneM2M serializado
     */
    @Override
    public void sendTelemetry(String jsonPayload) {
        if (!connected) return;
        try {
            JSONObject req = new JSONObject(jsonPayload);
            String to  = req.optString("to",  "");
            String fr  = req.optString("fr",  "");
            String rqi = req.optString("rqi", "");
            String rvi = req.optString("rvi", "3");
            int    ty  = req.optInt("ty", 0);
            JSONObject pc = req.optJSONObject("pc");
            String bodyStr = (pc != null) ? pc.toString() : "{}";

            // Construir URI CoAP: coap://host:port/{to}?X-M2M-Origin=fr&...
            String uri = "coap://" + savedHost + ":" + savedPort + "/" + to
                    + "?X-M2M-Origin=" + fr
                    + "&X-M2M-RI=" + rqi
                    + "&X-M2M-RVI=" + rvi;
            if (ty > 0) uri += "&ty=" + ty;

            CoapClient coapClient = new CoapClient(uri);
            coapClient.setTimeout(REQUEST_TIMEOUT_MS);

            final String rqiFinal = rqi;
            // POST assíncrono CON (Confirmable) — handler chamado no thread Californium
            coapClient.post(new CoapHandler() {
                @Override
                public void onLoad(CoapResponse response) {
                    try {
                        // Mapear código de resposta CoAP para rsc oneM2M
                        // 2.01 Created = rsc 2001; 2.04 Changed = rsc 2004; 4.05 Conflict = rsc 4105
                        int rsc = mapCoapCodeToRsc(response.getCode());

                        // Tentar ler resposta oneM2M do payload CoAP
                        String respBody = response.getResponseText();
                        JSONObject flatResp = new JSONObject()
                                .put("rsc", rsc)
                                .put("rqi", rqiFinal);
                        if (respBody != null && !respBody.isEmpty()) {
                            try {
                                flatResp.put("pc", new JSONObject(respBody));
                            } catch (JSONException ignore) { }
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
            }, bodyStr, CONTENT_FORMAT_JSON);

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
        return "coap://" + (wifiIp != null ? wifiIp : "0.0.0.0") + ":" + CALLBACK_PORT;
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
     * @param code código de resposta Californium
     * @return rsc oneM2M correspondente
     */
    private int mapCoapCodeToRsc(CoAP.ResponseCode code) {
        if (code == null) return 0;
        switch (code) {
            case CREATED:  return 2001;
            case CHANGED:  return 2004;
            case DELETED:  return 2002;
            case CONTENT:  return 2000;
            // 4.09 Conflict não existe em CoAP standard — ACME pode retornar 4.03 Forbidden
            case FORBIDDEN: return 4105; // tratar como Conflict (AE/recurso já existe)
            default:       return 5000;  // erro genérico
        }
    }

    /**
     * Obtém o endereço IP do RC na rede WiFi.
     *
     * @return IP no formato dotted-decimal, ou "0.0.0.0" se não disponível
     */
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
