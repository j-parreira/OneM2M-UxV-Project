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
 *
 * <p>Cada instância carrega uma {@link #generation} que corresponde ao valor de
 * {@code wsGeneration} em {@link NetworkManager} no momento em que foi criada.
 * Se entre a criação e a execução do callback o {@code NetworkManager} tiver aberto
 * um novo WebSocket (incrementando a geração), o callback é descartado — evita que
 * tentativas de ligação obsoletas (ex: IP antigo com timeout de 10s) perturbem
 * uma sessão já estabelecida.
 */
public class SocketListener extends WebSocketListener {

    /** Gestor de rede que cria este listener. */
    private final NetworkManager manager;

    /**
     * Geração do WebSocket que criou este listener.
     * Callbacks de gerações anteriores são ignorados pelo NetworkManager.
     */
    private final int generation;

    /**
     * Cria o listener ligado ao gestor de rede com a geração actual.
     *
     * @param manager    o NetworkManager que gere este WebSocket
     * @param generation o contador de geração do NetworkManager no momento da criação
     */
    public SocketListener(NetworkManager manager, int generation) {
        this.manager = manager;
        this.generation = generation;
    }

    /**
     * Chamado quando a conexão WebSocket é estabelecida com sucesso.
     *
     * @param webSocket a instância WebSocket aberta
     * @param response a resposta HTTP da conexão
     */
    @Override
    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
        Log.d("SocketListener", "CONNECTED gen=" + generation);
        manager.notifyConnectionChange(true, "Connected", generation);
    }

    /**
     * Chamado quando uma mensagem é recebida do servidor.
     *
     * @param webSocket a instância WebSocket
     * @param text a mensagem de texto recebida
     */
    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
        // Passa a mensagem crua para o NetworkManager tratar (sem guarda de geração —
        // onMessage só é chamado em sockets activos; se o socket foi substituído o okhttp
        // já não entregará mais mensagens nesta instância)
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
        Log.d("SocketListener", "CLOSING gen=" + generation + ": " + reason);
        manager.notifyConnectionChange(false, "Closing...", generation);
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
        Log.d("SocketListener", "CLOSED gen=" + generation + ": " + reason);
        manager.notifyConnectionChange(false, "Closed", generation);
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
        Log.e("SocketListener", "ERROR gen=" + generation + ": " + t.getMessage());
        manager.notifyConnectionChange(false, "Error: " + t.getMessage(), generation);
    }
}
