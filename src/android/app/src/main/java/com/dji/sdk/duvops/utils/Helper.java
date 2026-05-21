/**
 * {@code Helper} — Utilitários de conversão e informação do drone.
 *
 * Fornece:
 * <ul>
 *   <li>Conversões de bytes/strings (GBK, UTF-8, hex)</li>
 *   <li>Criação de listas a partir de arrays primitivos e objetos</li>
 *   <li>Timestamp para string formatada</li>
 *   <li>Verificação de suporte HMS para diferentes modelos DJI</li>
 *   <li>Leitura do ficheiro {@code hms.json} para mapeamento de alarmes</li>
 * </ul>
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.utils;

import android.app.Activity;
import android.content.Context;
import android.widget.TextView;
import android.widget.Toast;

import com.dji.sdk.duvops.app.HealthInformationView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import dji.common.product.Model;
import dji.sdk.camera.Camera;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Utilitários de conversão e informação do drone.
 *
 * <h3>Conversões de bytes</h3>
 * <p>Os métodos de conversão removem bytes nulos (0x00) e 0xFF
 * de buffers para obter strings limpas.
 *
 * <h3>HMS (Health and Status Management)</h3>
 * <p>Para o Matrice 300 RTK, lê o ficheiro {@code hms.json} dos assets
 * para mapear IDs de alarme para mensagens em inglês e chinês.
 */
public class Helper {

    public Helper() {
        // Não instanciado — apenas métodos estáticos
    }

    /**
     * Mostra um toast na activity especificada.
     *
     * @param activity a activity onde mostrar o toast
     * @param msg a mensagem a mostrar
     */
    public static void showToast(final Activity activity, final String msg) {
        activity.runOnUiThread(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * Atualiza o texto de um TextView na thread principal.
     *
     * @param activity a activity que contém o TextView
     * @param tv o TextView a atualizar
     * @param msg a mensagem a definir
     */
    public static void showText(final Activity activity, final TextView tv, final String msg) {
        if (tv == null) {
            showToast(activity, "The current textView is null.");
            return;
        }
        activity.runOnUiThread(() -> tv.setText(msg));
    }

    /**
     * Cria uma lista de strings a partir de um array de objetos.
     *
     * @param o o array de objetos
     * @return a lista de strings
     */
    public static ArrayList<String> makeList(Object[] o) {
        ArrayList<String> list = new ArrayList<>();
        for (int i = 0; i < o.length; i++) {
            list.add(o[i].toString());
        }
        return list;
    }

    /**
     * Cria uma lista de strings a partir de um array de ints.
     *
     * @param o o array de ints
     * @return a lista de strings
     */
    public static ArrayList<String> makeList(int[] o) {
        ArrayList<String> list = new ArrayList<>();
        for (int i = 0; i < o.length; i++) {
            list.add(Integer.valueOf(o[i]).toString());
        }
        return list;
    }

    /**
     * Cria uma lista de strings a partir de uma coleção.
     *
     * @param o a coleção de objetos
     * @return a lista de strings
     */
    public static ArrayList<String> makeList(List<?> o) {
        ArrayList<String> list = new ArrayList<>();
        Iterator<?> iterator = o.iterator();
        while (iterator.hasNext()) {
            list.add(iterator.next().toString());
        }
        return list;
    }

    /**
     * Converte um array de bytes a string, removendo bytes nulos e 0xFF.
     *
     * @param bytes o array de bytes
     * @return a string resultante (GBK)
     */
    public static String getString(byte[] bytes) {
        if (null == bytes) return "";
        byte zero = 0x00;
        byte no = (byte) 0xFF;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == zero || bytes[i] == no) {
                bytes = readBytes(bytes, 0, i);
                break;
            }
        }
        return getString(bytes, "GBK");
    }

    private static String getString(byte[] bytes, String charsetName) {
        return new String(bytes, Charset.forName(charsetName));
    }

    /** Copia um sub-array de bytes. */
    public static byte[] readBytes(byte[] source, int from, int length) {
        byte[] result = new byte[length];
        System.arraycopy(source, from, result, 0, length);
        return result;
    }

    /** Converte uma string para bytes (GBK). */
    public static byte[] getBytes(String data) {
        return getBytes(data, "GBK");
    }

    private static byte[] getBytes(String data, String charsetName) {
        Charset charset = Charset.forName(charsetName);
        return data.getBytes(charset);
    }

    /**
     * Converte um sub-array de bytes a string UTF-8, removendo bytes nulos.
     *
     * @param bytes o array de bytes
     * @param start o índice de início
     * @param length o comprimento
     * @return a string resultante
     */
    public static String getStringUTF8(byte[] bytes, int start, int length) {
        if (null == bytes || bytes.length == 0) return "";
        byte zero = 0x00;
        for (int i = start; i < length && i < bytes.length; i++) {
            if (bytes[i] == zero) {
                length = i - start;
                break;
            }
        }
        return getString(bytes, start, length, "UTF-8");
    }

    private static String getString(byte[] bytes, int start, int length, String charsetName) {
        return new String(bytes, start, length, Charset.forName(charsetName));
    }

