/**
 * {@code ToastUtils} — Utilitários para toasts thread-safe.
 *
 * Fornece métodos estáticos para mostrar toasts na thread principal,
 * independentemente da thread de chamada.
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.utils;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Pair;
import android.widget.TextView;
import android.widget.Toast;

import com.dji.sdk.duvops.app.App;

/**
 * Utilitários para toasts thread-safe via handler.
 *
 * <h3>Como funciona</h3>
 * <p>Envia mensagens para um Handler na thread principal (Looper.getMainLooper()).
 * A mensagem carrega o texto ou o par TextView+String.
 *
 * <h3>Tipos de mensagem</h3>
 * <ul>
 *   <li>{@code MESSAGE_TOAST = 1} — mostra um toast simples</li>
 *   <li>{@code MESSAGE_UPDATE = 2} — atualiza o texto de um TextView</li>
 * </ul>
 */
public class ToastUtils {

    /** Tipo de mensagem para mostrar toast. */
    private static final int MESSAGE_TOAST = 2;

    /** Tipo de mensagem para atualizar TextView. */
    private static final int MESSAGE_UPDATE = 1;

    /** Handler na thread principal para processar mensagens de toast/TextView. */
    private static final Handler mUIHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_UPDATE:
                    showMessage((Pair<TextView, String>) msg.obj);
                    break;
                case MESSAGE_TOAST:
                    showToast((String) msg.obj);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    };

    /**
     * Atualiza o texto de um TextView na thread principal.
     *
     * @param msg o par (TextView, String)
     */
    private static void showMessage(Pair<TextView, String> msg) {
        if (msg != null) {
            if (msg.first == null) {
                Toast.makeText(App.getInstance(), "tv is null", Toast.LENGTH_SHORT).show();
            } else {
                msg.first.setText(msg.second);
            }
        }
    }

    /**
     * Mostra um toast simples.
     *
     * @param msg a mensagem a mostrar
     */
    public static void showToast(String msg) {
        Toast.makeText(App.getInstance(), msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Mostra um toast na thread principal.
     *
     * <p>Thread-safe: pode ser chamado de qualquer thread.
     * A mensagem é enfileirada para a thread principal.
     *
     * @param string a mensagem a mostrar
     */
    public static void setResultToToast(final String string) {
        Message msg = new Message();
        msg.what = MESSAGE_TOAST;
        msg.obj = string;
        mUIHandler.sendMessage(msg);
    }

    /**
     * Atualiza o texto de um TextView na thread principal.
     *
     * <p>Thread-safe: pode ser chamado de qualquer thread.
     *
     * @param tv o TextView a atualizar
     * @param s a nova string
     */
    public static void setResultToText(final TextView tv, final String s) {
        Message msg = new Message();
        msg.what = MESSAGE_UPDATE;
        msg.obj = new Pair<>(tv, s);
        mUIHandler.sendMessage(msg);
    }
}
