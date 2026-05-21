/**
 * {@code LoginView} — Custom view para login/logout de conta DJI.
 *
 * Permite ao utilizador fazer login e logout da conta DJI diretamente
 * da interface. Os botões são habilitados/desabilitados conforme o
 * estado de autenticação atual.
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dji.sdk.duvops.R;
import com.dji.sdk.duvops.utils.ToastUtils;
import dji.common.error.DJIError;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.sdk.useraccount.UserAccountManager;

/**
 * View reutilizável para login/logout de conta DJI.
 *
 * <p>Infla o layout {@link R.layout#view_login} e liga os botões
 * de login/logout ao {@link UserAccountManager} da DJI.
 *
 * <h3>Estados dos botões</h3>
 * <ul>
 *   <li><b>Login:</b> habilitado se o utilizador NÃO está autorizado</li>
 *   <li><b>Logout:</b> habilitado se o utilizador NÃO está desconectado</li>
 * </ul>
 */
public class LoginView extends LinearLayout implements View.OnClickListener {

    /** TextView que mostra o estado atual da conta. */
    private TextView accountStateTV;

    /** Botão de login na conta DJI. */
    private Button loginBtn;

    /** Botão de logout da conta DJI. */
    private Button logoutBtn;

    public LoginView(Context context) {
        super(context);
        init();
    }

    public LoginView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LoginView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /**
     * Processa o clique nos botões de login/logout.
     *
     * @param v a vista clicada
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_login:
                // Inicia o fluxo de login na conta DJI
                UserAccountManager.getInstance().logIntoDJIUserAccount(this.getContext(),
                        new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                            @Override
                            public void onSuccess(UserAccountState userAccountState) {
                                ToastUtils.setResultToToast("Login Success");
                                updateLoginState(userAccountState);
                            }

                            @Override
                            public void onFailure(DJIError djiError) {
                                ToastUtils.setResultToToast("error:" + djiError.getDescription());
                            }
                        });
                break;

            case R.id.btn_login_out:
                // Inicia o logout da conta DJI
                UserAccountManager.getInstance().logoutOfDJIUserAccount(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        if (null == error) {
                            ToastUtils.setResultToToast("Logout Success");
                            updateLoginState(UserAccountState.NOT_LOGGED_IN);
                        } else {
                            ToastUtils.setResultToToast("error:" + error.getDescription());
                        }
                    }
                });
                break;

            default:
                break;
        }
    }

    /**
     * Atualiza o estado visual quando a view entra na janela.
     *
     * <p>Lê o estado atual da conta e ajusta os botões.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        UserAccountState currentState = UserAccountManager.getInstance().getUserAccountState();
        updateLoginState(currentState);
    }

    /**
     * Atualiza o estado visual dos botões e o texto de estado.
     *
     * @param userAccountState o estado atual da conta DJI
     */
    private void updateLoginState(final UserAccountState userAccountState) {
        ToastUtils.setResultToText(accountStateTV, "Account State: " + userAccountState);
        post(() -> {
            loginBtn.setEnabled(userAccountState != UserAccountState.AUTHORIZED);
            logoutBtn.setEnabled(userAccountState != UserAccountState.NOT_LOGGED_IN);
        });
    }

    /**
     * Infla o layout e liga os elementos da UI.
     *
     * <p>Configura a orientação vertical, infla {@link R.layout#view_login},
     * e regista os listeners de clique.
     */
    private void init() {
        setOrientation(VERTICAL);
        inflate(getContext(), R.layout.view_login, this);

        loginBtn = (Button) findViewById(R.id.btn_login);
        logoutBtn = (Button) findViewById(R.id.btn_login_out);
        loginBtn.setOnClickListener(this);
        logoutBtn.setOnClickListener(this);
        accountStateTV = (TextView) findViewById(R.id.tv_account_state_info);
    }
}
