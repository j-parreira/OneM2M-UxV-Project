/**
 * {@code FlightActivity} — Activity de voo que contém a {@link DuvopsView}.
 *
 * Actua como wrapper para a view de controlo do drone: configura o ecrã
 * para manter-se ligado e aloca a view principal.
 *
 * @author João Parreira
 * @author Pedro Barbeiro
 * @version 2.0
 */
package com.dji.sdk.duvops.flight;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Activity que aloca a view de controlo de voo do drone.
 *
 * <h3>Configuração</h3>
 * <ul>
 *   <li>{@code KEEP_SCREEN_ON} — mantém o ecrã ligado durante o voo</li>
 *   <li>{@code DuvopsView} — view principal de controlo</li>
 * </ul>
 */
public class FlightActivity extends AppCompatActivity {

    /** View principal de controlo do drone. */
    private DuvopsView duvopsView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Manter o ecrã ligado durante o voo
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Aloca a view de controlo principal
        duvopsView = new DuvopsView(this);

        // Define a view como conteúdo da activity
        setContentView(duvopsView);
    }

    @Override
    protected void onDestroy() {
        // Limpar sessão OneM2M, WebSocket e timer de telemetria antes de destruir a activity.
        // Sem este cleanup, o WebSocket e o timer ficam activos em segundo plano.
        if (duvopsView != null) duvopsView.cleanup();
        super.onDestroy();
    }
}
