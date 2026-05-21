/**
 * {@code MainContent} — Ecrã de registo SDK e lançamento do benchmark.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Registar a app no DJI SDK (normal registration)</li>
 *   <li>Gerir permissões de runtime (Android M+)</li>
 *   <li>Mostrar o estado de conexão do drone e modelo</li>
 *   <li>Abrir {@link FlightActivity} quando o drone está conectado</li>
 *   <li>Gerir a activação da conta DJI (necessária para SDK funcionar)</li>
 * </ul>
 *
 * <h3>Fluxo de registo</h3>
 * <pre>
 * Botão "Registar App" → checkAndRequestPermissions() → startSDKRegistration()
 *     → DJISDKManager.registerApp() → startConnectionToProduct()
 *     → onProductConnect() → notifyStatusChange() → refreshSDKRelativeUI()
 *     → botão "Open Benchmark" activado → FlightActivity → DuvopsView
 * </pre>
 *
 * @author João Parreira
 * @version 3.0
 */
package com.dji.sdk.duvops.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.dji.sdk.duvops.R;
import com.dji.sdk.duvops.flight.FlightActivity;
import com.dji.sdk.duvops.utils.GeneralUtils;
import com.dji.sdk.duvops.utils.ToastUtils;
import com.squareup.otto.Subscribe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.realname.AppActivationState;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.log.DJILog;
import dji.log.GlobalConfig;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.realname.AppActivationManager;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.useraccount.UserAccountManager;

/**
 * Ecrã principal (home screen) — registo do SDK e lançamento do benchmark.
 */
public class MainContent extends RelativeLayout {

    public static final String TAG = MainContent.class.getName();

    /** Permissões necessárias pelo DJI SDK e pela app. */
    private static final String[] REQUIRED_PERMISSION_LIST = new String[]{
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
    };

    private static final int  REQUEST_PERMISSION_CODE  = 12345;
    private static final int  MSG_INFORM_ACTIVATION    = 1;
    private static final int  ACTIVATION_DELAY_TIME    = 3000;

    private final AtomicBoolean isRegistrationInProgress = new AtomicBoolean(false);
    private final Handler       mHander    = new Handler();
    private final BaseComponent.ComponentListener mDJIComponentListener =
            new BaseComponent.ComponentListener() {
                @Override
                public void onConnectivityChange(boolean isConnected) {
                    Log.d(TAG, "onComponentConnectivityChanged: " + isConnected);
                    notifyStatusChange();
                }
            };
    private final HandlerThread mHandlerThread = new HandlerThread("Activation");
    private final AtomicBoolean hasAppActivationListenerStarted = new AtomicBoolean(false);
    private final Context       mContext;

    private int lastProcess = -1;

    // UI
    private ProgressBar progressBar;
    private TextView    mTextConnectionStatus;
    private TextView    mTextProduct;
    private Button      mBtnRegisterApp;
    private Button      mBtnOpen;

    // Handlers
    private Handler mHandler;
    private Handler mHandlerUI;

    private BaseProduct mProduct;
    private AppActivationState.AppActivationStateListener appActivationStateListener;

    // ─────────────────────────────────────────────────────────────────────────

