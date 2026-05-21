/**
 * {@code CameraManager} — Gestor de controlo da câmara do drone.
 *
 * Gerencia o zoom híbrido (óptico + digital) e a seleção de stream
 * da câmara (RGB, IR térmica, SPLIT/PIP) para o Mavic 2 Enterprise Advanced.
 * Inclui getters para o frontend interrogar o estado atual da câmara.
 *
 * @author João Parreira
 * @version 3.0
 */
package com.dji.sdk.duvops.flight;

import android.util.Log;

import dji.common.camera.CameraVideoStreamSource;
import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.keysdk.CameraKey;
import dji.keysdk.DJIKey;
import dji.keysdk.KeyManager;
import dji.keysdk.callback.SetCallback;

/**
 * Classe para controlo da câmara do drone via DJI KeyManager.
 *
 * <h3>Mapeamento de streams do M2EA</h3>
 * <table>
 *   <tr><th>Modo</th><th>Stream</th><th>DisplayMode</th></tr>
 *   <tr><td>RGB</td><td>WIDE (RGB primária)</td><td>VISUAL_ONLY</td></tr>
 *   <tr><td>IR</td><td>INFRARED_THERMAL</td><td>VISUAL_ONLY</td></tr>
 *   <tr><td>SPLIT</td><td>WIDE + INFRARED_THERMAL</td><td>PIP + SIDE_BY_SIDE</td></tr>
 * </table>
 *
 * <h3>Zoom híbrido (M2EA)</h3>
 * <p>O M2EA suporta zoom de 1.0x até 32.0x.
 * A conversão é: {@code focalLength = factor × 240} (unidade 0.1mm).
 * <ul>
 *   <li>1.0x = 240 (24mm base)</li>
 *   <li>32.0x = 7680 (768mm digital overlay)</li>
 * </ul>
 */
public class CameraManager {

    /** Tag para log. */
    private static final String TAG = "CameraManager";

    /** Chave para controlo do zoom híbrido (distância focal em 0.1mm). */
    private final DJIKey hybridZoomKey;

    /** Chave para a fonte do stream de vídeo (WIDE/INFRARED_THERMAL). */
    private final DJIKey videoSourceKey;

    /** Chave para o modo de display (VISUAL_ONLY, PIP). */
    private final DJIKey displayModeKey;

    /** Chave para a posição do PIP (SIDE_BY_SIDE). */
    private final DJIKey pipPositionKey;

    /** Último zoom definido (cache para getter). Range: 1.0 a 32.0. */
    private float lastZoom = 1.0f;

    /** Último modo de camera definido (cache para getter). RGB, IR, SPLIT. */
    private String lastCameraMode = "RGB";

    /**
     * Cria o CameraManager com as chaves do SDK.
     *
     * <p>Índice 0 = Câmara Principal do M2EA.
     */
    public CameraManager() {
        hybridZoomKey = CameraKey.create(CameraKey.HYBRID_ZOOM_FOCAL_LENGTH);
        videoSourceKey = CameraKey.create(CameraKey.CAMERA_VIDEO_STREAM_SOURCE);
        displayModeKey = CameraKey.create(CameraKey.DISPLAY_MODE);
        pipPositionKey = CameraKey.create(CameraKey.PIP_POSITION);
    }

    /**
     * Obtém o último zoom definido (de getter).
     *
     * <p>Se o KeyManager expor o valor atual, usa-o; caso contrário
     * devolve o último zoom conhecido gravado em cache.
     *
     * @return o fator de zoom (1.0 a 32.0) ou 0 se indisponível
     */
    public float getZoom() {
        // Tentar obter o valor real via KeyManager getValue (síncrono)
        if (KeyManager.getInstance() != null) {
            Object val = KeyManager.getInstance().getValue(hybridZoomKey);
            if (val instanceof Integer) {
                lastZoom = ((Integer) val) / 240.0f;
                lastZoom = Math.max(1.0f, Math.min(32.0f, lastZoom));
                lastZoom = (float) Math.round(lastZoom * 10.0) / 10f;
                return lastZoom;
            }
        }
        return lastZoom;
    }

