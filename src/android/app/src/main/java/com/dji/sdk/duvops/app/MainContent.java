/**
 * {@code MainContent} — Ecrã principal da aplicação.
 *
 * Responsável por:
 * <ul>
 *   <li>Registar a app no SDK da DJI (modo normal ou LDM)</li>
 *   <li>Gerir permissões de runtime (Android M+)</li>
 *   <li>Mostrar o estado de conexão do drone e modelo</li>
 *   <li>Encaminhar o intent para abrir o voo ({@link FlightActivity})</li>
 *   <li>Ligar/desligar a app às contas DJI quando necessário</li>
 * </ul>
 *
 * @author João Parreira
 * @version 2.0
 */
package com.dji.sdk.duvops.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.dji.sdk.duvops.R;
import com.dji.sdk.duvops.flight.DuvopsView;
import com.dji.sdk.duvops.utils.DialogUtils;
import com.dji.sdk.duvops.utils.GeneralUtils;
import com.dji.sdk.duvops.utils.ToastUtils;
import com.squareup.otto.Subscribe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.realname.AppActivationState;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.keysdk.DJIKey;
import dji.keysdk.KeyManager;
import dji.keysdk.ProductKey;
import dji.keysdk.callback.KeyListener;
import dji.log.DJILog;
import dji.log.GlobalConfig;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.products.Aircraft;
import dji.sdk.realname.AppActivationManager;
import dji.sdk.sdkmanager.BluetoothProductConnector;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.sdkmanager.LDMModule;
import dji.sdk.sdkmanager.LDMModuleType;
import dji.sdk.useraccount.UserAccountManager;
import android.content.Intent;
import com.dji.sdk.duvops.flight.FlightActivity; // Activity de voo

/**
 * Componente de ecrã principal (home screen).
 *
 * <p>Contém botões de registo, estado de conexão, versão do firmware,
 * e campo de configuração do bridge mode.
 *
 * <h3>Fluxo de registo</h3>
 * <pre>
 * Botão clicar → checkAndRequestPermissions() → startSDKRegistration()
 *     → DJISDKManager.registerApp() → startConnectionToProduct()
 *     → onProductConnect() → notifyStatusChange() → refreshSDKRelativeUI()
 * </pre>
 */
public class MainContent extends RelativeLayout {

    /** Tag para log. */
    public static final String TAG = MainContent.class.getName();

    /**
     * Lista de permissões necessárias para o funcionamento correto.
     *
     * <p>Inclui: vibração, internet, WiFi, localização (coarse/fine),
     * storage, Bluetooth, e gravação de áudio.
     */
    private static final String[] REQUIRED_PERMISSION_LIST = new String[]{
            Manifest.permission.VIBRATE,       // Rotação do gimbal
            Manifest.permission.INTERNET,     // Requisições API
            Manifest.permission.ACCESS_WIFI_STATE,     // Produtos via WiFi
            Manifest.permission.ACCESS_COARSE_LOCATION, // Mapas
            Manifest.permission.ACCESS_NETWORK_STATE,   // Produtos via WiFi
            Manifest.permission.ACCESS_FINE_LOCATION,   // Mapas
            Manifest.permission.CHANGE_WIFI_STATE,      // Trocar WiFi/USB
            Manifest.permission.WRITE_EXTERNAL_STORAGE, // Ficheiros de log
            Manifest.permission.BLUETOOTH,              // Produtos via Bluetooth
            Manifest.permission.BLUETOOTH_ADMIN,        // Produtos via Bluetooth
            Manifest.permission.READ_EXTERNAL_STORAGE,  // Ficheiros de log
            Manifest.permission.READ_PHONE_STATE,       // UUID do dispositivo no registo
            Manifest.permission.RECORD_AUDIO,           // Acessório de altifalante
    };

    /** Código para o pedido de permissões de runtime. */
    private static final int REQUEST_PERMISSION_CODE = 12345;