    public MainContent(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (isInEditMode()) return;
        App.getEventBus().register(this);
        initUI();
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    /**
     * Liga os elementos da UI e configura os listeners.
     *
     * <p>Fluxo simplificado: registo normal → open benchmark.
     * Sem LDM, bridge mode, ou bluetooth.
     */
    private void initUI() {
        Log.v(TAG, "initUI");
        checkAndRequestPermissions();

        progressBar           = findViewById(R.id.progress_bar);
        mTextConnectionStatus = findViewById(R.id.text_connection_status);
        mTextProduct          = findViewById(R.id.text_product_info);
        mBtnRegisterApp       = findViewById(R.id.btn_registerApp);
        mBtnOpen              = findViewById(R.id.btn_open);

        mBtnRegisterApp.setOnClickListener(v -> checkAndRequestPermissions());

        mBtnOpen.setOnClickListener(v -> {
            if (GeneralUtils.isFastDoubleClick()) return;
            getContext().startActivity(new Intent(getContext(), FlightActivity.class));
        });

        ((TextView) findViewById(R.id.text_version)).setText(
                getResources().getString(R.string.sdk_version,
                        DJISDKManager.getInstance().getRegistrationSDKVersion()
                        + " Debug:" + GlobalConfig.DEBUG));
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) return;
        refreshSDKRelativeUI();
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_INFORM_ACTIVATION) {
                    loginToActivationIfNeeded();
                }
            }
        };
        mHandlerUI = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (isInEditMode()) return;
        removeAppActivationListenerIfNeeded();
        mHandler.removeCallbacksAndMessages(null);
        mHandlerUI.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mHandlerThread.quitSafely();
        } else {
            mHandlerThread.quit();
        }
        mHandlerUI = null;
        mHandler   = null;
    }

    // ── SDK status UI ────────────────────────────────────────────────────────

    /**
     * Subscreve eventos de conectividade do drone e actualiza a UI.
     */
    @Subscribe
    public void onConnectivityChange(MainActivity.ConnectivityChangeEvent event) {
        if (mHandlerUI != null) mHandlerUI.post(this::refreshSDKRelativeUI);
    }

    /**
     * Actualiza a UI com base no estado de conexão do produto DJI.
     */
    private void refreshSDKRelativeUI() {
        mProduct = App.getProductInstance();
        Log.d(TAG, "mProduct: " + (mProduct == null ? "null" : "connected"));

        if (mProduct != null && mProduct.isConnected()) {
            mBtnOpen.setEnabled(true);
            String model = mProduct instanceof Aircraft
                    ? mProduct.getModel().getDisplayName()
                    : "DJI HandHeld";
            mTextConnectionStatus.setText("Status: " + model + " connected");
            mTextProduct.setText(mProduct.getModel() != null
                    ? mProduct.getModel().getDisplayName()
                    : getContext().getString(R.string.product_information));
            if (mProduct instanceof Aircraft) addAppActivationListenerIfNeeded();

        } else if (mProduct instanceof Aircraft) {
            Aircraft aircraft = (Aircraft) mProduct;
            if (aircraft.getRemoteController() != null && aircraft.getRemoteController().isConnected()) {
                mTextConnectionStatus.setText(R.string.connection_only_rc);
                mTextProduct.setText(R.string.product_information);
                mBtnOpen.setEnabled(false);
            }
        } else {
            mBtnOpen.setEnabled(false);
            mTextProduct.setText(R.string.product_information);
            mTextConnectionStatus.setText(R.string.connection_loose);
        }
    }

    // ── App activation ───────────────────────────────────────────────────────

    /**
     * Regista listener de activação DJI se necessário.
     *
     * <p>Sem activação o SDK não conecta ao drone. Agenda tentativa de login após 3 s.
     */
    private void addAppActivationListenerIfNeeded() {
        if (AppActivationManager.getInstance().getAppActivationState() != AppActivationState.ACTIVATED) {
            sendDelayMsg(MSG_INFORM_ACTIVATION, ACTIVATION_DELAY_TIME);
            if (hasAppActivationListenerStarted.compareAndSet(false, true)) {
                appActivationStateListener = state -> {
                    if (mHandler != null && mHandler.hasMessages(MSG_INFORM_ACTIVATION)) {
                        mHandler.removeMessages(MSG_INFORM_ACTIVATION);
                    }
                    if (state != AppActivationState.ACTIVATED) {
                        sendDelayMsg(MSG_INFORM_ACTIVATION, ACTIVATION_DELAY_TIME);
                    }
                };
                AppActivationManager.getInstance()
                        .addAppActivationStateListener(appActivationStateListener);
            }
        }
    }

    private void removeAppActivationListenerIfNeeded() {
        if (hasAppActivationListenerStarted.compareAndSet(true, false)) {
            AppActivationManager.getInstance()
                    .removeAppActivationStateListener(appActivationStateListener);
        }
    }

    /**
     * Faz login na conta DJI se o estado for LOGIN_REQUIRED.
     */
    private void loginToActivationIfNeeded() {
        if (AppActivationManager.getInstance().getAppActivationState()
                == AppActivationState.LOGIN_REQUIRED) {
            UserAccountManager.getInstance().logIntoDJIUserAccount(getContext(),
                    new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                        @Override public void onSuccess(UserAccountState s) {
                            ToastUtils.setResultToToast("Login succeeded!");
                        }
                        @Override public void onFailure(DJIError e) {
                            ToastUtils.setResultToToast("Login failed: " + e.getDescription());
                        }
                    });
        }
    }

    private void sendDelayMsg(int what, long delayMs) {
        if (mHandler == null || mHandler.hasMessages(what)) return;
        mHandler.sendEmptyMessageDelayed(what, delayMs);
    }

    // ── Registration ─────────────────────────────────────────────────────────

    /**
     * Verifica permissões em falta e pede-as ao utilizador se necessário.
     * Quando todas estão concedidas, inicia o registo do SDK.
     */
    private void checkAndRequestPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSION_LIST) {
            if (ContextCompat.checkSelfPermission(mContext, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (missing.isEmpty()) {
            startSDKRegistration();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions((Activity) mContext,
                    missing.toArray(new String[0]), REQUEST_PERMISSION_CODE);
        }
    }

    /**
     * Inicia o registo da app no SDK DJI (modo normal).
     */
    private void startSDKRegistration() {
        if (isRegistrationInProgress.compareAndSet(false, true)) {
            AsyncTask.execute(() -> {
                ToastUtils.setResultToToast(
                        mContext.getString(R.string.sdk_registration_doing_message));
                startNormalRegistration();
            });
        }
    }

    /**
     * Registo standard (não-LDM) — chamado em modo benchmark.
     */
    private void startNormalRegistration() {
        DJISDKManager.getInstance().registerApp(mContext.getApplicationContext(),
                new DJISDKManager.SDKManagerCallback() {

            @Override
            public void onRegister(DJIError djiError) {
                if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                    DJILog.e("App registration", DJISDKError.REGISTRATION_SUCCESS.getDescription());
                    DJISDKManager.getInstance().startConnectionToProduct();
                    ToastUtils.setResultToToast(
                            mContext.getString(R.string.sdk_registration_success_message));
                } else {
                    ToastUtils.setResultToToast(
                            mContext.getString(R.string.sdk_registration_message)
                            + djiError.getDescription());
                }
                hideProcess();
            }

            @Override public void onProductDisconnect() {
                Log.d(TAG, "onProductDisconnect");
                notifyStatusChange();
            }

            @Override
            public void onProductConnect(BaseProduct baseProduct) {
                Log.d(TAG, "onProductConnect: " + baseProduct);
                notifyStatusChange();
            }

            @Override public void onProductChanged(BaseProduct baseProduct) {
                notifyStatusChange();
            }

            @Override
            public void onComponentChange(BaseProduct.ComponentKey componentKey,
                                          BaseComponent oldComponent, BaseComponent newComponent) {
                if (newComponent != null) {
                    newComponent.setComponentListener(mDJIComponentListener);
                    if (componentKey == BaseProduct.ComponentKey.FLIGHT_CONTROLLER) {
                        showDBVersion();
                    }
                }
                notifyStatusChange();
            }

            @Override public void onInitProcess(DJISDKInitEvent event, int i) { }

            @Override
            public void onDatabaseDownloadProgress(long current, long total) {
                int progress = (int) (100 * current / total);
                if (progress == lastProcess) return;
                lastProcess = progress;
                showProgress(progress);
                if (progress % 25 == 0) ToastUtils.setResultToToast("DB: " + progress + "%");
            }
        });
    }

    // ── Progress & DB ────────────────────────────────────────────────────────

    private void showProgress(final int progress) {
        mHander.post(() -> {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(progress);
        });
    }

    /**
     * Verifica a versão da base de dados de zonas de voo após a conexão do FC.
     */
    private void showDBVersion() {
        mHander.postDelayed(() -> {
            if (DJISDKManager.getInstance().getFlyZoneManager() == null) return;
            DJISDKManager.getInstance().getFlyZoneManager().getPreciseDatabaseVersion(
                    new CommonCallbacks.CompletionCallbackWith<String>() {
                        @Override public void onSuccess(String s) {
                            ToastUtils.setResultToToast("Fly zone DB: " + s);
                        }
                        @Override public void onFailure(DJIError e) {
                            ToastUtils.setResultToToast("DB error: " + e.getDescription());
                        }
                    });
        }, 3000);
    }

    private void hideProcess() {
        mHander.post(() -> progressBar.setVisibility(View.GONE));
    }

    private void notifyStatusChange() {
        App.getEventBus().post(new MainActivity.ConnectivityChangeEvent());
    }
}
