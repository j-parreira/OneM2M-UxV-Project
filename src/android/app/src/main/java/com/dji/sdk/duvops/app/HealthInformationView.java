/**
 * {@code HealthInformationView} — Visualização de informações de saúde do drone (HMS).
 *
 * Exibe as mensagens de saúde do drone (Health and Status Management)
 * recebidas via {@link dji.sdk.base.DJIDiagnostics}. Filtra as mensagens
 * de HMS das mensagens SDK Error para evitar duplicação.
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.dji.sdk.duvops.R;
import com.dji.sdk.duvops.utils.Helper;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import dji.common.product.Model;
import dji.sdk.base.DJIDiagnostics;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * View personalizada para exibir informações de saúde do drone (HMS).
 *
 * <p>Regista-se como callback de diagnósticos do produto DJI.
 * Quando o produto suporta HMS (ex: Matrice 300 RTK), mostra as mensagens
 * de saúde do drone e outras informações de diagnóstico.
 *
 * <h3>Compatibilidade</h3>
 * <p>Esta view só funciona para produtos que suportam HMS
 * (ex: MATRICE_300_RTK). Para outros drones, a view fica vazia.
 */
public class HealthInformationView extends FrameLayout implements View.OnClickListener, DJIDiagnostics.DiagnosticsInformationCallback {

    /** Tag para log. */
    private static final String TAG = HealthInformationView.class.getSimpleName();

    /** TextView principal para mensagens HMS. */
    private TextView logTv;

    /** TextView secundário para outros diagnósticos. */
    private TextView otherLogTv;

    /** Indica se o produto conectado suporta HMS. */
    private boolean isSupportHMS;

    public HealthInformationView(@NonNull Context context) {
        super(context);
        initView(context);
    }