    /** Mensagens para o HandlerThread de Bluetooth. */
    private static final int MSG_UPDATE_BLUETOOTH_CONNECTOR = 0;
    private static final int MSG_INFORM_ACTIVATION = 1;
    /** Delay em ms antes de solicitar ativação da app. */
    private static final int ACTIVATION_DELAY_TIME = 3000;

    /** Conector Bluetooth, obtido via DJISDKManager. */
    private static BluetoothProductConnector connector = null;

    /** Garante que só uma registo decorre de cada vez. */
    private final AtomicBoolean isRegistrationInProgress = new AtomicBoolean(false);

    /** Handler para callbacks da UI (progress bar, etc.). */
    private final Handler mHander = new Handler();

    /** Listener para mudanças de conectividade do componente DJI. */
    private final BaseComponent.ComponentListener mDJIComponentListener = new BaseComponent.ComponentListener() {
        @Override
        public void onConnectivityChange(boolean isConnected) {
            Log.d(TAG, "onComponentConnectivityChanged: " + isConnected);
            notifyStatusChange();
        }
    };

    /** Thread para operações Bluetooth. */
    private final HandlerThread mHandlerThread = new HandlerThread("Bluetooth");

    /** Garante que o listener de ativação é registado apenas uma vez. */
    private final AtomicBoolean hasAppActivationListenerStarted = new AtomicBoolean(false);

    /** Contexto da activity que contém este componente. */
    private final Context mContext;

    /** Último percentual de progresso exibido (evita atualizações duplicadas). */
    private int lastProcess = -1;

    // UI elements
    private ProgressBar progressBar;
    private TextView mTextConnectionStatus;
    private TextView mTextProduct;
    private TextView mTextModelAvailable;
    private Button mBtnRegisterApp;
    private Button mBtnRegisterAppForLDM;
    private Button mBtnOpen;
    private Button mBtnBluetooth;
    private EditText mBridgeModeEditText;
    private CheckBox mCheckboxFirmware;

    // Handlers para Bluetooth e UI principal
    private Handler mHandler;
    private Handler mHandlerUI;

    /** Produto DJI atual (drone ou handheld). */
    private BaseProduct mProduct;

    /** Chave SDK para a versão do firmware. */
    private DJIKey firmwareKey;
    /** Listener para a versão do firmware. */
    private KeyListener firmwareVersionUpdater;
    /** Indica se o listener de firmware foi registado. */
    private boolean hasStartedFirmwareListener = false;

    /** Listener do estado de ativação da app. */
    private AppActivationState.AppActivationStateListener appActivationStateListener;

    /** Indica se o registo é para o modo LDM (Load Distribution Module). */
    private boolean isRegisterForLDM = false;

    //region Construction

    public MainContent(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    /**
     * Chamado quando o componente é adicionado à janela de visualização.
     *
     * <p>Regista no event bus, inicializa a UI e inicia o HandlerThread de Bluetooth.
     */
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (isInEditMode()) {
            return;
        }
        App.getEventBus().register(this);
        initUI();
    }
    //endregion

    //region UI Initialization

