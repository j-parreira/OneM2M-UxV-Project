/**
 * {@code GeneralUtils} — Utilitários gerais de conversão e verificação.
 *
 * Fornece funções utilitárias para:
 * <ul>
 *   <li>Deteção de duplo clique rápido (anti-spam)</li>
 *   <li>Validação de coordenadas GPS</li>
 *   <li>Conversões de graus/radianos</li>
 *   <li>Cálculo de offset de longitude por latitude</li>
 *   <li>Append seguro a StringBuffer</li>
 *   <li>Completion callback padrão para erros DJI</li>
 * </ul>
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.utils;

import dji.common.error.DJIError;
import dji.common.util.CommonCallbacks;

/**
 * Utilitários gerais para cálculos e validação.
 *
 * <h3>Fator de conversão de coordenadas</h3>
 * <p>{@code ONE_METER_OFFSET = 0.00000899322} — valor aproximado de 1 grau de longitude
 * por metro na latitude 0. Ajustado dinamicamente por {@link #calcLongitudeOffset(double)}.
 */
public class GeneralUtils {

    /** Fator de conversão: metros para graus de longitude na latitude 0. */
    public static final double ONE_METER_OFFSET = 0.00000899322;

    /** Último timestamp de clique (para deteção de duplo clique). */
    private static long lastClickTime;

    /**
     * Verifica se o clique atual é um duplo clique rápido.
     *
     * <p>Dois cliques dentro de 800ms são considerados duplo clique.
     *
     * @return {@code true} se é duplo clique rápido (deve ser ignorado)
     */
    public static boolean isFastDoubleClick() {
        long time = System.currentTimeMillis();
        long timeD = time - lastClickTime;
        if (0 < timeD && timeD < 800) {
            return true;
        }
        lastClickTime = time;
        return false;
    }

    /**
     * Valida se as coordenadas GPS são válidas.
     *
     * @param latitude latitude (-90 a 90, não zero)
     * @param longitude longitude (-180 a 180, não zero)
     * @return {@code true} se as coordenadas são válidas
     */
    public static boolean checkGpsCoordinate(double latitude, double longitude) {
        return (latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180)
                && (latitude != 0f && longitude != 0f);
    }

    /** Converte graus para radianos. */
    public static double toRadian(double x) {
        return x * Math.PI / 180.0;
    }

    /** Converte radianos para graus. */
    public static double toDegree(double x) {
        return x * 180 / Math.PI;
    }

    /**
     * Calcula o cosseno de um ângulo em graus.
     *
     * @param degree o ângulo em graus
     * @return o cosseno do ângulo
     */
    public static double cosForDegree(double degree) {
        return Math.cos(degree * Math.PI / 180.0f);
    }

    /**
     * Calcula o offset de longitude para 1 metro a uma dada latitude.
     *
     * <p>O offset varia com a latitude: é maior no equador e menor nos polos.
     *
     * @param latitude a latitude atual
     * @return o offset em graus
     */
    public static double calcLongitudeOffset(double latitude) {
        return ONE_METER_OFFSET / cosForDegree(latitude);
    }

    /**
     * Adiciona uma linha ao StringBuffer de forma segura.
     *
     * @param sb o StringBuffer destino
     * @param name o nome do campo
     * @param value o valor do campo
     */
    public static void addLineToSB(StringBuffer sb, String name, Object value) {
        if (sb == null) return;
        sb.append(name == null ? "" : name + ": ")
          .append(value == null ? "" : value + "")
          .append("\n");
    }

    /**
     * Cria um completion callback padrão para erros DJI.
     *
     * @return o callback que mostra toast de sucesso/erro
     */
    public static CommonCallbacks.CompletionCallback getCommonCompletionCallback() {
        return new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                ToastUtils.setResultToToast(djiError == null ? "Succeed!" : "failed!" + djiError.getDescription());
            }
        };
    }
}
