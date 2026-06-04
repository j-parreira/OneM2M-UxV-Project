/**
 * {@code DialogUtils} — Utilitários para diálogos AlertDialog.
 *
 * Fornece métodos estáticos para mostrar diálogos com o tema da app
 * ({@code set_dialog}). Usado para mensagens de feedback ao utilizador.
 *
 * @author João Parreira
 * @author Pedro Barbeiro
 * @version 2.0
 */
package com.dji.sdk.duvops.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import com.dji.sdk.duvops.R;
import dji.common.error.DJIError;

/**
 * Utilitários para diálogos AlertDialog com tema da app.
 *
 * <p>Todos os diálogos usam o estilo {@code set_dialog} e têm um botão "OK".
 */
public class DialogUtils {

    /**
     * Mostra um diálogo com a mensagem fornecida.
     *
     * @param ctx o contexto da aplicação
     * @param str a mensagem de texto a mostrar
     */
    public static void showDialog(Context ctx, String str) {
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, R.style.set_dialog);
        builder.setMessage(str);
        builder.setNeutralButton(android.R.string.ok, (dialog, which) -> dialog.dismiss());
        builder.create().show();
    }

    /**
     * Mostra um diálogo com a mensagem do recurso de strings.
     *
     * @param ctx o contexto da aplicação
     * @param strId o ID do recurso de string
     */
    public static void showDialog(Context ctx, int strId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, R.style.set_dialog);
        builder.setMessage(strId);
        builder.setNeutralButton(android.R.string.ok, (dialog, which) -> dialog.dismiss());
        builder.create().show();
    }

    /**
     * Mostra um diálogo de confirmação com botões OK/Cancel.
     *
     * @param ctx o contexto da aplicação
     * @param strId o ID do recurso de string para a mensagem
     * @param onClickListener o callback para o botão OK
     */
    public static void showConfirmationDialog(Context ctx, int strId,
                                              DialogInterface.OnClickListener onClickListener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, R.style.set_dialog);
        builder.setMessage(strId);
        builder.setPositiveButton(android.R.string.ok, onClickListener);
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss());
        builder.create().show();
    }

    /**
     * Mostra um diálogo baseado no resultado de um erro DJI.
     *
     * <p>Se o erro é null, mostra "Success". Caso contrário, mostra a descrição do erro.
     *
     * @param ctx o contexto da aplicação
     * @param djiError o erro DJI (pode ser null)
     */
    public static void showDialogBasedOnError(Context ctx, DJIError djiError) {
        if (null == djiError) {
            showDialog(ctx, R.string.success);
        } else {
            showDialog(ctx, djiError.getDescription());
        }
    }
}
