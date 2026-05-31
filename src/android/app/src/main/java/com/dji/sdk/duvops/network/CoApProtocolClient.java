/**
 * {@code CoApProtocolClient} — Transporte CoAP para comunicação com o ACME CSE.
 *
 * Implementa {@link ProtocolClient} sobre CoAP/UDP (Californium 2.7.4).
 * Usa mensagens Confirmable (CON) para garantir entrega. Sem DTLS — ACME CSE
 * v2025.11 não suporta DTLS no binding CoAP.
 *
 * <h3>Mapeamento flat JSON → CoAP (TS-0010 CoAP binding)</h3>
 * <pre>
 * op=1 (CREATE)   → CoAP POST  + Content-Format=50 (application/json)
 * op=3 (UPDATE)   → CoAP PUT   + Content-Format=50
 * op=4 (DELETE)   → CoAP DELETE (sem body)
 * to               → URI path: coap://host:5683/{to}  ("id-in" → "cse-in" para CREATE AE)
 * fr               → CoAP option 279 (oneM2M-FR, STRING)
 * rqi              → CoAP option 283 (oneM2M-RQI, STRING)
 * rvi              → CoAP option 271 (oneM2M-RVI, STRING)
 * ty               → CoAP option 267 (oneM2M-TY, INTEGER — só em CREATE)
 * pc               → body: apenas o conteúdo do recurso (ex: {"m2m:cin":{...}})
 * rsc (resposta)   → CoAP option 307 (oneM2M-RSC, INTEGER) — lido das opções, não do body
 * </pre>
 *
 * <h3>Números de opções (fonte: ACME CSE v2025.11 CoAPthonTools.py)</h3>
 * <pre>
 * 267 = oneM2M-TY  (INTEGER)  279 = oneM2M-FR  (STRING)
 * 271 = oneM2M-RVI (STRING)   283 = oneM2M-RQI (STRING)
 * 307 = oneM2M-RSC (INTEGER — lido da resposta)
 * </pre>
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
import org.eclipse.californium.core.coap.Option;
import org.eclipse.californium.core.coap.Request;
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
 *
 * <p>Diferentemente do WebSocket binding (flat JSON completo no body), o binding CoAP
 * do ACME CSE v2025.11 usa opções CoAP dedicadas para os metadados oneM2M e o body
 * contém apenas a representação do recurso (campo {@code pc} do flat JSON).
 */
public class CoApProtocolClient implements ProtocolClient {

    private static final String TAG = "CoApProtocolClient";

    /** Porto do servidor de callback CoAP (UDP). */
    private static final int CALLBACK_PORT = 5684;

    /** Content-Format 50 = application/json (RFC 7252). */
    private static final int CONTENT_FORMAT_JSON = MediaTypeRegistry.APPLICATION_JSON;

    /** Timeout de request CoAP em ms (Californium default = 32000 ms). */
    private static final long REQUEST_TIMEOUT_MS = 10_000L;