    public HealthInformationView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initView(context);
    }

    public HealthInformationView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView(context);
    }

    /**
     * Inicializa a view inflando o layout e verificando compatibilidade HMS.
     *
     * @param context o contexto da aplicação
     */
    public void initView(Context context) {
        if (DJISDKManager.getInstance().getProduct() != null) {
            // HMS só suportado no Matrice 300 RTK
            isSupportHMS = DJISDKManager.getInstance().getProduct().getModel() == Model.MATRICE_300_RTK;
        } else {
            isSupportHMS = false;
        }
        View root = LayoutInflater.from(context).inflate(R.layout.view_health_information, this, true);
        logTv = root.findViewById(R.id.tv_log);
        otherLogTv = root.findViewById(R.id.tv_other_log);
    }

    @Override
    public void onClick(View v) {
        // Não implementado — reservado para interações futuras
    }

    /**
     * Recebe as atualizações de diagnóstico em tempo real.
     *
     * <p>Filtra entre mensagens HMS (DEVICE_HEALTH_INFORMATION) e outras mensagens.
     * As mensagens HMS são enriquecidas com textos do ficheiro {@code hms.json}.
     *
     * @param list lista de diagnósticos recebidos
     */
    @Override
    public void onUpdate(List<DJIDiagnostics> list) {
        if (list == null || list.isEmpty()) {
            return;
        }

        List<HealthInfo> healthInfos = new ArrayList<>();
        for (DJIDiagnostics diagnostics : list) {
            if (diagnostics.getType() == DJIDiagnostics.DJIDiagnosticsType.DEVICE_HEALTH_INFORMATION) {
                handleHMS(diagnostics, healthInfos);
            } else {
                handleOther(diagnostics);
            }
        }
        updateLog(healthInfos, logTv);
    }

    /**
     * Processa uma mensagem de diagnóstico não-HMS.
     *
     * @param diagnostics o diagnóstico a processar
     */
    private void handleOther(DJIDiagnostics diagnostics) {
        if (filter(diagnostics)) {
            return;
        }
        updateLog(diagnostics.getReason(), otherLogTv);
    }

    /**
     * Filtra mensagens HMS que são duplicadas de SDK Errors.
     *
     * <p>As mensagens HMS são enviadas também via {@code SDKError},
     * por isso precisamos de filtrar para evitar duplicação.
     *
     * @param d o diagnóstico a filtrar
     * @return {@code true} se deve ser filtrado (duplicado), {@code false} se deve ser mostrado
     */
    private boolean filter(DJIDiagnostics d) {
        if (!isSupportHMS) {
            return false;
        }
        List<HealthInfoMatchSDKError> errors = Helper.getHmsMatchSDKError(getContext());
        for (HealthInfoMatchSDKError e : errors) {
            if (e.getSdkErrorCode().equals(d.getCode())) {
                return true; // Duplicado de SDK Error
            }
        }
        return false;
    }

    /**
     * Processa uma mensagem HMS enriquecendo-a com o texto do ficheiro JSON.
     *
     * @param diagnostic o diagnóstico HMS
     * @param cache lista acumulada de informações de saúde
     */
    private void handleHMS(DJIDiagnostics diagnostic, List<HealthInfo> cache) {
        List<HealthInfo> healthInfos = Helper.getHmsInfo(getContext());
        int index = healthInfos.indexOf(new HealthInfo("0x" + Long.toHexString(diagnostic.getHealthInformation().informationId()).toUpperCase() + ""));
        if (index >= 0) {
            HealthInfo info = healthInfos.get(index);
            if (!cache.contains(info)) {
                cache.add(info);
            }
        }
    }

    /**
     * Adiciona uma mensagem ao TextView de forma segura para threads.
     *
     * @param append a mensagem a append
     * @param tv o TextView onde append a mensagem
     */
    private void updateLog(String append, TextView tv) {
        StringBuffer sb = new StringBuffer(tv.getText() + "\n");
        sb.append(append).append("\n");
        tv.post(() -> tv.setText(sb.toString()));
    }

    /**
     * Atualiza o TextView com a lista de informações de saúde.
     *
     * @param list lista de HealthInfo
     * @param tv o TextView onde mostrar a lista
     */
    private void updateLog(List<HealthInfo> list, TextView tv) {
        StringBuffer sb = new StringBuffer("HMS info:\n");
        for (HealthInfo info : list) {
            sb.append(info.toString()).append("\n");
        }
        tv.post(() -> tv.setText(sb.toString()));
    }

    /**
     * Regista-se como callback de diagnósticos quando a view é adicionada.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (DJISDKManager.getInstance().getProduct() != null) {
            DJISDKManager.getInstance().getProduct().setDiagnosticsInformationCallback(this);
        }
    }

    /**
     * Remove o callback de diagnósticos quando a view é removida.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (DJISDKManager.getInstance().getProduct() != null) {
            DJISDKManager.getInstance().getProduct().setDiagnosticsInformationCallback(null);
        }
    }

    //region Inner Classes

    /**
     * Representa uma informação de saúde (alarme) do drone.
     *
     * <p>Lido do ficheiro {@code hms.json}.
     */
    public static class HealthInfo {
        private String alarmId;
        private String tipEn;
        private String tipCn;

        public HealthInfo() {
        }

        public HealthInfo(String alarmId) {
            this.alarmId = alarmId;
        }

        public String getAlarmId() { return alarmId; }
        public void setAlarmId(String alarmId) { this.alarmId = alarmId; }
        public String getTipEn() { return tipEn; }
        public void setTipEn(String tipEn) { this.tipEn = tipEn; }
        public String getTipCn() { return tipCn; }
        public void setTipCn(String tipCn) { this.tipCn = tipCn; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HealthInfo)) return false;
            HealthInfo that = (HealthInfo) o;
            return alarmId != null ? alarmId.equals(that.alarmId) : that.alarmId == null;
        }

        @Override
        public int hashCode() {
            return alarmId != null ? alarmId.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "HealthInfo{" +
                    "alarmId='" + alarmId + '\'' +
                    ", tipEn='" + tipEn + '\'' +
                    ", tipCn='" + tipCn + '\'' +
                    '}';
        }
    }

    /**
     * Mapeia um alarme HMS para um SDK Error correspondente.
     *
     * <p>Usado para filtrar duplicação entre mensagens HMS e SDK Errors.
     */
    public static class HealthInfoMatchSDKError {
        private String alarmId;
        private String sdkErrorCode;
        private String sdkErrorEnumName;

        public String getAlarmId() { return alarmId; }
        public void setAlarmId(String alarmId) { this.alarmId = alarmId; }
        public String getSdkErrorCode() { return sdkErrorCode; }
        public void setSdkErrorCode(String sdkErrorCode) { this.sdkErrorCode = sdkErrorCode; }
        public String getSdkErrorEnumName() { return sdkErrorEnumName; }
        public void setSdkErrorEnumName(String sdkErrorEnumName) { this.sdkErrorEnumName = sdkErrorEnumName; }
    }

    //endregion
}
