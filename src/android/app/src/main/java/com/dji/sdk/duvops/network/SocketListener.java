/**
 * {@code SocketListener} — Listener do WebSocket (okhttp3).
 *
 * Responde aos eventos do ciclo de vida do WebSocket (open, message, close, error)
 * e notifica o {@link NetworkManager} para encadear a resposta.
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.network;

import android.util.Log;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * WebSocket listener para eventos de conexão.
 *
 * <h3>Ciclo de vida do WebSocket</h3>
 * <pre>
 * onOpen → [onMessage]* → onClosing → onClosed
 *                                ↘ onFailure (se erro)
 * </pre>
 */
public class SocketListener extends WebSocketListener {

    /** Gestor de rede que cria este listener. */
    private final NetworkManager manager;

    /**
     * Cria o listener ligado ao gestor de rede.
     *
     * @param manager o NetworkManager que gere este WebSocket
     */
    public SocketListener(NetworkManager manager) {
        this.manager = manager;
    }

    /**
     * Chamado quando a conexão WebSocket é estabelecida com sucesso.
     *
     * @param webSocket a instância WebSocket aberta
     * @param response a resposta HTTP da conexão
     */
    @Override
    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
        Log.d("SocketListener", "CONNECTED");
        manager.notifyConnectionChange(true, "Connected");
    }

    /**
     * Chamado quando uma mensagem é recebida do servidor.
     *
     * @param webSocket a instância WebSocket
     * @param text a mensagem de texto recebida
     */
    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
        // Passa a mensagem crua para o NetworkManager tratar
        manager.handleRawMessage(text);
    }

    /**
     * Chamado quando o outro lado inicia o fecho da conexão.
     *
     * @param webSocket a instância WebSocket
     * @param code código de fecho HTTP
     * @param reason razão do fecho
     */
    @Override
    public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        Log.d("SocketListener", "CLOSING: " + reason);
        manager.notifyConnectionChange(false, "Closing...");
    }

    /**
     * Chamado quando a conexão é fechada (normal ou anormal).
     *
     * @param webSocket a instância WebSocket
     * @param code código de fecho
     * @param reason razão do fecho
     */
    @Override
    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        Log.d("SocketListener", "CLOSED: " + reason);
        manager.notifyConnectionChange(false, "Closed");
    }

    /**
     * Chamado quando ocorre um erro de conexão.
     *
     * @param webSocket a instância WebSocket
     * @param t a exceção que causou o erro
     * @param response a resposta HTTP (pode ser null se erro antes da resposta)
     */
    @Override
    public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
        Log.e("SocketListener", "ERROR: " + t.getMessage());
        manager.notifyConnectionChange(false, "Error: " + t.getMessage());
    }
}
