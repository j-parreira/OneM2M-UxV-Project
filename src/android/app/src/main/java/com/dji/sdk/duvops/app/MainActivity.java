/**
 * {@code MainActivity} — Activity de lançador da aplicação.
 *
 * Actua como ponto de entrada principal: gerencia a barra de ação personalizada,
 * ouve eventos de conectividade do drone via EventBus e encaminha o evento
 * de acessório USB para o DJI SDK (necessário para estabelecer a conexão).
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.app;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.dji.sdk.duvops.R;
import com.squareup.otto.Subscribe;

import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Activity principal da aplicação (launcher).
 *
 * <p>Responsabilidades:
 * <ol>
 *   <li>Mostrar a barra de ação personalizada com o título da app</li>
 *   <li>Receber o evento {@code USB_ACCESSORY_ATTACHED} e encaminhá-lo ao DJI SDK</li>
 *   <li>Ouvir eventos de conectividade do drone via {@link EventBus}</li>
 * </ol>
 *
 * <h3>Fluxo de inicialização</h3>
 * <pre>
 * MainActivity.onCreate() → setupActionBar() + EventBus.register(this)
 *     ↓
 * USB connected → onNewIntent() → sendBroadcast(DJI) → DJISDKManager conecta
 *     ↓
 * Product connected → EventBus.post(ConnectivityChangeEvent) → refreshUI()
 * </pre>
 */
public class MainActivity extends AppCompatActivity {

    /** Tag para log. */
    private static final String TAG = MainActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Registar no EventBus para receber notificações de conectividade do drone
        App.getEventBus().register(this);

        setupActionBar();
    }

    @Override
    protected void onDestroy() {
        // Desregistrar para evitar memory leaks
        App.getEventBus().unregister(this);
        super.onDestroy();
    }

    /**
     * Processa novos intents quando a activity já está em primeiro plano.
     *
     * <p>Checa se o intent é um evento de acessório USB attachado.
     * Se sim, encaminha o broadcast para o DJI SDK iniciar a conexão.
     *
     * @param intent o novo intent recebido
     */
    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        String action = intent.getAction();
        // Encaminha o evento USB ao DJI SDK — essencial para conexão via USB
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
            Intent attachedIntent = new Intent();
            attachedIntent.setAction(DJISDKManager.USB_ACCESSORY_ATTACHED);
            sendBroadcast(attachedIntent);
        }
    }

    /**
     * Configura a barra de ação com a vista personalizada (actionbar_custom.xml).
     *
     * <p>Define o título a partir das strings de recursos.
     * O texto pode ser substituído por {@code actionBar.hide()} para ecrã completo.
     */
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (null != actionBar) {
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
            actionBar.setCustomView(R.layout.actionbar_custom);

            TextView titleTextView = (TextView) (actionBar.getCustomView().findViewById(R.id.title_tv));
            titleTextView.setText(R.string.sample_app_name);
        }
    }

    /**
     * Subscreve o event bus para eventos de conectividade.
     *
     * <p>Disparado pelo {@link MainContent#notifyStatusChange()} quando
     * o drone liga/desliga. Atualiza a UI em sequência principal.
     *
     * @param event evento de mudança de conectividade
     */
    @Subscribe
    public void onConnectivityChange(MainActivity.ConnectivityChangeEvent event) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // TODO: Atualizar ícones/indicadores de estado conforme necessário
            }
        });
    }

    /**
     * Evento do bus para notificações de conectividade do drone.
     *
     * <p>Usado como meio de comunicação entre {@link MainContent} e {@link MainActivity}.
     */
    public static class ConnectivityChangeEvent {
    }
}
