/**
 * {@code HttpProtocolClient} — Transporte HTTP para comunicação com o ACME CSE.
 *
 * Implementa {@link ProtocolClient} sobre HTTP/1.1 (OkHttp). Envia pedidos oneM2M
 * como POST REST e recebe notificações push via servidor HTTP embebido (NanoHTTPD).
 *
 * <h3>Mapeamento flat JSON → HTTP (oneM2M TS-0010 HTTP binding)</h3>
 * <pre>
 * Flat JSON field  → HTTP element
 * op=1 (CREATE)   → POST
 * to               → URL path: http://host:port/{to}
 * fr               → X-M2M-Origin header
 * rqi              → X-M2M-RI header
 * rvi              → X-M2M-RVI header
 * ty               → Content-Type: application/json;ty={ty}
 * pc               → HTTP body (JSON)
 * rsc (response)   → X-M2M-RSC response header
 * </pre>
 *
 * <h3>Notificações push</h3>
 * O CSE faz POST para {@code http://rcIp:callbackPort} com body {@code {"m2m:sgn":{...}}}.
 * O servidor NanoHTTPD responde HTTP 200 — esse é o ACK (sem JSON adicional).
 * Por isso {@link #requiresExplicitNotifyAck()} retorna {@code false}.
 *
 * <h3>Reachability</h3>
 * O container Docker do CSE deve conseguir atingir o IP do RC na LAN WiFi.
 * O IP é obtido via {@code WifiManager} na chamada a {@link #connect}.
 *
 * @see com.dji.sdk.duvops.network.ProtocolClient
 * @see com.dji.sdk.duvops.network.OneM2MSession
 */
package com.dji.sdk.duvops.network;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Transporte HTTP oneM2M com callback server NanoHTTPD embebido.
 */
public class HttpProtocolClient implements ProtocolClient {

    private static final String TAG = "HttpProtocolClient";

    /** Porto do servidor de callback NanoHTTPD. */
    private static final int CALLBACK_PORT = 8181;

    // ── Dependências ─────────────────────────────────────────────────────────

    /** Contexto Android (para WifiManager). */
    private final Context context;

    /** Listener de estado e comandos (normalmente {@link OneM2MSession}). */
    private final DroneCommandListener listener;

    // ── Estado ───────────────────────────────────────────────────────────────

    private OkHttpClient httpClient;
    private NotifyServer callbackServer;
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
    public HttpProtocolClient(Context context, DroneCommandListener listener) {
        this.context  = context;
        this.listener = listener;
    }

    // ── ProtocolClient ───────────────────────────────────────────────────────

    /**
     * Inicia o cliente HTTP e o servidor de callback NanoHTTPD.
     *
     * <p>HTTP não tem ligação persistente — "connect" significa:
     * 1. Obter IP WiFi do RC (necessário para o campo {@code poa}).
     * 2. Iniciar o servidor de callback na porta {@value CALLBACK_PORT}.
     * 3. Notificar o listener com {@code onConnectionStatusChange(true)}.
     *
     * @param host hostname ou IP do CSE
     * @param port porto HTTP do CSE (normalmente 8080)
     * @param aeId originator do AE (passado apenas para logging; não usado no connect HTTP)
     */
    @Override
    public void connect(String host, int port, String aeId) {
        disconnect();

        savedHost = host;
        savedPort = port;
        wifiIp    = getWifiIpAddress();

        // OkHttp client com timeouts configurados
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        // Iniciar servidor NanoHTTPD para receber notificações push do CSE
        callbackServer = new NotifyServer(CALLBACK_PORT);
        try {
            callbackServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            connected = true;
            Log.d(TAG, "HTTP ready — callback server on " + wifiIp + ":" + CALLBACK_PORT);
            listener.onConnectionStatusChange(true,
                    "HTTP connected (callback: " + wifiIp + ":" + CALLBACK_PORT + ")");
        } catch (IOException e) {
            Log.e(TAG, "NanoHTTPD start failed: " + e.getMessage());
            listener.onConnectionStatusChange(false,
                    "HTTP callback server failed: " + e.getMessage());
        }
    }

    /**
     * Para o servidor de callback e liberta o OkHttpClient.
     */
    @Override
    public void disconnect() {
        connected = false;
        if (callbackServer != null) {
            callbackServer.stop();
            callbackServer = null;
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient = null;
        }
    }