    /**
     * Converte um array de bytes para string hexadecimal.
     *
     * @param buffer o array de bytes
     * @return a string hexadecimal (ex: "0a 1b 2c")
     */
    public static String byte2hex(byte[] buffer) {
        String h = "";
        if (null == buffer) return h;
        for (int i = 0; i < buffer.length; i++) {
            String temp = Integer.toHexString(buffer[i] & 0xFF);
            if (temp.length() == 1) temp = "0" + temp;
            h = h + " " + temp;
        }
        return h;
    }

    /**
     * Gera um timestamp formatado como string.
     *
     * @param format o formato (null ou vazio para {@code yyyy-MM-dd-HH-mm-ss})
     * @return a string do timestamp atual
     */
    public static String timeStamp2Date(String format) {
        if (format == null || format.isEmpty()) {
            format = "yyyy-MM-dd-HH-mm-ss";
        }
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        long time = System.currentTimeMillis();
        return sdf.format(new Date(time));
    }

    /**
     * Verifica se o drone suporta streaming multi-câmera.
     *
     * @return {@code true} se o modelo suporta multi-stream
     */
    public static boolean isMultiStreamPlatform() {
        if (DJISDKManager.getInstance() == null) return false;
        Model model = DJISDKManager.getInstance().getProduct().getModel();
        return model != null && (model == Model.INSPIRE_2
                || model == Model.MATRICE_200
                || model == Model.MATRICE_210
                || model == Model.MATRICE_210_RTK
                || model == Model.MATRICE_200_V2
                || model == Model.MATRICE_210_V2
                || model == Model.MATRICE_210_RTK_V2
                || model == Model.MATRICE_300_RTK
                || model == Model.MATRICE_600
                || model == Model.MATRICE_600_PRO
                || model == Model.A3
                || model == Model.N3);
    }

    /**
     * Verifica se o drone é um Matrice 300 RTK.
     *
     * @return {@code true} se o modelo é MATRICE_300_RTK
     */
    public static boolean isM300Product() {
        if (DJISDKManager.getInstance().getProduct() == null) return false;
        return DJISDKManager.getInstance().getProduct().getModel() == Model.MATRICE_300_RTK;
    }

    /**
     * Verifica se a câmara é da série H20 (Zenmuse H20/H20T).
     *
     * @return {@code true} se a câmara é Zenmuse H20 ou H20T
     */
    public static boolean isH20Series() {
        if (DJISDKManager.getInstance().getProduct() == null) return false;
        if (DJISDKManager.getInstance().getProduct().getCamera() == null) return false;
        String displayName = DJISDKManager.getInstance().getProduct().getCamera().getDisplayName();
        return Camera.DisplayNameZenmuseH20.equals(displayName)
                || Camera.DisplayNameZenmuseH20T.equals(displayName);
    }

    /**
     * Obtém a lista de informações HMS (Health Management System) do ficheiro JSON.
     *
     * <p>Cache o resultado em memória para evitar re-leitura.
     *
     * @param context o contexto da aplicação
     * @return a lista de HealthInfo
     */
    private static List<HealthInformationView.HealthInfo> hmsJson;

    public static List<HealthInformationView.HealthInfo> getHmsInfo(Context context) {
        if (hmsJson == null) {
            synchronized (Helper.class) {
                if (hmsJson == null) {
                    hmsJson = getObjFromJsonFile(context, "hms.json",
                            new TypeToken<List<HealthInformationView.HealthInfo>>() {}.getType());
                }
            }
        }
        return hmsJson;
    }

    /**
     * Obtém o mapeamento de alarmes HMS para SDK Errors.
     *
     * <p>Usado para filtrar duplicação entre mensagens HMS e SDK Errors.
     *
     * @param context o contexto da aplicação
     * @return a lista de HealthInfoMatchSDKError
     */
    private static List<HealthInformationView.HealthInfoMatchSDKError> hmsMatchSDKError;

    public static List<HealthInformationView.HealthInfoMatchSDKError> getHmsMatchSDKError(Context context) {
        if (hmsMatchSDKError == null) {
            synchronized (Helper.class) {
                if (hmsMatchSDKError == null) {
                    hmsMatchSDKError = getObjFromJsonFile(context, "hms_match_sdkerror.json",
                            new TypeToken<List<HealthInformationView.HealthInfoMatchSDKError>>() {}.getType());
                }
            }
        }
        return hmsMatchSDKError;
    }

    /**
     * Lê um ficheiro JSON dos assets e desserializa para o tipo especificado.
     *
     * @param context o contexto
     * @param file o nome do ficheiro nos assets
     * @param type o tipo de destino para desserialização
     * @param <T> o tipo de destino
     * @return o objeto desserializado ou null em caso de erro
     */
    private static <T> T getObjFromJsonFile(Context context, String file, Type type) {
        InputStream in = null;
        ByteArrayOutputStream out = null;
        try {
            in = context.getAssets().open(file);
            out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = in.read(buffer, 0, 1024)) > -1) {
                out.write(buffer, 0, length);
            }
            out.flush();
            String json = out.toString();
            Gson gson = new Gson();
            return gson.fromJson(json, type);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException e) { e.printStackTrace(); }
            }
            if (out != null) {
                try { out.close(); } catch (IOException e) { e.printStackTrace(); }
            }
        }
        return null;
    }
}
