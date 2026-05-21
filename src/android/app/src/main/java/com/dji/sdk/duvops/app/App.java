/**
 * {@code App} — Aplicações singleton para o projeto DJI.
 *
 * Fornece acesso centralizado ao produto DJI (drone), event bus (Otto),
 * e à instância da aplicação. Responsável também pela inicialização
 * do MultiDex e do helper de segurança.
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.app;

import android.app.Application;
import android.content.Context;

import com.squareup.otto.Bus;
import com.squareup.otto.ThreadEnforcer;

import androidx.multidex.MultiDex;
import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.products.HandHeld;
import dji.sdk.sdkmanager.BluetoothProductConnector;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Classe principal da aplicação.
 *
 * <p>Actua como ponto de entrada para o SDK da DJI e fornece singletons
 * para o produto conectado e o event bus (Otto). É instantiated
 * automaticamente pelo sistema Android via {@code AndroidManifest.xml}.
 *
 * <h3>Ciclo de vida</h3>
 * <ol>
 *   <li>{@link #attachBaseContext(Context)} — inicializa MultiDex e helper de segurança</li>
 *   <li>{@link #getProductInstance()} — obtém o drone conectado via SDK</li>
 *   <li>{@link #getEventBus()} — obtém o event bus para comunicação inter-componentes</li>
 * </ol>
 */
public class App extends Application {

    /** Tag para log. */
    public static final String TAG = App.class.getName();

    /** Produto DJI conectado (drone, handheld, etc.). Atualizado por {@link #getProductInstance()}. */
    private static BaseProduct product;

    /** Conector Bluetooth para dispositivos emparelhados. */
    private static BluetoothProductConnector bluetoothConnector = null;

    /** Event bus com enforced threading (ANY = permite envio de qualquer thread). */
    private static final Bus bus = new Bus(ThreadEnforcer.ANY);

    /** Instância global da aplicação, definida em {@link #attachBaseContext(Context)}. */
    private static Application app = null;

    /**
     * Obtém a instância do produto DJI conectado.
     *
     * <p>Consulta o SDK da DJI diretamente para obter o produto atual.
     * O produto só está disponível após a validação bem-sucedida da API key
     * no {@code AndroidManifest.xml}.
     *
     * @return o produto DJI conectado, ou {@code null} se nenhum estiver disponível
     */
    public static synchronized BaseProduct getProductInstance() {
        product = DJISDKManager.getInstance().getProduct();
        return product;
    }

    /**
     * Obtém o conector Bluetooth para dispositivos emparelhados.
     *
     * @return o conector Bluetooth ou {@code null} se não houver um
     */
    public static synchronized BluetoothProductConnector getBluetoothProductConnector() {
        bluetoothConnector = DJISDKManager.getInstance().getBluetoothProductConnector();
        return bluetoothConnector;
    }

    /**
     * Verifica se um drone (Aircraft) está conectado.
     *
     * @return {@code true} se o produto for uma instância de {@link Aircraft}
     */
    public static boolean isAircraftConnected() {
        return getProductInstance() != null && getProductInstance() instanceof Aircraft;
    }

    /**
     * Verifica se um dispositivo handheld está conectado.
     *
     * @return {@code true} se o produto for uma instância de {@link HandHeld}
     */
    public static boolean isHandHeldConnected() {
        return getProductInstance() != null && getProductInstance() instanceof HandHeld;
    }

    /**
     * Obtém a instância do drone como {@link Aircraft}.
     *
     * @return o {@link Aircraft} conectado, ou {@code null} se não estiver disponível
     */
    public static synchronized Aircraft getAircraftInstance() {
        if (!isAircraftConnected()) {
            return null;
        }
        return (Aircraft) getProductInstance();
    }

    /**
     * Obtém a instância do handheld como {@link HandHeld}.
     *
     * @return o {@link HandHeld} conectado, ou {@code null} se não estiver disponível
     */
    public static synchronized HandHeld getHandHeldInstance() {
        if (!isHandHeldConnected()) {
            return null;
        }
        return (HandHeld) getProductInstance();
    }

    /**
     * Obtém a instância global da aplicação.
     *
     * @return a instância {@link Application}
     */
    public static Application getInstance() {
        return app;
    }

    /**
     * Obtém o event bus (Otto) para comunicação inter-componentes.
     *
     * <p>O event bus permite a desacoplagem de componentes: os componentes
     * {@code register()} para receber eventos e {@code post()} para enviar eventos.
     *
     * @return o {@link Bus} singleton do Otto
     */
    public static Bus getEventBus() {
        return bus;
    }

    /**
     * Callback do ciclo de vida — executado antes de {@link #onCreate()}.
     *
     * <p>Responsável por três inicializações críticas:
     * <ul>
     *   <li>{@code MultiDex.install()} — permite mais de 64K métodos</li>
     *   <li>{@code secneo.Helper.install()} — inicializa o módulo de segurança</li>
     *   <li>Armazena a referência da aplicação no campo estático {@code app}</li>
     * </ul>
     *
     * @param paramContext contexto base fornecido pelo sistema
     */
    @Override
    protected void attachBaseContext(Context paramContext) {
        super.attachBaseContext(paramContext);
        MultiDex.install(this);
        com.secneo.sdk.Helper.install(this);
        app = this;
    }
}