    /**
     * Envia um request oneM2M como POST HTTP ao CSE.
     *
     * <p>Parseia o frame flat JSON recebido de {@link OneM2MSession} e mapeia
     * cada campo para o elemento HTTP correspondente (URL path, headers, body).
     * A resposta é convertida de volta para flat JSON e entregue ao
     * {@link #rawMessageListener} para que {@link OneM2MSession} a processe.
     *
     * @param jsonPayload frame flat JSON oneM2M serializado
     */
    @Override
    public void sendTelemetry(String jsonPayload) {
        if (httpClient == null) return;
        try {
            // Parsear campos do frame flat oneM2M
            JSONObject req = new JSONObject(jsonPayload);
            String to  = req.optString("to",  "");
            String fr  = req.optString("fr",  "");
            String rqi = req.optString("rqi", "");
            String rvi = req.optString("rvi", "3");
            int    ty  = req.optInt("ty", 0);
            JSONObject pc = req.optJSONObject("pc");
            String bodyStr = (pc != null) ? pc.toString() : "{}";

            // Construir URL: http://host:port/{to}
            String url = "http://" + savedHost + ":" + savedPort + "/" + to;

            // Content-Type com ty embebido (ex: application/json;ty=4)
            String contentType = (ty > 0)
                    ? "application/json;ty=" + ty
                    : "application/json";

            RequestBody body = RequestBody.create(
                    MediaType.parse(contentType),
                    bodyStr.getBytes()
            );

            Request httpReq = new Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("X-M2M-Origin", fr)
                    .addHeader("X-M2M-RI",     rqi)
                    .addHeader("X-M2M-RVI",    rvi)
                    .addHeader("Accept",        "application/json")
                    .build();

            // Execução assíncrona — não bloqueia o timer de telemetria
            final String rqiFinal = rqi;
            httpClient.newCall(httpReq).enqueue(new Callback() {
                @Override
                public void onResponse(@androidx.annotation.NonNull Call call,
                                       @androidx.annotation.NonNull Response response) {
                    try {
                        // Ler X-M2M-RSC — este header contém o código oneM2M real
                        // (NÃO mapear a partir do HTTP status code — os dois não coincidem)
                        String rscStr = response.header("X-M2M-RSC", "0");
                        int rsc = 0;
                        try { rsc = Integer.parseInt(rscStr); } catch (NumberFormatException ignore) {}

                        // Ler body da resposta (pode estar vazio para CINs de telemetria)
                        String respBody = "";
                        if (response.body() != null) respBody = response.body().string();

                        // Construir flat JSON para OneM2MSession processar como resposta normal
                        JSONObject flatResp = new JSONObject()
                                .put("rsc", rsc)
                                .put("rqi", rqiFinal);
                        if (!respBody.isEmpty()) {
                            try {
                                flatResp.put("pc", new JSONObject(respBody));
                            } catch (JSONException ignore) { }
                        }

                        if (rawMessageListener != null) {
                            rawMessageListener.onRawMessage(flatResp.toString());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "HTTP response parse: " + e.getMessage());
                    } finally {
                        if (response.body() != null) response.body().close();
                    }
                }

                @Override
                public void onFailure(@androidx.annotation.NonNull Call call,
                                      @androidx.annotation.NonNull IOException e) {
                    Log.e(TAG, "HTTP request failed rqi=" + rqiFinal + ": " + e.getMessage());
                    // Sem resposta → o timeout de request em OneM2MSession vai disparar
                }
            });

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
     * @return URL do poa no formato {@code http://rcIp:callbackPort}
     */
    @Override
    public String getPoaUrl() {
        return "http://" + (wifiIp != null ? wifiIp : "0.0.0.0") + ":" + CALLBACK_PORT;
    }

    /**
     * HTTP: o ACK é o próprio HTTP 200 devolvido pelo servidor de callback — sem JSON adicional.
     *
     * @return {@code false}
     */
    @Override
    public boolean requiresExplicitNotifyAck() {
        return false;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Obtém o endereço IP do RC na rede WiFi.
     *
     * <p>Os bytes do IP no Android estão em little-endian (LSB primeiro).
     * {@code WifiManager.getConnectionInfo()} está deprecated desde API 31, mas o RC
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
            // Android usa little-endian (byte 0 = octeto menos significativo)
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
     * Servidor HTTP embebido (NanoHTTPD) que recebe notificações push do CSE.
     *
     * <p>O CSE faz POST com body {@code {"m2m:sgn":{...}}}. A resposta HTTP 200
     * serve como ACK — não é necessário enviar JSON adicional.
     */
    private class NotifyServer extends NanoHTTPD {

        NotifyServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {
                // Ler body do POST
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);
                String json = body.get("postData");
                if (json != null && !json.isEmpty() && rawMessageListener != null) {
                    rawMessageListener.onRawMessage(json);
                }
            } catch (Exception e) {
                Log.e(TAG, "NotifyServer: " + e.getMessage());
            }
            // HTTP 200 = ACK da notificação (requiresExplicitNotifyAck = false)
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
        }
    }
}