    /**
     * Obtém o último modo de camera definido (de getter).
     *
     * <p>Se o KeyManager expôr o DisplayMode atual, deriva o modo a partir dele.
     * Senão devolve o último modo conhecido gravado em cache.
     *
     * @return o modo de camera ("RGB", "IR", "SPLIT") ou "UNKNOWN"
     */
    public String getCameraMode() {
        // Tentar derivar o modo a partir do DisplayMode
        if (KeyManager.getInstance() != null) {
            Object displayVal = KeyManager.getInstance().getValue(displayModeKey);
            if (displayVal instanceof SettingsDefinitions.DisplayMode) {
                SettingsDefinitions.DisplayMode dm =
                        (SettingsDefinitions.DisplayMode) displayVal;
                if (dm == SettingsDefinitions.DisplayMode.PIP) {
                    lastCameraMode = "SPLIT";
                    return lastCameraMode;
                } else if (dm == SettingsDefinitions.DisplayMode.VISUAL_ONLY) {
                    // VISUAL_ONLY → verificar qual stream está ativo
                    Object sourceVal = KeyManager.getInstance().getValue(videoSourceKey);
                    if (sourceVal instanceof CameraVideoStreamSource) {
                        CameraVideoStreamSource src = (CameraVideoStreamSource) sourceVal;
                        if (src == CameraVideoStreamSource.INFRARED_THERMAL) {
                            lastCameraMode = "IR";
                            return lastCameraMode;
                        } else if (src == CameraVideoStreamSource.WIDE) {
                            lastCameraMode = "RGB";
                            return lastCameraMode;
                        }
                    }
                }
            }
        }
        return lastCameraMode;
    }

    /**
     * Define o fator de zoom.
     *
     * <p>Range: 1.0 a 32.0. Valores fora são clampados.
     *
     * @param factor o fator de zoom (1.0 a 32.0)
     */
    public void setZoom(float factor) {
        if (KeyManager.getInstance() == null) return;

        // Clamp range 1.0-32.0
        final int clamped = (int) (Math.max(1.0f, Math.min(32.0f, factor)) * 240);
        lastZoom = Math.max(1.0f, Math.min(32.0f, factor)); // gravar em cache

        KeyManager.getInstance().setValue(hybridZoomKey, clamped, new SetCallback() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Zoom definido para " + lastZoom + "x (" + clamped + ")");
            }

            @Override
            public void onFailure(DJIError error) {
                Log.e(TAG, "Erro ao definir Zoom: " + error.getDescription());
            }
        });
    }

    /**
     * Define o modo da câmara (stream source + display mode).
     *
     * <p>Os modos suportados são:
     * <ul>
     *   <li>{@code "RGB"} — Câmera RGB/WIDE</li>
     *   <li>{@code "IR"} — Câmera térmica infravermelha</li>
     *   <li>{@code "SPLIT"} — Split screen (PIP) com ambas as câmeras</li>
     * </ul>
     *
     * @param mode o modo da câmara (case-insensitive)
     */
    public void setCameraMode(String mode) {
        if (KeyManager.getInstance() == null) return;

        String cmd = mode.toUpperCase();
        Log.d(TAG, "Recebido comando de modo: " + cmd);

        if (cmd.contains("RGB") || cmd.contains("VISUAL") || cmd.contains("WIDE")) {
            switchToRGB();
            lastCameraMode = "RGB";
        } else if (cmd.contains("IR") || cmd.contains("INFRARED") || cmd.contains("THERMAL")) {
            switchToThermal();
            lastCameraMode = "IR";
        } else if (cmd.contains("SPLIT") || cmd.contains("PIP")) {
            switchToSplitScreen();
            lastCameraMode = "SPLIT";
        }
    }

    // --- Helpers de Lógica ---

    /** Muda para a câmara RGB (stream WIDE, display VISUAL_ONLY). */
    private void switchToRGB() {
        setKey(displayModeKey, SettingsDefinitions.DisplayMode.VISUAL_ONLY);
        setKey(videoSourceKey, CameraVideoStreamSource.WIDE);
    }

    /** Muda para a câmara térmica (stream INFRARED_THERMAL, display VISUAL_ONLY). */
    private void switchToThermal() {
        setKey(displayModeKey, SettingsDefinitions.DisplayMode.VISUAL_ONLY);
        setKey(videoSourceKey, CameraVideoStreamSource.INFRARED_THERMAL);
    }

    /**
     * Muda para split screen PIP (SIDE_BY_SIDE).
     *
     * <p>No M2EA o PIP mostra IR sobre RGB (Picture-in-Picture).
     * SIDE_BY_SIDE é configurado via PIP_POSITION para layout horizontal.
     */
    private void switchToSplitScreen() {
        setKey(displayModeKey, SettingsDefinitions.DisplayMode.PIP);
        setKey(pipPositionKey, SettingsDefinitions.PIPPosition.SIDE_BY_SIDE);
    }

    /**
     * Define o valor de uma chave do SDK de forma assíncrona.
     *
     * @param key a chave DJI
     * @param value o valor a definir
     */
    private void setKey(DJIKey key, Object value) {
        KeyManager.getInstance().setValue(key, value, new SetCallback() {
            @Override
            public void onSuccess() { }

            @Override
            public void onFailure(DJIError error) {
                Log.e(TAG, "Erro ao definir " + key.toString() + ": " + error.getDescription());
            }
        });
    }
}