    /**
     * Números de opções CoAP oneM2M (fonte: ACME CSE v2025.11 CoAPthonTools.py).
     * Confirmados empiricamente a partir do código fonte do contentor Docker.
     */
    private static final int OPT_TY  = 267;  // oneM2M-TY  (INTEGER — só em CREATE)
    private static final int OPT_RVI = 271;  // oneM2M-RVI (STRING — release version)
    private static final int OPT_FR  = 279;  // oneM2M-FR  (STRING — originator)
    private static final int OPT_RQI = 283;  // oneM2M-RQI (STRING — request ID)
    private static final int OPT_RSC = 307;  // oneM2M-RSC (INTEGER — lido da resposta)

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
     * Envia um request oneM2M ao CSE usando o binding CoAP correcto (TS-0010).
     *
     * <p>Os campos oneM2M (fr, rqi, rvi, ty) vão em opções CoAP dedicadas.
     * O body contém apenas o conteúdo do recurso (campo {@code pc} do flat JSON).
     * O endpoint outgoing partilhado é reutilizado para evitar criar socket UDP por request.
     *
     * <p>Mapeamento de operações:
     * <ul>
     *   <li>op=1 (CREATE) → CoAP POST + Content-Format=50</li>
     *   <li>op=3 (UPDATE) → CoAP PUT  + Content-Format=50</li>
     *   <li>op=4 (DELETE) → CoAP DELETE (sem body)</li>
     * </ul>
     *
     * @param jsonPayload frame flat JSON oneM2M serializado
     */
    @Override
    public void sendTelemetry(String jsonPayload) {
        if (!connected || sharedEndpoint == null) return;
        try {
            JSONObject req = new JSONObject(jsonPayload);
            String to  = req.optString("to",  "");
            String fr  = req.optString("fr",  "");
            String rqi = req.optString("rqi", "");
            String rvi = req.optString("rvi", "3");
            int    op  = req.optInt("op", 1);
            int    ty  = req.optInt("ty", 0);
            JSONObject pc = req.optJSONObject("pc");

            // "id-in" (CSE-ID) → "cse-in" (CSE resource name) no URI path, igual ao HTTP binding.
            // ACME CSE v2025.11 retorna 4.00 se o path for "id-in" em CREATE.
            String coapPath = "id-in".equals(to) ? "cse-in" : to;
            String uri = "coap://" + savedHost + ":" + savedPort + "/" + coapPath;

            // Seleccionar método CoAP conforme a operação oneM2M
            Request coapReq;
            if (op == 4) {
                coapReq = Request.newDelete();
            } else if (op == 3) {
                coapReq = Request.newPut();
            } else {
                coapReq = Request.newPost();
            }
            coapReq.setType(CoAP.Type.CON);
            coapReq.setURI(uri);

            // Body = só o conteúdo do recurso (campo pc), nunca o flat JSON completo
            if (op != 4 && pc != null) {
                coapReq.setPayload(pc.toString());
                coapReq.getOptions().setContentFormat(CONTENT_FORMAT_JSON);
            }

            // Opção 279 — oneM2M-FR: originator (STRING)
            Option frOpt = new Option(OPT_FR);
            frOpt.setStringValue(fr);
            coapReq.getOptions().addOption(frOpt);

            // Opção 283 — oneM2M-RQI: request identifier (STRING)
            Option rqiOpt = new Option(OPT_RQI);
            rqiOpt.setStringValue(rqi);
            coapReq.getOptions().addOption(rqiOpt);

            // Opção 271 — oneM2M-RVI: release version indicator (STRING)
            Option rviOpt = new Option(OPT_RVI);
            rviOpt.setStringValue(rvi);
            coapReq.getOptions().addOption(rviOpt);

            // Opção 267 — oneM2M-TY: resource type (INTEGER — só em CREATE)
            if (op == 1 && ty > 0) {
                Option tyOpt = new Option(OPT_TY);
                tyOpt.setIntegerValue(ty);
                coapReq.getOptions().addOption(tyOpt);
            }

            CoapClient coapClient = new CoapClient(uri);
            // Reutilizar endpoint partilhado — evita criar novo socket UDP por request
            coapClient.setEndpoint(sharedEndpoint);
            coapClient.setTimeout(REQUEST_TIMEOUT_MS);

            final String rqiFinal = rqi;

            // advanced() envia o request com as opções e body exactamente como construído
            coapClient.advanced(new CoapHandler() {
                @Override
                public void onLoad(CoapResponse response) {
                    try {
                        // RSC está na opção 307 (oneM2M-RSC, INTEGER) — não no body
                        int rsc = 0;
                        for (Option opt : response.getOptions().getOthers()) {
                            if (opt.getNumber() == OPT_RSC) {
                                rsc = opt.getIntegerValue();
                                break;
                            }
                        }
                        // Fallback: mapear código CoAP se a opção RSC não estiver presente
                        if (rsc == 0) {
                            rsc = mapCoapCodeToRsc(response.getCode());
                        }

                        JSONObject flatResp = new JSONObject()
                                .put("rsc", rsc)
                                .put("rqi", rqiFinal);

                        // Incluir body da resposta (representação do recurso criado/actualizado)
                        String respBody = response.getResponseText();
                        if (respBody != null && !respBody.isEmpty()) {
                            try {
                                flatResp.put("pc", new JSONObject(respBody));
                            } catch (JSONException ignore) {}
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
            }, coapReq);

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
    /**
     * Fallback: mapeia código de resposta CoAP para código de resposta oneM2M (rsc).
     *
     * <p>Usado apenas quando a opção 307 (oneM2M-RSC) não está presente na resposta.
     * ACME CSE v2025.11 inclui sempre a opção 307 — este mapeamento é para robustez.
     *
     * @param code código de resposta Californium
     * @return rsc oneM2M correspondente
     */
    private int mapCoapCodeToRsc(CoAP.ResponseCode code) {
        if (code == null) return 0;
        Log.d(TAG, "CoAP RSC fallback (no opt 307): " + code + " (value=" + code.value + ")");
        switch (code) {
            case CREATED:     return 2001;
            case CHANGED:     return 2004;
            case DELETED:     return 2002;
            case CONTENT:     return 2000;
            case BAD_REQUEST: return 4000;
            case NOT_FOUND:   return 4004;
            case CONFLICT:    return 4105;
            case FORBIDDEN:   return 4105;
            default:          return 5000;
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