    /**
     * Liga os elementos da UI e configura os listeners de clique.
     *
     * <p>Configura:
     * <ul>
     *   <li>Botão de registo normal e LDM</li>
     *   <li>Botão de abertura da activity de voo</li>
     *   <li>Campo de texto do bridge mode IP</li>
     * </ul>
     */
    private void initUI() {
        Log.v(TAG, "initUI");

        // Verificar e pedir permissões de runtime
        checkAndRequestPermissions();

        // Obter referências dos elementos da UI
        progressBar = findViewById(R.id.progress_bar);
        mTextConnectionStatus = findViewById(R.id.text_connection_status);
        mTextModelAvailable = findViewById(R.id.text_model_available);
        mTextProduct = findViewById(R.id.text_product_info);
        mBtnRegisterApp = findViewById(R.id.btn_registerApp);
        mBtnRegisterAppForLDM = findViewById(R.id.btn_registerAppForLDM);
        mBtnOpen = findViewById(R.id.btn_open);
        mBridgeModeEditText = findViewById(R.id.edittext_bridge_ip);
        mCheckboxFirmware = findViewById(R.id.checkbox_firmware);

        // Botão de registo normal
        mBtnRegisterApp.setOnClickListener(v -> {
            isRegisterForLDM = false;
            checkAndRequestPermissions();
        });

        // Botão de registo LDM (Load Distribution Module)
        mBtnRegisterAppForLDM.setOnClickListener(v -> {
            isRegisterForLDM = true;
            checkAndRequestPermissions();
        });

        // Botão de abertura da activity de voo com anti-duplicate
        mBtnOpen.setOnClickListener(v -> {
            if (GeneralUtils.isFastDoubleClick()) {
                return;
            }
            Intent intent = new Intent(getContext(), FlightActivity.class);
            getContext().startActivity(intent);
        });

        // Campo de texto do bridge mode — detecta "Enter" e novas linhas
        mBridgeModeEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (event != null && event.isShiftPressed()) {
                return false;
            }
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                handleBridgeIPTextChange();
            }
            return false;
        });

        // Detecta nova linha no campo de texto para submeter o IP automaticamente
        mBridgeModeEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                if (s != null && s.toString().contains("\n")) {
                    String currentText = mBridgeModeEditText.getText().toString();
                    mBridgeModeEditText.setText(currentText.substring(0, currentText.indexOf('\n')));
                    handleBridgeIPTextChange();
                }
            }
        });

        // Mostrar versão do SDK
        ((TextView) findViewById(R.id.text_version)).setText(
                getResources().getString(R.string.sdk_version,
                        DJISDKManager.getInstance().getRegistrationSDKVersion()
                        + " Debug:" + GlobalConfig.DEBUG));
    }
    //endregion

    //region Bridge Mode

    /**
     * Aplica o IP do bridge mode quando o utilizador confirma.
     *
     * @param bridgeIP o IP do servidor bridge
     */
    private void handleBridgeIPTextChange() {
        String bridgeIP = mBridgeModeEditText.getText().toString();
        DJISDKManager.getInstance().enableBridgeModeWithBridgeAppIP(bridgeIP);
        if (!TextUtils.isEmpty(bridgeIP)) {
            ToastUtils.setResultToToast("BridgeMode ON!\nIP: " + bridgeIP);
        }
    }
    //endregion

    //region Lifecycle

    /**
     * Chamado quando o componente entra na janela de visualização.
     *
     * <p>Inicia o HandlerThread de Bluetooth e atualiza a UI com o estado atual do SDK.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!isInEditMode()) {
            refreshSDKRelativeUI();
            mHandlerThread.start();

            // Handler para atualizar o conector Bluetooth periodicamente
            mHandler = new Handler(mHandlerThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MSG_UPDATE_BLUETOOTH_CONNECTOR:
                            connector = App.getBluetoothProductConnector();
                            if (connector != null) {
                                // mBtnBluetooth is intentionally hidden (visibility=gone) —
                                // null-check prevents NPE if layout is changed in the future
                                if (mBtnBluetooth != null) {
                                    mBtnBluetooth.post(() -> mBtnBluetooth.setEnabled(true));
                                }
                                return;
                            } else if ((System.currentTimeMillis() - System.currentTimeMillis()) >= 5000) {
                                DialogUtils.showDialog(getContext(), "Fetch Connector failed, reboot if you want to connect the Bluetooth");
                                return;
                            }
                            // Reenvia a mensagem se ainda não obteve o conector
                            sendDelayMsg(0, MSG_UPDATE_BLUETOOTH_CONNECTOR);
                            break;
                        case MSG_INFORM_ACTIVATION:
                            loginToActivationIfNeeded();
                            break;
                    }
                }
            };
            mHandlerUI = new Handler(Looper.getMainLooper());
        }
    }

    /**
     * Envia uma mensagem com delay ao Handler, se este estiver disponível.
     *
     * @param msg       tipo de mensagem a enviar
     * @param delayMillis delay em milissegundos
     */
    private void sendDelayMsg(int msg, long delayMillis) {
        if (mHandler == null) return;
        if (!mHandler.hasMessages(msg)) {
            mHandler.sendEmptyMessageDelayed(msg, delayMillis);
        }
    }

    /**
     * Chamado quando o componente sai da janela de visualização.
     *
     * <p>Limpa callbacks, handlers e encerra o HandlerThread.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!isInEditMode()) {
            removeFirmwareVersionListener();
            removeAppActivationListenerIfNeeded();
            mHandler.removeCallbacksAndMessages(null);
            mHandlerUI.removeCallbacksAndMessages(null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                mHandlerThread.quitSafely();
            } else {
                mHandlerThread.quit();
            }
            mHandlerUI = null;
            mHandler = null;
        }
    }
    //endregion

    //region SDK UI Updates

    /**
     * Atualiza o texto da versão do firmware.
     *
     * <p>Se o produto não estiver disponível, mostra "N/A".
     * Se disponível e a versão não é vazia, mostra o valor.
     */
    private void updateVersion() {
        String version = null;
        if (mProduct != null) {
            version = mProduct.getFirmwarePackageVersion();
        }

        if (TextUtils.isEmpty(version)) {
            mTextModelAvailable.setText("Firmware version:N/A");
        } else {
            mTextModelAvailable.setText("Firmware version:" + version);
            removeFirmwareVersionListener();
        }
    }

    /**
     * Subscreve o event bus para eventos de conectividade.
     *
     * <p>Reencaminha a atualização da UI para a thread principal.
     *
     * @param event evento de mudança de conectividade
     */
    @Subscribe
    public void onConnectivityChange(MainActivity.ConnectivityChangeEvent event) {
        if (mHandlerUI != null) {
            mHandlerUI.post(() -> refreshSDKRelativeUI());
        }
    }

    /**
     * Atualiza a UI com base no estado atual do produto SDK.
     *
     * <p>Lógica de estado:
     * <ul>
     *   <li><b>Produto conectado:</b> mostra modelo, versão do firmware, habilitar botão de voo</li>
     *   <li><b>Produto desconectado mas com RC:</b> mostra apenas a RC conectada</li>
     *   <li><b>Sem produto:</b> mostra "Sem produto", desabilita botão de voo</li>
     * </ul>
     */
    private void refreshSDKRelativeUI() {
        mProduct = App.getProductInstance();
        Log.d(TAG, "mProduct: " + (mProduct == null ? "null" : "unnull"));

        if (null != mProduct) {
            if (mProduct.isConnected()) {
                mBtnOpen.setEnabled(true);
                String model = mProduct instanceof Aircraft
                        ? mProduct.getModel().getDisplayName()
                        : "DJI HandHeld";
                mTextConnectionStatus.setText("Status: " + model + " connected");

                tryUpdateFirmwareVersionWithListener();

                if (mProduct instanceof Aircraft) {
                    addAppActivationListenerIfNeeded();
                }

                mTextProduct.setText(mProduct.getModel() != null
                        ? mProduct.getModel().getDisplayName()
                        : getContext().getString(R.string.product_information));

            } else if (mProduct instanceof Aircraft) {
                Aircraft aircraft = (Aircraft) mProduct;
                if (aircraft.getRemoteController() != null && aircraft.getRemoteController().isConnected()) {
                    mTextConnectionStatus.setText(R.string.connection_only_rc);
                    mTextProduct.setText(R.string.product_information);
                    mBtnOpen.setEnabled(false);
                    mTextModelAvailable.setText("Firmware version:N/A");
                }
            }
        } else {
            mBtnOpen.setEnabled(false);
            mTextProduct.setText(R.string.product_information);
            mTextConnectionStatus.setText(R.string.connection_loose);
            mTextModelAvailable.setText("Firmware version:N/A");
        }
    }

    /**
     * Regista um listener para atualizar a versão do firmware em tempo real.
     *
     * <p>Usa o {@link KeyManager} da DJI para ouvir mudanças na chave de versão do firmware.
     * O listener é removido automaticamente quando a versão é obtida.
     */
    private void tryUpdateFirmwareVersionWithListener() {
        if (hasStartedFirmwareListener) {
            return;
        }

        firmwareVersionUpdater = (oldValue, newValue) ->
                mHandlerUI.post(this::updateVersion);

        firmwareKey = ProductKey.create(ProductKey.FIRMWARE_PACKAGE_VERSION);
        KeyManager.getInstance().addListener(firmwareKey, firmwareVersionUpdater);
        hasStartedFirmwareListener = true;
        updateVersion();
    }

    /** Remove o listener de versão do firmware registado. */
    private void removeFirmwareVersionListener() {
        if (hasStartedFirmwareListener) {
            if (KeyManager.getInstance() != null) {
                KeyManager.getInstance().removeListener(firmwareVersionUpdater);
            }
        }
        hasStartedFirmwareListener = false;
    }

    /**
     * Regista um listener de ativação se necessário.
     *
     * <p>Se a app não está ativada, agenda a login após 3s e regista o listener
     * para detetar quando o utilizador ativa a app.
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
                AppActivationManager.getInstance().addAppActivationStateListener(appActivationStateListener);
            }
        }
    }

    /** Remove o listener de ativação se foi registado. */
    private void removeAppActivationListenerIfNeeded() {
        if (hasAppActivationListenerStarted.compareAndSet(true, false)) {
            AppActivationManager.getInstance().removeAppActivationStateListener(appActivationStateListener);
        }
    }

    /**
     * Faz login na conta DJI se o estado for "LOGIN_REQUIRED".
     *
     * <p>Usado para ativar a app antes de se poder conectar ao drone.
     */
    private void loginToActivationIfNeeded() {
        if (AppActivationManager.getInstance().getAppActivationState() == AppActivationState.LOGIN_REQUIRED) {
            UserAccountManager.getInstance().logIntoDJIUserAccount(getContext(),
                    new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                        @Override
                        public void onSuccess(UserAccountState userAccountState) {
                            ToastUtils.setResultToToast("Login Successed!");
                        }

                        @Override
                        public void onFailure(DJIError djiError) {
                            ToastUtils.setResultToToast("Login Failed!");
                        }
                    });
        }
    }
    //endregion

    //region Registration & Permissions

    /**
     * Verifica e pede permissões de runtime se necessário.
     *
     * <p>Se todas as permissões estão concedidas, inicia o registo do SDK.
     * Caso contrário, pede ao utilizador as que faltam.
     */
    private void checkAndRequestPermissions() {
        List<String> missingPermission = new ArrayList<>();
        for (String eachPermission : REQUIRED_PERMISSION_LIST) {
            if (ContextCompat.checkSelfPermission(mContext, eachPermission) != PackageManager.PERMISSION_GRANTED) {
                missingPermission.add(eachPermission);
            }
        }

        if (missingPermission.isEmpty()) {
            startSDKRegistration();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions((Activity) mContext,
                    missingPermission.toArray(new String[missingPermission.size()]),
                    REQUEST_PERMISSION_CODE);
        }
    }

    /**
     * Inicia o registo da app no SDK da DJI.
     *
     * <p>Usa uma lock para evitar registos simultâneos. Executa numa thread de fundo
     * e escolhe entre o registo normal ou LDM conforme o checkbox.
     *
     * @see <a href="https://developer.dji.com/doc/mobile-sdk-android">DJI SDK Registration</a>
     */
    private void startSDKRegistration() {
        if (isRegistrationInProgress.compareAndSet(false, true)) {
            AsyncTask.execute(() -> {
                ToastUtils.setResultToToast(mContext.getString(R.string.sdk_registration_doing_message));

                // Configurar LDM module network service para firmware upgrade
                if (mCheckboxFirmware.isChecked()) {
                    DJISDKManager.getInstance().getLDMManager()
                            .setModuleNetworkServiceEnabled(
                                    new LDMModule.Builder()
                                            .moduleType(LDMModuleType.FIRMWARE_UPGRADE)
                                            .enabled(true)
                                            .build());
                } else {
                    DJISDKManager.getInstance().getLDMManager()
                            .setModuleNetworkServiceEnabled(
                                    new LDMModule.Builder()
                                            .moduleType(LDMModuleType.FIRMWARE_UPGRADE)
                                            .enabled(false)
                                            .build());
                }

                if (isRegisterForLDM) {
                    startLDMRegistration();
                } else {
                    startNormalRegistration();
                }
            });
        }
    }

    /** Inicia o registo da app no modo LDM (Load Distribution Module). */
    private void startLDMRegistration() {
        DJISDKManager.getInstance().registerAppForLDM(mContext.getApplicationContext(), new DJISDKManager.SDKManagerCallback() {
            @Override
            public void onRegister(DJIError djiError) {
                if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                    DJILog.e("App registration for LDM", DJISDKError.REGISTRATION_SUCCESS.getDescription());
                    DJISDKManager.getInstance().startConnectionToProduct();
                    ToastUtils.setResultToToast(mContext.getString(R.string.sdk_registration_success_message));
                } else {
                    ToastUtils.setResultToToast(mContext.getString(R.string.sdk_registration_message) + djiError.getDescription());
                }
                Log.v(TAG, djiError.getDescription());
                hideProcess();
            }

            @Override public void onProductDisconnect() {
                Log.d(TAG, "onProductDisconnect");
                notifyStatusChange();
            }

            @Override
            public void onProductConnect(BaseProduct baseProduct) {
                Log.d(TAG, String.format("onProductConnect newProduct:%s", baseProduct));
                notifyStatusChange();
                // Configurar a camera para gravar vídeo após conexão
                if (baseProduct instanceof Aircraft) {
                    Camera camera = ((Aircraft) baseProduct).getCamera();
                    if (camera != null) {
                        camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO,
                                error -> ToastUtils.setResultToToast("Camera mode set after connection."));
                    }
                }
            }

            @Override public void onProductChanged(BaseProduct baseProduct) { notifyStatusChange(); }

            @Override
            public void onComponentChange(BaseProduct.ComponentKey componentKey,
                                          BaseComponent oldComponent, BaseComponent newComponent) {
                if (newComponent != null) {
                    newComponent.setComponentListener(mDJIComponentListener);
                    if (componentKey == BaseProduct.ComponentKey.FLIGHT_CONTROLLER) {
                        showDBVersion();
                    }
                }
                Log.d(TAG, String.format("onComponentChange key:%s, oldComponent:%s, newComponent:%s",
                        componentKey, oldComponent, newComponent));
                notifyStatusChange();
            }

            @Override public void onInitProcess(DJISDKInitEvent djisdkInitEvent, int i) { }

            @Override
            public void onDatabaseDownloadProgress(long current, long total) {
                int progress = (int) (100 * current / total);
                if (progress == lastProcess) return;
                lastProcess = progress;
                showProgress(progress);
                if (progress % 25 == 0) {
                    ToastUtils.setResultToToast("DB load process : " + progress);
                } else if (progress == 0) {
                    ToastUtils.setResultToToast("DB load begin");
                }
            }
        });
    }

    /** Inicia o registo da app no modo normal (sem LDM). */
    private void startNormalRegistration() {
        DJISDKManager.getInstance().registerApp(mContext.getApplicationContext(), new DJISDKManager.SDKManagerCallback() {
            @Override
            public void onRegister(DJIError djiError) {
                if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                    DJILog.e("App registration", DJISDKError.REGISTRATION_SUCCESS.getDescription());
                    DJISDKManager.getInstance().startConnectionToProduct();
                    ToastUtils.setResultToToast(mContext.getString(R.string.sdk_registration_success_message));
                } else {
                    ToastUtils.setResultToToast(mContext.getString(R.string.sdk_registration_message) + djiError.getDescription());
                }
                Log.v(TAG, djiError.getDescription());
                hideProcess();
            }

            @Override public void onProductDisconnect() {
                Log.d(TAG, "onProductDisconnect");
                notifyStatusChange();
            }

            @Override
            public void onProductConnect(BaseProduct baseProduct) {
                Log.d(TAG, String.format("onProductConnect newProduct:%s", baseProduct));
                notifyStatusChange();
                // Configurar a camera para gravar vídeo após conexão
                if (baseProduct instanceof Aircraft) {
                    Camera camera = ((Aircraft) baseProduct).getCamera();
                    if (camera != null) {
                        camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO,
                                error -> ToastUtils.setResultToToast("Camera mode set after connection."));
                    }
                }
            }

            @Override public void onProductChanged(BaseProduct baseProduct) { notifyStatusChange(); }

            @Override
            public void onComponentChange(BaseProduct.ComponentKey componentKey,
                                          BaseComponent oldComponent, BaseComponent newComponent) {
                if (newComponent != null) {
                    newComponent.setComponentListener(mDJIComponentListener);
                    if (componentKey == BaseProduct.ComponentKey.FLIGHT_CONTROLLER) {
                        showDBVersion();
                    }
                }
                Log.d(TAG, String.format("onComponentChange key:%s, oldComponent:%s, newComponent:%s",
                        componentKey, oldComponent, newComponent));
                notifyStatusChange();
            }

            @Override public void onInitProcess(DJISDKInitEvent djisdkInitEvent, int i) { }

            @Override
            public void onDatabaseDownloadProgress(long current, long total) {
                int progress = (int) (100 * current / total);
                if (progress == lastProcess) return;
                lastProcess = progress;
                showProgress(progress);
                if (progress % 25 == 0) {
                    ToastUtils.setResultToToast("DB load process : " + progress);
                } else if (progress == 0) {
                    ToastUtils.setResultToToast("DB load begin");
                }
            }
        });
    }

    //endregion

    //region Progress & DB Version

    /** Mostra a barra de progresso no handler principal. */
    private void showProgress(final int progress) {
        mHander.post(() -> {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(progress);
        });
    }

    /**
     * Verifica a versão da base de dados de zonas de voo (fly zones).
     *
     * <p>Executa 3s após a conexão para dar tempo ao SDK inicializar.
     */
    private void showDBVersion() {
        mHander.postDelayed(() -> {
            if (DJISDKManager.getInstance().getFlyZoneManager() == null) {
                return;
            }
            DJISDKManager.getInstance().getFlyZoneManager().getPreciseDatabaseVersion(
                    new CommonCallbacks.CompletionCallbackWith<String>() {
                        @Override
                        public void onSuccess(String s) {
                            ToastUtils.setResultToToast("db load success ! version : " + s);
                        }

                        @Override
                        public void onFailure(DJIError djiError) {
                            ToastUtils.setResultToToast("db load failure ! get version error : " + djiError.getDescription());
                        }
                    });
        }, 3000);
    }

    /** Esconde a barra de progresso no handler principal. */
    private void hideProcess() {
        mHander.post(() -> progressBar.setVisibility(View.GONE));
    }

    /**
     * Notifica todos os subscrevedores do event bus que o estado de conexão mudou.
     */
    private void notifyStatusChange() {
        App.getEventBus().post(new MainActivity.ConnectivityChangeEvent());
    }
    //endregion
}
