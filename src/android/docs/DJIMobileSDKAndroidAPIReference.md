# DJI Mobile SDK — Android API Reference
> **Package base:** `dji.sdk.*`  
> **SDK version:** 4.16.4 (Mobile SDK v4 for Android)  
> **Source:** https://developer.dji.com/api-reference/android-api/  
> **Compiled:** 2026-05-14  
> **Relevance key**: `[P]` project-used | `[G]` SDK-generic (not used by project)  
> **Cross-ref**: methods used by project include `→` annotation, e.g. `→ FlightManager.java:86`

---

## Índice

1. [Manager Classes](#1-manager-classes)
   - [DJISDKManager [P]](#11-djisdkmanager)
   - [SDKManagerCallback [P]](#12-sdkmanagercallback)
   - [BluetoothProductConnector [G]](#13-bluetoothproductconnector)
   - [KeyManager [P]](#14-keymanager)
   - [FlyZoneManager [G]](#15-flyzonemanager)
   - [AppActivationManager [G]](#16-appactivationmanager)
   - [UserAccountManager [G]](#17-useraccountmanager)
   - [DataProtectionManager [G]](#18-dataprotectionmanager)
   - [LDMManager [G]](#19-ldmmanager)
   - [UTMISSManager [G]](#110-utmissmanager)
   - [FlightHubManager [G]](#111-flighthubmanager)
   - [LiveStreamManager [P]](#112-livestreammanager)
   - [IUASRemoteIDManager [G]](#113-iuasremoteidmanager)
   - [UpgradeManager [G]](#114-upgrademanager)
2. [Base Classes](#2-base-classes)
   - [BaseProduct [P]](#21-baseproduct)
   - [VideoFeeder / VideoFeed [P]](#22-videofeeder--videofeed)
   - [BaseComponent [P]](#23-basecomponent)
3. [Product Classes](#3-product-classes)
   - [Aircraft [P]](#31-aircraft)
   - [HandHeld [G]](#32-handheld)
4. [Component Classes](#4-component-classes)
   - [FlightController [P]](#41-flightcontroller)
   - [RTK [G]](#42-rtk)
   - [RTKNetworkServiceProvider [G]](#43-rtknetworkserviceprovider)
   - [LandingGear [G]](#44-landinggear)
   - [FlightAssistant [G]](#45-flightassistant)
   - [Radar [G]](#46-radar)
   - [Simulator [P]](#47-simulator)
   - [Battery [P]](#48-battery)
   - [Camera [P]](#49-camera)
   - [Gimbal [P]](#410-gimbal)
   - [AirLink [P]](#411-airlink)
   - [RemoteController [P]](#412-remotecontroller)
   - [HandheldController [G]](#413-handheldcontroller)
   - [MobileRemoteController [G]](#414-mobileremotecontroller)
   - [Payload [G]](#415-payload)
   - [Pipeline [G]](#416-pipeline)
   - [AccessoryAggregation [G]](#417-accessoryaggregation)
   - [RTKBaseStation [G]](#418-rtkbasestation)
   - [Lidar [G]](#419-lidar)
5. [Mission Classes](#5-mission-classes)
   - [MissionControl [P]](#51-missioncontrol)
   - [WaypointMissionOperator [P]](#52-waypointmissionoperator)
   - [WaypointV2MissionOperator [G]](#53-waypointv2missionoperator)
   - [FollowMeMissionOperator [G]](#54-followmemissionoperator)
   - [HotpointMissionOperator [G]](#55-hotpointmissionoperator)
   - [IntelligentHotpointMissionOperator [G]](#56-intelligenthotpointmissionoperator)
   - [TapFlyMissionOperator [G]](#57-tapflymissionoperator)
   - [ActiveTrackOperator [G]](#58-activetrackoperator)
   - [PanoramaMissionOperator [G]](#59-panoramamissionoperator)
6. [Misc Classes](#6-misc-classes)
   - [CommonCallbacks [P]](#61-commoncallbacks)
   - [DJIError [P]](#62-djierror)
   - [DJIDiagnostics [G]](#63-djidiagnostics)
   - [DJICodecManager [G]](#64-djicodecmanager)
   - [DJIParamCapability [P]](#65-djiparamcapability)

---

## 1. Manager Classes

### 1.1 DJISDKManager [P]

**Package:** `dji.sdk.sdkmanager`  
**Descrição:** Ponto de entrada do SDK. Gere registo, ligação ao produto e acesso a todos os managers.

```java
synchronized static DJISDKManager getInstance()
```

#### SDK Admin

| Método | Assinatura | → Usado por |
|--------|-----------|------|
| `getInstance` | `synchronized static DJISDKManager getInstance()` | App.java:18; DuvopsView.java:184 |
| `getSDKVersion` | `String getSDKVersion()` | — |
| `registerApp` | `void registerApp(Context ctx, SDKManagerCallback cb)` | App.java:30 |
| `registerAppForLDM` | `void registerAppForLDM(Context ctx, SDKManagerCallback cb)` | — |
| `hasSDKRegistered` | `boolean hasSDKRegistered()` ⚠️ *Deprecated* | — |
| `setCallbackRunInUIThread` | `void setCallbackRunInUIThread(boolean enable)` | — |
| `closeAOAConnection` | `void closeAOAConnection()` | — |
| `USB_ACCESSORY_ATTACHED` | `static final String` | — |

#### Product Connection

| Método | Assinatura | → Usado por |
|--------|-----------|------|
| `getProduct` | `BaseProduct getProduct()` | App.java:22; DuvopsView.java:122 |
| `getBluetoothProductConnector` | `BluetoothProductConnector getBluetoothProductConnector()` | — |
| `setSupportOnlyForBluetoothDevice` | `void setSupportOnlyForBluetoothDevice(boolean bt)` | — |
| `startConnectionToProduct` | `boolean startConnectionToProduct()` | App.java:35 |
| `stopConnectionToProduct` | `void stopConnectionToProduct()` | — |

#### Managers

| Método | Retorna |
|--------|---------|
| `getKeyManager()` | `KeyManager` |
| `getFlyZoneManager()` | `FlyZoneManager` |
| `getMissionControl()` | `MissionControl` |
| `getFlightHubManager()` | `FlightHubManager` |
| `getLiveStreamManager()` | `LiveStreamManager` |
| `getLDMManager()` | `LDMManager` |
| `getAppActivationManager()` | `AppActivationManager` |
| `getRTKNetworkServiceProvider()` | `RTKNetworkServiceProvider` |
| `getUpgradeManager()` | `UpgradeManager` |
| `getUasRemoteIDManager()` | `UASRemoteIDManager` |

#### Debug & Logs

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `enableBridgeModeWithBridgeAppIP` | `void enableBridgeModeWithBridgeAppIP(String ip)` | Modo debug via DJI Bridge App |
| `getLogPath` | `@NonNull String getLogPath()` | Path dos flight logs no dispositivo |
| `getFlycLogPath` | `String getFlycLogPath()` | Path dos compact flight controller logs |

---

### 1.2 SDKManagerCallback [P]

**Package:** `dji.sdk.sdkmanager`  
**Interface** de callback para eventos de registo e produto.

```java
interface SDKManagerCallback {
    void onRegister(DJIError error);
    void onProductDisconnect();
    void onProductConnect(BaseProduct product);
    void onProductChanged(BaseProduct product);
    void onComponentChange(BaseProduct.ComponentKey key,
                           BaseComponent oldComponent,
                           BaseComponent newComponent);
    void onInitProcess(DJISDKInitEvent event, int totalProcess);
    void onDatabaseDownloadProgress(long current, long total);
}
```

---

### 1.3 BluetoothProductConnector [G]

**Package:** `dji.sdk.sdkmanager`  
**Descrição:** Gere ligações Bluetooth entre o dispositivo móvel e produtos DJI.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `searchBluetoothProducts` | `void searchBluetoothProducts(BluetoothDevicesListCallback cb)` | Inicia scan BT e retorna lista de dispositivos |
| `connect` | `void connect(DJIBluetoothDevice device, CommonCallbacks.CompletionCallback cb)` | Liga ao dispositivo BT |
| `disconnect` | `void disconnect(CommonCallbacks.CompletionCallback cb)` | Desliga do dispositivo BT |
| `isConnected` | `boolean isConnected()` | Estado da ligação BT |

**DJIBluetoothDevice (inner class):**
```java
String getName()      // Nome do dispositivo
String getUUID()      // UUID do dispositivo
```

---

### 1.4 KeyManager [P]

**Package:** `dji.sdk.keymanager`  
**Descrição:** Interface de baixo nível para acesso a propriedades/ações do SDK via `DJIKey`. Permite get, set, action e listen a qualquer parâmetro do produto.

```java
@Nullable KeyManager getKeyManager()   // via DJISDKManager
```

| Método | Assinatura | → Usado por |
|--------|-----------|------|
| `getValue` | `void getValue(DJIKey key, GetCallback callback)` | CameraManager.java:46; TelemetryManager.java:209, 215, 235 |
| `setValue` | `void setValue(DJIKey key, Object value, SetCallback callback)` | CameraManager.java:46, 78, 85, 92, 98 |
| `performAction` | `void performAction(DJIKey key, ActionCallback callback, Object... args)` | — |
| `addListener` | `void addListener(DJIKey key, KeyListener listener)` | TelemetryManager.java:129, 140, 148, 163 |
| `removeListener` | `void removeListener(KeyListener listener)` | — |
| `stopAllListening` | `void stopAllListening(Object observerContext)` | — |

**DJIKey subclasses disponíveis:**

| Key Class | Componente |
|-----------|-----------|
| `BatteryKey` | Bateria |
| `CameraKey` | Câmara — `HYBRID_ZOOM_FOCAL_LENGTH`, `CAMERA_VIDEO_STREAM_SOURCE`, `DISPLAY_MODE`, `PIP_POSITION` |
| `FlightControllerKey` | Controlador de voo |
| `GimbalKey` | Gimbal |
| `ProductKey` | Produto |
| `RemoteControllerKey` | Comando — `BATTERY_STATE` |
| `AirLinkKey` | Ligação ar |
| `PayloadKey` | Payload |
| `RadarKey` | Radar |
| `LidarKey` | Lidar |
| `BaseStationKey` | Base station RTK |
| `DiagnosticsKey` | Diagnóstico |
| `HandheldControllerKey` | Controlador handheld |
| `AccessoryAggregationKey` | Acessórios |

**Callbacks:**
```java
interface GetCallback    { void onSuccess(Object value); void onFailure(DJIError error); }
interface SetCallback    { void onSuccess(); void onFailure(DJIError error); }
interface ActionCallback { void onSuccess(Object response); void onFailure(DJIError error); }
interface KeyListener    { void onValueChange(Object oldValue, Object newValue); }
```

---

### 1.5 FlyZoneManager [G]

**Package:** `dji.sdk.flightcontroller`  
**Descrição:** Gere o sistema GEO da DJI — zonas de não-voo, desbloqueio, avisos FlySafe.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `getFlyZonesInSurroundingArea` | `void getFlyZonesInSurroundingArea(CommonCallbacks.CompletionCallbackWith<ArrayList<FlyZoneInformation>> cb)` | Lista zonas na área circundante |
| `unlockFlyZones` | `void unlockFlyZones(ArrayList<Integer> ids, CommonCallbacks.CompletionCallback cb)` | Desbloqueia zonas pelo ID |
| `getUnlockedFlyZonesForAircraft` | `void getUnlockedFlyZonesForAircraft(CommonCallbacks.CompletionCallbackWith<ArrayList<UnlockedZoneGroup>> cb)` | Zonas desbloqueadas pelo aircraft |
| `loadCustomUnlockZoneLicenses` | `void loadCustomUnlockZoneLicenses(ArrayList<CustomUnlockZone> zones, CompletionCallback cb)` | Carrega licenças de zonas custom |
| `getCustomUnlockZonesFromServer` | `void getCustomUnlockZonesFromServer(CompletionCallbackWith<ArrayList<CustomUnlockZone>> cb)` | Obtém zonas custom do servidor |
| `setFlySafeNotificationCallback` | `void setFlySafeNotificationCallback(FlySafeNotification.Callback cb)` | Callback para notificações FlySafe |
| `setFlyForbidStatusUpdatedCallback` | `void setFlyForbidStatusUpdatedCallback(Callback cb)` | Callback para mudanças de estado |

**FlyZoneInformation (data class):**
```java
int getFlyZoneID()
String getName()
FlyZoneType getFlyZoneType()      // CIRCLE, POLY
FlyZoneCategory getCategory()     // WARNING, ENHANCED_WARNING, AUTHORIZATION, RESTRICTED
double getRadius()
LocationCoordinate2D getCoordinate()
ArrayList<SubFlyZoneInformation> getSubFlyZoneInformation()
```

---

### 1.6 AppActivationManager [G]

**Package:** `dji.sdk.products`  
**Descrição:** Verifica estado de activação da app e binding do aircraft.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `getAppActivationState` | `AppActivationState getAppActivationState()` | Estado de activação atual |
| `getAircraftBindingState` | `AircraftBindingState getAircraftBindingState()` | Estado de binding do aircraft |
| `addAppActivationStateListener` | `void addAppActivationStateListener(AppActivationStateListener l)` | Subscreve mudanças de estado |
| `removeAppActivationStateListener` | `void removeAppActivationStateListener(AppActivationStateListener l)` | Remove listener |
| `addAircraftBindingStateListener` | `void addAircraftBindingStateListener(AircraftBindingStateListener l)` | Subscreve binding state |

**AppActivationState enum:** `NOT_SUPPORTED`, `ACTIVATED`, `LOGIN_REQUIRED`  
**AircraftBindingState enum:** `INITIAL`, `BOUND`, `UNBOUND`, `UNBOUND_BUT_CANNOT_BIND`, `UNKNOWN`

---

### 1.7 UserAccountManager [G]

**Package:** `dji.sdk.useraccount`  
**Descrição:** Gere login/logout de conta DJI para funcionalidades que o exigem (ex: desbloquear zonas de autorização).

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `logIntoDJIUserAccount` | `void logIntoDJIUserAccount(Context ctx, UserAccountStateChangeListener l)` | Login na conta DJI |
| `logoutOfDJIUserAccount` | `void logoutOfDJIUserAccount(CompletionCallback cb)` | Logout |
| `getUserAccountState` | `UserAccountState getUserAccountState()` | Estado actual da conta |
| `addUserAccountStateChangeListener` | `void addUserAccountStateChangeListener(UserAccountStateChangeListener l)` | Subscreve mudanças de estado |

**UserAccountState enum:** `NOT_LOGGED_IN`, `NOT_AUTHORIZED`, `AUTHORIZED`, `TOKEN_OUT_OF_DATE`, `UNKNOWN`

---

### 1.8 DataProtectionManager [G]

**Package:** `dji.sdk.products`  
**Descrição:** Controla e monitoriza políticas de proteção de dados pessoais.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `getUserDataProtectionPolicy` | `void getUserDataProtectionPolicy(CompletionCallbackWith<UserDataProtectionPolicy> cb)` | Obtém política actual |
| `setUserDataProtectionPolicy` | `void setUserDataProtectionPolicy(UserDataProtectionPolicy policy, CompletionCallback cb)` | Define política |

---

### 1.9 LDMManager [G]

**Package:** `dji.sdk.ldm`  
**Descrição:** Local Data Mode — coloca o SDK em modo "avião", sem acesso à internet. Útil para ambientes seguros ou restritos.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `isLDMSupported` | `boolean isLDMSupported()` | Verifica suporte LDM |
| `isLDMEnabled` | `boolean isLDMEnabled()` | Verifica se LDM está activo |
| `enableLDM` | `void enableLDM(LDMModule[] modules, LDMCallback cb)` | Activa LDM para os módulos especificados |
| `disableLDM` | `void disableLDM(LDMCallback cb)` | Desactiva LDM |

**LDMModule (inner class com Builder):**
- `FLIGHT_HUB`, `FLY_ZONE`, `USER_ACCOUNT`, `UTMISS` — features controláveis individualmente

---

### 1.10 UTMISSManager [G]

**Package:** `dji.sdk.utmiss`  
**Descrição:** Integração com sistema UTMISS (UTM Information Sharing System) — reporta dados de voo para sistemas de gestão de tráfego aéreo.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setUTMISSCallback` | `void setUTMISSCallback(Callback cb)` | Callback para status de upload |
| `setUTMISSParam` | `void setUTMISSParam(UTMISSParam param, CompletionCallback cb)` | Configura parâmetros UTMISS |
| `isUTMISSEnabled` | `boolean isUTMISSEnabled()` | Estado do UTMISS |
| `startUploadFlightData` | `void startUploadFlightData()` | Inicia upload de dados de voo |
| `stopUploadFlightData` | `void stopUploadFlightData()` | Para upload |

---

### 1.11 FlightHubManager [G]

**Package:** `dji.sdk.flighthub`  
**Descrição:** Integração com DJI FlightHub — plataforma cloud para monitorização de frotas.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `logIn` | `void logIn(String token, CompletionCallback cb)` | Autentica no FlightHub |
| `logOut` | `void logOut(CompletionCallback cb)` | Logout do FlightHub |
| `isLoggedIn` | `boolean isLoggedIn()` | Estado de login |
| `startFlightDataSync` | `void startFlightDataSync(CompletionCallback cb)` | Inicia sincronização de dados |
| `stopFlightDataSync` | `void stopFlightDataSync(CompletionCallback cb)` | Para sincronização |
| `getTeamList` | `void getTeamList(CompletionCallbackWith<ArrayList<Team>> cb)` | Lista de equipas |
| `getOnlineDeviceList` | `void getOnlineDeviceList(CompletionCallbackWith<ArrayList<OnlineDevice>> cb)` | Dispositivos online |
| `getRealTimeFlightData` | `void getRealTimeFlightData(String sn, CompletionCallbackWith<RealTimeFlightData> cb)` | Dados em tempo real de um drone |
| `getFlightStatistics` | `void getFlightStatistics(CompletionCallbackWith<Statistics> cb)` | Estatísticas de voo |

---

### 1.12 LiveStreamManager [P]

**Package:** `dji.sdk.livestream`  
**Descrição:** Streaming de vídeo em tempo real para servidores RTMP.

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `isStreaming` | `boolean isStreaming()` | — |
| `startStream` | `int startStream()` | — (projecto usa RTMP via DuvopsView.java) |
| `stopStream` | `void stopStream()` | — |
| `getLiveUrl` | `String getLiveUrl()` | — |
| `setLiveUrl` | `void setLiveUrl(String url)` | — |
| `getLiveVideoBitRate` | `int getLiveVideoBitRate()` | — |
| `setLiveVideoBitRate` | `void setLiveVideoBitRate(int kbps)` | — |
| `getLiveVideoResolution` | `LiveVideoResolution getLiveVideoResolution()` | — |
| `setLiveVideoResolution` | `void setLiveVideoResolution(LiveVideoResolution res)` | — |
| `setOnErrorListener` | `void setOnErrorListener(OnLiveErrorStatusListener l)` | — |
| `isVideoFeederSupported` | `boolean isVideoFeederSupported()` | — |

**LiveVideoResolution enum:** `RESOLUTION_360P`, `RESOLUTION_540P`, `RESOLUTION_720P`, `RESOLUTION_1080P`, `RESOLUTION_1920_1080`, `AUTO`

---

### 1.13 IUASRemoteIDManager [G]

**Package:** `dji.sdk.remoteId`  
**Descrição:** Gere Remote ID (identificação remota de UAS) — conformidade com regulamentações UAS Remote ID.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `getUASRemoteIDStatus` | `UASRemoteIDStatus getUASRemoteIDStatus()` | Status atual do Remote ID |
| `addUASRemoteIDStatusListener` | `void addUASRemoteIDStatusListener(UASRemoteIDStatusListener l)` | Subscreve mudanças de status |
| `removeUASRemoteIDStatusListener` | `void removeUASRemoteIDStatusListener(UASRemoteIDStatusListener l)` | Remove listener |

**UASRemoteIDStatus fields:**
```java
boolean isRemoteIdConnected()
RemoteIDWorkingState getWorkingState()  // IDLE, WORKING, ERROR
```

---

### 1.14 UpgradeManager [G]

**Package:** `dji.sdk.upgrade`  
**Descrição:** Gere upgrades de firmware para componentes do produto.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `getUpgradeableComponents` | `void getUpgradeableComponents(CompletionCallbackWith<ArrayList<UpgradeComponent>> cb)` | Lista componentes com upgrade disponível |
| `addUpgradeComponentChangeListener` | `void addUpgradeComponentChangeListener(UpgradeComponentChangeListener l)` | Listener para mudanças de componentes |
| `removeUpgradeComponentChangeListener` | `void removeUpgradeComponentChangeListener(UpgradeComponentChangeListener l)` | Remove listener |

**UpgradeComponent:**
```java
String getComponentName()
FirmwareInformation getLatestFirmwareInformation()
FirmwareInformation getCurrentFirmwareInformation()
void startUpgrade(UpgradeFirmwareListener listener)
void stopUpgrade(CompletionCallback cb)
```

---

## 2. Base Classes

### 2.1 BaseProduct [P]

**Package:** `dji.sdk.base`  
**Descrição:** Classe base abstracta para todos os produtos DJI.

| Método | Assinatura | → Usado por |
|--------|-----------|------|
| `getModel` | `Model getModel()` | DuvopsView.java:134 |
| `getFirmwarePackageVersion` | `String getFirmwarePackageVersion()` | — |
| `isConnected` | `boolean isConnected()` | — |
| `getDiagnostics` | `void getDiagnostics(CompletionCallbackWith<ArrayList<DJIDiagnostics>> cb)` | — |
| `getComponent` | `BaseComponent getComponent(ComponentKey key)` | — |
| `getComponents` | `@NonNull Map<ComponentKey, List<BaseComponent>> getComponents()` | — |
| `getName` | `@Nullable String getName()` | — |
| `setUserDefinedName` | `void setUserDefinedName(String name, CompletionCallback cb)` | — |

**ComponentKey enum (acesso a componentes):**  
`FLIGHT_CONTROLLER`, `CAMERA`, `GIMBAL`, `REMOTE_CONTROLLER`, `WIRELESS_LINK`, `BATTERY`, `PAYLOAD`

**Model enum (selecção):**  
`PHANTOM_4_PRO`, `PHANTOM_4_PRO_V2`, `INSPIRE_2`, `MATRICE_300_RTK`, `MATRICE_350_RTK`, `MATRICE_400`, `MAVIC_2_PRO`, `MAVIC_2_ZOOM`, `MAVIC_2_ENT` (enterprise), `MAVIC_2_ENTERPRISE_ADVANCED` (M2EA), `MAVIC_3`, `MINI_3_PRO`, `UNKNOWN_AIRCRAFT`, etc.

---

### 2.2 VideoFeeder / VideoFeed [P]

**Package:** `dji.sdk.codec`

**VideoFeeder** — Fornece feeds de vídeo do produto.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `getInstance` | `static VideoFeeder getInstance()` | Singleton |
| `getPrimaryVideoFeed` | `VideoFeed getPrimaryVideoFeed()` | Feed de vídeo primário |
| `getSecondaryVideoFeed` | `VideoFeed getSecondaryVideoFeed()` | Feed de vídeo secundário |
| `addVideoDataListener` | `void addVideoDataListener(VideoDataListener l, PhysicalSource source)` | Subscreve dados de vídeo raw H.264 |
| `removeVideoDataListener` | `void removeVideoDataListener(VideoDataListener l)` | Remove listener |

**VideoFeed:**
```java
interface VideoDataListener {
    void onReceive(byte[] videoBuffer, int size);
}
void addVideoDataListener(VideoDataListener listener)
void removeVideoDataListener(VideoDataListener listener)
PhysicalSource getVideoSource()
```

**PhysicalSource enum:** `LEFT_CAM`, `RIGHT_CAM`, `TOP_CAM`, `MAIN_CAM`, `FPV_CAM`, `UNKNOWN`

**CameraVideoStreamSource enum (used by CameraManager):** `WIDE`, `ZOOM`, `INFRARED_THERMAL`, `RGB`, `IR`, `UNKNOWN`

---

### 2.3 BaseComponent [P]

**Package:** `dji.sdk.base`  
**Descrição:** Classe base para todos os componentes de um produto DJI.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `isConnected` | `boolean isConnected()` | Estado de ligação do componente |
| `getIndex` | `int getIndex()` | Índice (para multi-component, ex: multi-câmara) |
| `setComponentListener` | `void setComponentListener(ComponentListener l)` | Listener para connect/disconnect |
| `getSerialNumber` | `void getSerialNumber(CompletionCallbackWith<String> cb)` | Número de série |
| `getFirmwareVersion` | `void getFirmwareVersion(CompletionCallbackWith<String> cb)` | Versão de firmware |

---

## 3. Product Classes

### 3.1 Aircraft [P]

**Package:** `dji.sdk.products`  
**Herda de:** `BaseProduct`  
**Descrição:** Representa qualquer produto aircraft DJI. Dá acesso a todos os componentes de voo.

| Método | Retorna | → Usado por |
|--------|--|-|
| `getFlightController()` | `FlightController` | App.java:26; FlightManager.java:59; DuvopsView.java:123, 160 |
| `getCameras()` | `@Nullable List<Camera>` | VideoFeedView.java |
| `getGimbals()` | `@Nullable List<Gimbal>` | VideoFeedView.java |
| `getBattery()` | `@Nullable Battery` | TelemetryManager.java:116; DboidsView.java:142 |
| `getRemoteController()` | `@Nullable RemoteController` | — (legacy) |
| `getAirLink()` | `@Nullable AirLink` | — |
| `getPayload()` | `@Nullable List<Payload>` | — |
| `getAccessoryAggregation()` | `@Nullable AccessoryAggregation` | — |
| `getLidar()` | `@Nullable Lidar` | — |
| `getRadar()` | `@Nullable Radar` | — |

---

### 3.2 HandHeld [G]

**Package:** `dji.sdk.products`  
**Herda de:** `BaseProduct`  
**Descrição:** Representa produtos handheld (Osmo series).

| Método | Retorna | Descrição |
|--------|---------|-----------|
| `getCamera()` | `@Nullable Camera` | Câmara do dispositivo |
| `getGimbal()` | `@Nullable Gimbal` | Gimbal do dispositivo |
| `getHandheldController()` | `@Nullable HandheldController` | Controlador físico |
| `getMobileRemoteController()` | `@Nullable MobileRemoteController` | Controlo via app |
| `getAirLink()` | `@Nullable AirLink` | Link (WiFi) |

---

## 4. Component Classes

### 4.1 FlightController [P]

**Package:** `dji.sdk.flightcontroller`  
**Herda de:** `BaseComponent`  
**Descrição:** Controlador principal de voo. Gere navegação, modos de voo, virtual sticks, IMU, RTH, geo-fence, etc.

#### Voo Básico

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `startTakeoff` | `void startTakeoff(CompletionCallback cb)` | FlightManager.java:89 |
| `startPrecisionTakeoff` | `void startPrecisionTakeoff(CompletionCallback cb)` | — |
| `startLanding` | `void startLanding(CompletionCallback cb)` | FlightManager.java:97 |
| `cancelLanding` | `void cancelLanding(CompletionCallback cb)` | — |
| `startGoHome` | `void startGoHome(CompletionCallback cb)` | FlightManager.java:117 |
| `cancelGoHome` | `void cancelGoHome(CompletionCallback cb)` | — |
| `confirmLanding` | `void confirmLanding(CompletionCallback cb)` | — |
| `turnOnMotors` | `void turnOnMotors(CompletionCallback cb)` | FlightManager.java:106 |
| `turnOffMotors` | `void turnOffMotors(CompletionCallback cb)` | FlightManager.java:108 |

#### Virtual Sticks

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setVirtualStickModeEnabled` | `void setVirtualStickModeEnabled(boolean enable, CompletionCallback cb)` | Activa/desactiva virtual sticks |
| `isVirtualStickControlModeAvailable` | `boolean isVirtualStickControlModeAvailable()` | Verifica disponibilidade |
| `sendVirtualStickFlightControlData` | `void sendVirtualStickFlightControlData(FlightControlData data, CompletionCallback cb)` | Envia comandos de voo |
| `setVirtualStickAdvancedModeEnabled` | `void setVirtualStickAdvancedModeEnabled(boolean enable)` | Modo avançado (permite Attitude mode) |

**FlightControlData:**
```java
new FlightControlData(float pitch, float roll, float yaw, float verticalThrottle)
// Unidades dependem do FlightCoordinateSystem e VerticalControlMode
```

#### Modos de Controlo

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setRollPitchControlMode` | `void setRollPitchControlMode(RollPitchControlMode mode)` | ANGLE ou VELOCITY |
| `setYawControlMode` | `void setYawControlMode(YawControlMode mode)` | ANGLE ou ANGULAR_VELOCITY |
| `setVerticalControlMode` | `void setVerticalControlMode(VerticalControlMode mode)` | VELOCITY ou POSITION |
| `setRollPitchCoordinateSystem` | `void setRollPitchCoordinateSystem(FlightCoordinateSystem sys)` | GROUND ou BODY |

**RollPitchControlMode:** `ANGLE`, `VELOCITY`
**YawControlMode:** `ANGLE`, `ANGULAR_VELOCITY`
**VerticalControlMode:** `VELOCITY`, `POSITION`
**FlightCoordinateSystem:** `GROUND`, `BODY`

#### Estado de Voo

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setStateCallback` | `void setStateCallback(FlightControllerState.Callback cb)` | Push de estado a 10Hz |
| `getState` | — | Ver `FlightControllerState` abaixo |

**FlightControllerState (push data):**
```java
LocationCoordinate3D getAircraftLocation()     // lat, lon, altitude
Attitude getAttitude()                         // pitch, roll, yaw
Velocity3D getVelocity()                       // velocidades X, Y, Z
double getAltitude()                           // altitude barométrica (m)
int getSatelliteCount()                        // satélites GPS
GPSSignalLevel getGPSSignalLevel()             // sinal GPS
boolean isFlying()
boolean isLandingConfirmationNeeded()
FlightMode getFlightMode()                     // MANUAL, ATTI, GPS, JOYSTICK, etc.
GoHomeAssessment getGoHomeAssessment()
float getFlightTimeInSeconds()
int getFlightCount()
boolean areMotorsOn()
float getUltrasonicHeight()                    // altura ultrassónica (m)
boolean isUltrasonicBeingUsed()
float getVelocityX(), getVelocityY(), getVelocityZ()
```

#### RTH e Limites

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setGoHomeHeightInMeters` | `void setGoHomeHeightInMeters(int height, CompletionCallback cb)` | Altitude de retorno (20–500m) |
| `getGoHomeHeightInMeters` | `void getGoHomeHeightInMeters(CompletionCallbackWith<Integer> cb)` | Obtém altitude de retorno |
| `setHomeLocation` | `void setHomeLocation(LocationCoordinate2D loc, CompletionCallback cb)` | Define home point |
| `setHomeLocationUsingAircraftCurrentLocation` | `void setHomeLocationUsingAircraftCurrentLocation(CompletionCallback cb)` | Home = posição actual |
| `setMaxFlightHeight` | `void setMaxFlightHeight(int meters, CompletionCallback cb)` | Limite de altitude (20–500m) |
| `setMaxFlightRadius` | `void setMaxFlightRadius(int meters, CompletionCallback cb)` | Raio máximo |
| `setSmartReturnToHomeEnabled` | `void setSmartReturnToHomeEnabled(boolean en, CompletionCallback cb)` | Smart RTH |

#### IMU e Calibração

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `getIMUState` | `void getIMUState(IMUState.Callback cb)` | Estado do IMU |
| `startIMUCalibration` | `void startIMUCalibration(int index, CompletionCallback cb)` | Inicia calibração IMU |
| `startCompassCalibration` | `void startCompassCalibration(CompletionCallback cb)` | Inicia calibração bússola |
| `stopCompassCalibration` | `void stopCompassCalibration(CompletionCallback cb)` | Para calibração bússola |

#### LEDs, AirSense, Modo de Voo

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setFlightModeRestricted` | `void setFlightModeRestricted(boolean restricted, CompletionCallback cb)` | Restringe mudança de modo |
| `setLEDsEnabledSettings` | `void setLEDsEnabledSettings(LEDsSettings settings, CompletionCallback cb)` | Configura LEDs do drone |
| `setAirSenseSystemWarningCallback` | `void setAirSenseSystemWarningCallback(AirSenseSystemInformation.Callback cb)` | Avisos ADS-B |
| `setOnboardSDKDeviceDataCallback` | `void setOnboardSDKDeviceDataCallback(OnboardSDKDeviceDataCallback cb)` | Dados do OSDK |
| `sendDataToOnboardSDKDevice` | `void sendDataToOnboardSDKDevice(byte[] data, CompletionCallback cb)` | Envia dados para OSDK |

**Subcomponentes:**
```java
Compass getCompass()        // Bússola
RTK getRTK()                // Módulo RTK
Simulator getSimulator()    // Simulador de voo
LandingGear getLandingGear() // Trem de aterragem
FlightAssistant getFlightAssistant() // APAS / obstacle avoidance
```

---

### 4.2 RTK [G]

**Package:** `dji.sdk.flightcontroller`  
**Descrição:** Receptor RTK para posicionamento centimétrico.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setStateCallback` | `void setStateCallback(RTKState.Callback cb)` | Push de estado RTK |
| `setRTKEnabled` | `void setRTKEnabled(boolean enable, CompletionCallback cb)` | Activa/desactiva RTK |
| `isRTKEnabled` | `void isRTKEnabled(CompletionCallbackWith<Boolean> cb)` | Estado actual |
| `setReferenceStationSource` | `void setReferenceStationSource(RTKReferenceStationSource src, CompletionCallback cb)` | BASE_STATION ou NETWORK |
| `getRTKBaseStationList` | `void getRTKBaseStationList(RTKBaseStationListCallback cb)` | Lista estações de base |

**RTKState (push data):**
```java
LocationCoordinate3D getAircraftLocation()     // posição RTK
LocationCoordinate3D getHomePointLocation()    // home point RTK
float getHeading()
RTKPositioningSolution getPositioningSolution() // NONE, SINGLE, FLOAT, FIXED_POINT
boolean isHeadingValid()
ReceiverInfo getMainAntennaMobileStationReceiver()
ReceiverInfo getAuxiliaryAntennaMobileStationReceiver()
LocationStandardDeviation getStandardDeviation()
```

---

### 4.3 RTKNetworkServiceProvider [G]

**Package:** `dji.sdk.flightcontroller`  
**Descrição:** Fornece serviço RTK via rede (NTRIP/DJI Network RTK).

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `loginNetworkServiceAccount` | `void loginNetworkServiceAccount(NetworkServiceSettings settings, CompletionCallback cb)` | Login no serviço de rede |
| `logoutNetworkServiceAccount` | `void logoutNetworkServiceAccount(CompletionCallback cb)` | Logout |
| `getNetworkServicePlansState` | `void getNetworkServicePlansState(CompletionCallbackWith<NetworkServicePlansState> cb)` | Estado dos planos |
| `setNetworkServiceStateCallback` | `void setNetworkServiceStateCallback(NetworkServiceState.Callback cb)` | Push de estado |

---

### 4.4 LandingGear [G]

**Package:** `dji.sdk.flightcontroller`  
**Descrição:** Controla o trem de aterragem retrátil.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setAutomaticMovementEnabled` | `void setAutomaticMovementEnabled(boolean en, CompletionCallback cb)` | Movimento automático |
| `isAutomaticMovementEnabled` | `void isAutomaticMovementEnabled(CompletionCallbackWith<Boolean> cb)` | Estado |
| `retract` | `void retract(CompletionCallback cb)` | Retrai o trem |
| `deploy` | `void deploy(CompletionCallback cb)` | Extende o trem |
| `getState` | `void getState(CompletionCallbackWith<LandingGearState> cb)` | Estado atual |

**LandingGearState enum:** `DEPLOYED`, `RETRACTED`, `DEPLOYING`, `RETRACTING`, `DISCONNECTED`, `UNKNOWN`

---

### 4.5 FlightAssistant [G]

**Package:** `dji.sdk.flightcontroller`  
**Descrição:** Detecção e evitamento de obstáculos (APAS), seguimento visual, palm landing.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setCollisionAvoidanceEnabled` | `void setCollisionAvoidanceEnabled(boolean en, CompletionCallback cb)` | Evitamento de colisão |
| `setUpwardAvoidanceEnabled` | `void setUpwardAvoidanceEnabled(boolean en, CompletionCallback cb)` | Avoidance para cima |
| `setLandingProtectionEnabled` | `void setLandingProtectionEnabled(boolean en, CompletionCallback cb)` | Protecção de aterragem |
| `setPrecisionLandingEnabled` | `void setPrecisionLandingEnabled(boolean en, CompletionCallback cb)` | Aterragem de precisão |
| `setVisionDetectionStateCallback` | `void setVisionDetectionStateCallback(VisionDetectionState.Callback cb)` | Push detecção de obstáculos |
| `setVisionControlStateCallback` | `void setVisionControlStateCallback(VisionControlState.Callback cb)` | Push de estado de controlo |
| `setObstacleAvoidanceBehavior` | `void setObstacleAvoidanceBehavior(ObstacleAvoidanceBehavior b, CompletionCallback cb)` | BYPASS ou BRAKE |

**VisionDetectionState (push data):**
```java
ObstacleSensorPosition getPosition()   // NOSE, TAIL, LEFT, RIGHT, UPWARD, DOWNWARD
float getObstacleDistanceInMeters()
ObstacleDetectionSector[] getDetectionSectors()  // 4 sectores por direcção
boolean isVisionSensorBeingUsed()
```

---

### 4.6 Radar [G]

**Package:** `dji.sdk.radar`  
**Descrição:** Radar de detecção de obstáculos (Matrice 300 RTK / 350 RTK).

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setObstacleAvoidanceEnabled` | `void setObstacleAvoidanceEnabled(boolean en, CompletionCallback cb)` | Activa evitamento |
| `isObstacleAvoidanceEnabled` | `void isObstacleAvoidanceEnabled(CompletionCallbackWith<Boolean> cb)` | Estado |
| `addObstacleDataListener` | `void addObstacleDataListener(Callback cb)` | Dados de obstáculos em tempo real |

---

### 4.7 Simulator [P]

**Package:** `dji.sdk.flightcontroller`  
**Descrição:** Simulador de voo integrado no firmware — permite testar missões sem voar.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `start` | `void start(InitializationData data, CompletionCallback cb)` | Inicia simulação |
| `stop` | `void stop(CompletionCallback cb)` | Para simulação |
| `isSimulatorActive` | `void isSimulatorActive(CompletionCallbackWith<Boolean> cb)` | Estado |
| `setStateCallback` | `void setStateCallback(SimulatorState.Callback cb)` | Push de estado do simulador |

**InitializationData:**
```java
LocationCoordinate2D homeLocation    // Lat/lon do home point
int updateFrequency                  // 2–150 Hz
int satelliteCount                   // GPS satélites simulados (0–20)
```

**SimulatorState (push data):**
```java
double getPositionX(), getPositionY(), getPositionZ()
double getPitch(), getRoll(), getYaw()
boolean areMotorsOn()
```

---

### 4.8 Battery [P]

**Package:** `dji.sdk.battery`  
**Herda de:** `BaseComponent`  
**Descrição:** Bateria inteligente DJI. Suporte a baterias únicas e agregadas (Inspire 2, Matrice).

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `setStateCallback` | `void setStateCallback(BatteryState.Callback cb)` | TelemetryManager.java:118 |
| `getFullChargeCapacity` | `void getFullChargeCapacity(CompletionCallbackWith<Integer> cb)` | — |
| `getChargeRemaining` | `void getChargeRemaining(CompletionCallbackWith<Integer> cb)` | — |
| `getChargeRemainingInPercent` | `void getChargeRemainingInPercent(CompletionCallbackWith<Integer> cb)` | — |
| `getVoltage` | `void getVoltage(CompletionCallbackWith<Integer> cb)` | — |
| `getCurrent` | `void getCurrent(CompletionCallbackWith<Integer> cb)` | — |
| `getTemperature` | `void getTemperature(CompletionCallbackWith<Double> cb)` | — |
| `getCellVoltages` | `void getCellVoltages(CompletionCallbackWith<int[]> cb)` | — |
| `getWarningRecords` | `void getWarningRecords(CompletionCallbackWith<ArrayList<WarningRecord>> cb)` | — |
| `setAggregationStateCallback` | `void setAggregationStateCallback(AggregationState.Callback cb)` | — |

**BatteryState (push data):**
```java
int getChargeRemainingInPercent()
int getChargeRemaining()            // mAh
int getVoltage()                    // mV
int getCurrent()                    // mA
double getTemperature()             // °C
boolean isBeingCharged()
boolean isFirmwareOverdue()
BatteryConnectionState getConnectionState()
```

---

### 4.9 Camera [P]

**Package:** `dji.sdk.camera`  
**Herda de:** `BaseComponent`  
**Descrição:** Câmara DJI. Controla modo de câmara, fotografia, vídeo, zoom, thermal, media, playback.

#### Modos e Captura

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `setMode` | `void setMode(SettingsDefinitions.CameraMode mode, CompletionCallback cb)` | CameraManager.java:85 |
**CameraMode enum:** `SHOOT_PHOTO`, `RECORD_VIDEO`, `PLAYBACK`, `MEDIA_DOWNLOAD`
| `getMode` | `void getMode(CompletionCallbackWith<CameraMode> cb)` | — |
| `startShootPhoto` | `void startShootPhoto(CompletionCallback cb)` | — |
| `stopShootPhoto` | `void stopShootPhoto(CompletionCallback cb)` | — |
| `startRecordVideo` | `void startRecordVideo(CompletionCallback cb)` | — |
| `stopRecordVideo` | `void stopRecordVideo(CompletionCallback cb)` | — |
| `isRecording` | `void isRecording(CompletionCallbackWith<Boolean> cb)` | — |
| `setShootPhotoMode` | `void setShootPhotoMode(ShootPhotoMode mode, CompletionCallback cb)` | — |

#### Exposição

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setExposureMode` | `void setExposureMode(ExposureMode mode, CompletionCallback cb)` | PROGRAM, SHUTTER_PRIORITY, APERTURE_PRIORITY, MANUAL |
| `setISO` | `void setISO(ISO iso, CompletionCallback cb)` | ISO |
| `setShutterSpeed` | `void setShutterSpeed(ShutterSpeed speed, CompletionCallback cb)` | Velocidade de obturador |
| `setAperture` | `void setAperture(Aperture aperture, CompletionCallback cb)` | Abertura (f-number) |
| `setExposureCompensation` | `void setExposureCompensation(ExposureCompensation ec, CompletionCallback cb)` | EV (-5.0 a +5.0) |
| `setWhiteBalance` | `void setWhiteBalance(WhiteBalance wb, CompletionCallback cb)` | White balance |
| `setExposureMeteringMode` | `void setExposureMeteringMode(ExposureMeteringMode mode, CompletionCallback cb)` | CENTER, AVERAGE, SPOT |

#### Foco e Zoom

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `setFocusMode` | `void setFocusMode(FocusMode mode, CompletionCallback cb)` | — |
| `setFocusTarget` | `void setFocusTarget(PointF target, CompletionCallback cb)` | — |
| `setOpticalZoomFocalLength` | `void setOpticalZoomFocalLength(int focalLength, CompletionCallback cb)` | — (projecto usa KeyManager em vez disso) |
| `startContinuousOpticalZoom` | `void startContinuousOpticalZoom(ZoomDirection dir, ZoomSpeed speed, CompletionCallback cb)` | — |
| `stopContinuousOpticalZoom` | `void stopContinuousOpticalZoom(CompletionCallback cb)` | — |
| `setDigitalZoomFactor` | `void setDigitalZoomFactor(float factor, CompletionCallback cb)` | — |

#### Vídeo

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setVideoResolutionAndFrameRate` | `void setVideoResolutionAndFrameRate(ResolutionAndFrameRate raf, CompletionCallback cb)` | Resolução + FPS |
| `setVideoFileFormat` | `void setVideoFileFormat(VideoFileFormat format, CompletionCallback cb)` | MP4, MOV |
| `setVideoStandard` | `void setVideoStandard(VideoStandard std, CompletionCallback cb)` | PAL, NTSC |

#### Thermal

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setThermalPalette` | `void setThermalPalette(ThermalPalette palette, CompletionCallback cb)` | Paleta de cores |
| `setThermalROI` | `void setThermalROI(ThermalROI roi, CompletionCallback cb)` | Região de interesse |
| `setThermalGainMode` | `void setThermalGainMode(ThermalGainMode mode, CompletionCallback cb)` | LOW ou HIGH |
| `setTemperatureDataCallback` | `void setTemperatureDataCallback(TemperatureDataCallback cb)` | Push de temperatura pixel |

#### Estado e Media

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `setSystemStateCallback` | `void setSystemStateCallback(SystemState.Callback cb)` | — |
| `setStorageStateCallback` | `void setStorageStateCallback(StorageState.Callback cb)` | — |
| `getMediaManager` | `MediaManager getMediaManager()` | — |
| `getPlaybackManager` | `PlaybackManager getPlaybackManager()` | — |
| `formatStorage` | `void formatStorage(StorageLocation location, CompletionCallback cb)` | — |

**SystemState (push data):**
```java
CameraMode getMode()
boolean isRecording()
boolean isShootingSinglePhoto()
boolean isStoringPhoto()
int getRecordingTimeInSeconds()
StorageLocation getCurrentVideoStorageLocation()
VideoResolution getRecordedVideoResolution()
int getRecordedVideoFrameRate()
```

**DisplayMode enum (used by CameraManager):** `VISUAL_ONLY`, `PIP`, `SPLIT_SCREEN`
**PIPPosition enum (used by CameraManager):** `SIDE_BY_SIDE`, `TOP_RIGHT`

---

### 4.10 Gimbal [P]

**Package:** `dji.sdk.gimbal`  
**Herda de:** `BaseComponent`

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `rotate` | `void rotate(Rotation rotation, CompletionCallback cb)` | mavic2_gimbal.txt; DboidsView.java:1189 |
| `reset` | `void reset(CompletionCallback cb)` | — |
| `setStateCallback` | `void setStateCallback(GimbalState.Callback cb)` | — (projecto usa KeyManager via TelemetryManager) |
| `setYawSimultaneousFollowEnabled` | `void setYawSimultaneousFollowEnabled(boolean en, CompletionCallback cb)` | — |
| `setPitchRangeExtensionEnabled` | `void setPitchRangeExtensionEnabled(boolean en, CompletionCallback cb)` | — |
| `fineTuneGimbalHorizontally` | `void fineTuneGimbalHorizontally(float value, CompletionCallback cb)` | — |
| `fineTuneGimbalVertically` | `void fineTuneGimbalVertically(float value, CompletionCallback cb)` | — |
| `startCalibration` | `void startCalibration(CompletionCallback cb)` | — |
| `setGimbalWorkMode` | `void setGimbalWorkMode(GimbalWorkMode mode, CompletionCallback cb)` | — |

**RotationMode enum:** `ABSOLUTE_ANGLE`, `RELATIVE_ANGLE`, `SPEED`
**GimbalWorkMode enum:** `FREE`, `FPV`, `YAW_FOLLOW`

**Rotation (Builder):**
```java
Rotation.Builder()
    .mode(RotationMode.ABSOLUTE_ANGLE / RELATIVE_ANGLE / SPEED)
    .pitch(float)        // graus
    .roll(float)         // graus
    .yaw(float)          // graus
    .time(double)        // segundos para completar
    .build()
```

**GimbalState (push data):**
```java
Attitude getAttitudeInDegrees()     // pitch, roll, yaw actuais
boolean isPitchAtStop()
boolean isRollAtStop()
boolean isYawAtStop()
GimbalMode getMode()
```

---

### 4.11 AirLink [P]

**Package:** `dji.sdk.airlink`  
**Herda de:** `BaseComponent`  
**Descrição:** Subsistema de link de comunicação. Pode ser LightbridgeLink, OcuSyncLink ou WiFiLink.

**Base AirLink:**
```java
@Nullable LightbridgeLink getLightbridgeLink()
@Nullable OcuSyncLink getOcuSyncLink()
@Nullable WiFiLink getWiFiLink()
void setDownlinkSignalQualityCallback(SignalQualityCallback cb)
void setUplinkSignalQualityCallback(SignalQualityCallback cb)
```
→ Acessado via `Aircraft.getAirLink()` (legacy, não usado no projecto actual).

**LightbridgeLink:**

| Método | Descrição |
|--------|-----------|
| `setChannelSelectionMode(LightbridgeChannelSelectionMode, cb)` | AUTO ou MANUAL |
| `setChannel(int, cb)` | Canal (1–8) |
| `setVideoDataRate(LightbridgeDataRate, cb)` | Taxa de vídeo |
| `setTransmissionMode(LightbridgeTransmissionMode, cb)` | HIGH_QUALITY, LOW_LATENCY, AUTO |
| `setFrequencyBand(LightbridgeFrequencyBand, cb)` | 2.4GHz, 5.8GHz, DUAL |

**OcuSyncLink:**

| Método | Descrição |
|--------|-----------|
| `setChannelSelectionMode(OcuSyncChannelSelectionMode, cb)` | AUTO ou MANUAL |
| `setChannel(int, cb)` | Canal (1–8 para 2.4GHz, 1–32 para 5.8GHz) |
| `setFrequencyBand(OcuSyncFrequencyBand, cb)` | 2.4GHz, 5.8GHz, AUTO |
| `setVideoDataRateCallback(VideoDataRateCallback)` | Callback para bitrate de vídeo |
| `setWarningMessageCallback(WarningMessagesCallback)` | Mensagens de aviso |

**WiFiLink:**

| Método | Descrição |
|--------|-----------|
| `setSSID(String, cb)` | Define SSID do AP |
| `setPassword(String, cb)` | Define password do AP |
| `setWiFiFrequencyBand(WiFiFrequencyBand, cb)` | 2.4GHz ou 5GHz |
| `rebootWiFi(cb)` | Reinicia módulo WiFi |

---

### 4.12 RemoteController [P]

**Package:** `dji.sdk.remotecontroller`  
**Herda de:** `BaseComponent`

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `setHardwareStateCallback` | `void setHardwareStateCallback(HardwareState.HardwareStateCallback cb)` | — (legacy) |
| `setBatteryStateCallback` | `void setBatteryStateCallback(BatteryState.Callback cb)` | — (projecto usa KeyManager via TelemetryManager) |
| `setGPSDataCallback` | `void setGPSDataCallback(Callback cb)` | — (legacy) |
| `setName` | `void setName(String name, CompletionCallback cb)` | — (legacy) |
| `getSerialNumber` | `void getSerialNumber(CompletionCallbackWith<String> cb)` | — (legacy) |
| `setAircraftMapping` | `void setAircraftMapping(AircraftMapping mapping, CompletionCallback cb)` | — (legacy) |
| `setCustomButtonTags` | `void setCustomButtonTags(CustomButtonTags tags, CompletionCallback cb)` | — (legacy) |
| `enterMasterSearchingMode` | `void enterMasterSearchingMode(MasterSearchingCallback cb)` | — (legacy) |
| `startMasterDeviceSearch` | `void startMasterDeviceSearch(MasterSearchingCallback cb)` | — (legacy) |
| `connectToMasterWithAuthorizationInfo` | `void connectToMasterWithAuthorizationInfo(AuthorizationInfo info, CompletionCallback cb)` | — (legacy) |
| `setMasterSlaveStateCallback` | `void setMasterSlaveStateCallback(MasterSlaveState.Callback cb)` | — (legacy) |

**HardwareState (push data):**
```java
Stick getLeftStick()     // position (-660 a 660)
Stick getRightStick()
Button getReturnToHomeButton()
Button getRecordButton()
Button getShutterButton()
Button getPlaybackButton()
RightDial getRightDial()
FiveDButton getFiveDButton()
```

---

### 4.13 HandheldController [G]

**Package:** `dji.sdk.handheld`  
**Herda de:** `BaseComponent`  
**Para:** Osmo series.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setHardwareStateCallback` | `void setHardwareStateCallback(HardwareState.Callback cb)` | Push de estado do hardware |
| `setHandheldMode` | `void setHandheldMode(HandheldMode mode, CompletionCallback cb)` | LOCK, FPV, FOLLOW |
| `setLEDsSettings` | `void setLEDsSettings(LEDCommand cmd, CompletionCallback cb)` | Controla LEDs |

---

### 4.14 MobileRemoteController [G]

**Package:** `dji.sdk.mobilerc`  
**Descrição:** Controla o produto via gestos/interface na app (quando não há RC físico).

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setLeftStickHorizontal` | `void setLeftStickHorizontal(int value)` | -660 a 660 |
| `setLeftStickVertical` | `void setLeftStickVertical(int value)` | -660 a 660 |
| `setRightStickHorizontal` | `void setRightStickHorizontal(int value)` | -660 a 660 |
| `setRightStickVertical` | `void setRightStickVertical(int value)` | -660 a 660 |
| `setGoHomeButton` | `void setGoHomeButton(boolean pressed)` | Botão RTH |

---

### 4.15 Payload [G]

**Package:** `dji.sdk.payload`  
**Herda de:** `BaseComponent`  
**Descrição:** Interface para payloads de terceiros via DJI Payload SDK.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `getPayloadProductName` | `void getPayloadProductName(CompletionCallbackWith<String> cb)` | Nome do payload |
| `setCommandDataCallback` | `void setCommandDataCallback(CommandDataCallback cb)` | Dados de comando do payload |
| `setVideoDataReceivedCallback` | `void setVideoDataReceivedCallback(VideoDataReceivedCallback cb)` | Dados de vídeo do payload |
| `setStreamDataCallback` | `void setStreamDataCallback(StreamDataCallback cb)` | Stream de dados |
| `setWidgetValueChangedCallback` | `void setWidgetValueChangedCallback(WidgetValueChangedCallback cb)` | Mudanças de widget |
| `sendDataToPayload` | `void sendDataToPayload(byte[] data, CompletionCallback cb)` | Envia dados para o payload |
| `downloadWidgets` | `void downloadWidgets(int count, PayloadWidgetDownloadListener l)` | Download de widgets |
| `getActivateInfo` | `void getActivateInfo(CompletionCallbackWith<ActivateInfo> cb)` | Info de activação do payload |

---

### 4.16 Pipeline [G]

**Package:** `dji.sdk.pipeline`  
**Descrição:** Canal de comunicação de dados bidirecional entre app e Onboard SDK / payload (alta largura de banda).

```java
// Via DJIPipelines
void connect(int id, NetworkType type, CompletionCallbackWith<Pipeline> cb)
void disconnect(int id, NetworkType type, CompletionCallback cb)

// Pipeline (canal individual)
int getId()
void writeData(byte[] data, CompletionCallbackWith<Integer> cb)  // retorna bytes escritos
void readData(int readLength, CompletionCallbackWith<byte[]> cb)
```

**NetworkType enum:** `STABLE_BANDWIDTH`, `UNRELIABLE_BANDWIDTH`

---

### 4.17 AccessoryAggregation [G]

**Package:** `dji.sdk.accessory`  
**Herda de:** `BaseComponent`  
**Descrição:** Agrega acessórios montados no drone: spotlight, speaker, beacon.

```java
@Nullable Spotlight getSpotlight()
@Nullable Speaker getSpeaker()
@Nullable Beacon getBeacon()
void setAggregationStateCallback(AccessoryAggregationState.Callback cb)
```

**Spotlight:**
```java
void setEnabled(boolean en, CompletionCallback cb)
void setBrightness(int percent, CompletionCallback cb)   // 0–100%
void setStateCallback(SpotlightState.Callback cb)
```

**Speaker:**
```java
void setAudioFileRepeatTimes(int times, CompletionCallback cb)   // 0 = loop
void setVolume(int vol, CompletionCallback cb)                    // 0–100
void startPlaying(CompletionCallback cb)
void stopPlaying(CompletionCallback cb)
void downloadAudioFile(String url, CompletionCallbackWith<AudioFileInfo> cb)
void setStateCallback(SpeakerState.Callback cb)
```

**Beacon:**
```java
void setEnabled(boolean en, CompletionCallback cb)
```

---

### 4.18 RTKBaseStation [G]

**Package:** `dji.sdk.basestation`  
**Herda de:** `BaseComponent`  
**Descrição:** Estação de base RTK (D-RTK 2).

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setBaseStationStateCallback` | `void setBaseStationStateCallback(BaseStationState.Callback cb)` | Push de estado |
| `setBaseStationBatteryStateCallback` | `void setBaseStationBatteryStateCallback(BaseStationBatteryState.Callback cb)` | Push de bateria |
| `setName` | `void setName(String name, CompletionCallback cb)` | Nome da estação |
| `startEquipmentSelfCheck` | `void startEquipmentSelfCheck(CompletionCallback cb)` | Auto-diagnóstico |
| `getMobileStationCoordinateSystemSettings` | `void getMobileStationCoordinateSystemSettings(CompletionCallbackWith<CoordinateSystemSettings> cb)` | Sistema de coordenadas |

---

### 4.19 Lidar [G]

**Package:** `dji.sdk.lidar`  
**Herda de:** `BaseComponent`  
**Descrição:** Sensor Lidar para nuvem de pontos em tempo real.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `setPointCloudLiveDataEnabled` | `void setPointCloudLiveDataEnabled(boolean en, CompletionCallback cb)` | Activa stream de nuvem de pontos |
| `setPointCloudRecordEnabled` | `void setPointCloudRecordEnabled(boolean en, CompletionCallback cb)` | Activa gravação |
| `addPointCloudLiveDataListener` | `void addPointCloudLiveDataListener(DJIPointCloudLiveDataListener l)` | Listener de dados live |
| `addPointCloudStatusListener` | `void addPointCloudStatusListener(DJIPointCloudStatusListener l)` | Listener de estado de gravação |
| `setLidarPointCloudRecordDataSource` | `void setLidarPointCloudRecordDataSource(PointCloudRecordDataSource src, CompletionCallback cb)` | Fonte de dados |

**PointCloudLiveViewData:**
```java
float[] getPointCloud()              // Array de pontos XYZ
int getPointCount()
long getTimestamp()
```

---

## 5. Mission Classes

### 5.1 MissionControl [P]

**Package:** `dji.sdk.mission`  
**Descrição:** Orquestra missões complexas via Timeline — sequência de `TimelineElement` (acções e missões).

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `scheduleElement` | `DJIError scheduleElement(TimelineElement element)` | — (Timeline API — projecto usa WaypointMissionOperator em vez disso) |
| `scheduleElements` | `DJIError scheduleElements(List<TimelineElement> elements)` | — |
| `unscheduleElement` | `void unscheduleElement(TimelineElement element)` | — |
| `unscheduleEverything` | `void unscheduleEverything()` | — |
| `startTimeline` | `void startTimeline()` | — |
| `stopTimeline` | `void stopTimeline()` | — |
| `pauseTimeline` | `void pauseTimeline()` | — |
| `resumeTimeline` | `void resumeTimeline()` | — |
| `addListener` | `void addListener(Listener l)` | — |
| `removeListener` | `void removeListener(Listener l)` | — |
| `getRunningElement` | `TimelineElement getRunningElement()` | — |
| `isTimelineRunning` | `boolean isTimelineRunning()` | — |

**TimelineElements disponíveis:**
```
TakeOffAction         — Descolagem
LandAction            — Aterragem
GoHomeAction          — RTH
GoToAction            — Vai para coordenada (lat, lon, alt)
HotpointAction        — Orbita um ponto
GimbalAttitudeAction  — Controla gimbal
ShootPhotoAction      — Tira foto
RecordVideoAction     — Grava vídeo
AircraftYawAction     — Roda o drone
TimelineMission       — Missão como elemento de timeline
```

---

### 5.2 WaypointMissionOperator [P]

**Package:** `dji.sdk.mission.waypoint`  
**Descrição:** Operador para missões de waypoints (v1) — o drone segue uma rota de waypoints pré-definidos.

| Método | Assinatura | → Usado por |
|--------|-----------|--|
| `loadMission` | `DJIError loadMission(WaypointMission mission)` | — (projecto implementa missão custom em FlightManager.java) |
| `uploadMission` | `void uploadMission(CompletionCallback cb)` | — |
| `downloadMission` | `void downloadMission(CompletionCallback cb)` | — |
| `startMission` | `void startMission(CompletionCallback cb)` | DuvopsView.java:173; Apêndice C |
| `stopMission` | `void stopMission(CompletionCallback cb)` | — |
| `pauseMission` | `void pauseMission(CompletionCallback cb)` | — |
| `resumeMission` | `void resumeMission(CompletionCallback cb)` | — |
| `getCurrentState` | `WaypointMissionState getCurrentState()` | — |
| `getLoadedMission` | `WaypointMission getLoadedMission()` | — |
| `addListener` | `void addListener(WaypointMissionOperatorListener l)` | — |
| `removeListener` | `void removeListener(WaypointMissionOperatorListener l)` | — |

**WaypointMission.Builder:**
```java
WaypointMission.Builder()
    .addWaypoint(Waypoint wp)
    .maxFlightSpeed(float mps)           // 2–15 m/s
    .autoFlightSpeed(float mps)          // 2–15 m/s
    .finishedAction(WaypointMissionFinishedAction action)
    // NO_ACTION, GO_HOME, AUTO_LAND, GO_FIRST_WAYPOINT, CONTINUE_UNTIL_END
    .headingMode(WaypointMissionHeadingMode mode)
    // AUTO, USING_INITIAL_DIRECTION, CONTROL_BY_REMOTE_CONTROLLER,
    // USING_WAYPOINT_HEADING, TOWARD_POINT_OF_INTEREST
    .flightPathMode(WaypointMissionFlightPathMode mode)  // NORMAL, CURVED
    .rotateGimbalPitch(boolean)
    .exitMissionOnRCSignalLostEnabled(boolean)
    .pointOfInterest(LocationCoordinate2D)
    .build()
```

**Waypoint:**
```java
Waypoint(double lat, double lon, float altitude)
wp.speed = float                      // 0 = usar autoFlightSpeed
wp.heading = int                      // 0–360 graus
wp.gimbalPitch = float                // -90 a 0
wp.cornerRadiusInMeters = float       // para modo CURVED
wp.turnMode = WaypointTurnMode        // CLOCKWISE, COUNTER_CLOCKWISE
wp.addAction(WaypointAction action)   // STAY, START_TAKE_PHOTO, etc.
```

---

### 5.3 WaypointV2MissionOperator [G]

**Package:** `dji.sdk.mission.waypointv2`  
**Descrição:** Versão 2 da missão de waypoints — maior controlo sobre trajectórias curvas, acções complexas nos waypoints, triggers.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `loadMission` | `DJIError loadMission(WaypointV2Mission mission)` | Carrega missão |
| `uploadMission` | `void uploadMission(CompletionCallback cb)` | Upload para drone |
| `uploadActions` | `void uploadActions(List<WaypointV2Action> actions, CompletionCallback cb)` | Upload de acções |
| `downloadMission` | `void downloadMission(CompletionCallback cb)` | Download |
| `startMission` | `void startMission(CompletionCallback cb)` | Inicia |
| `stopMission` | `void stopMission(CompletionCallback cb)` | Para |
| `pauseMission` | `void pauseMission(CompletionCallback cb)` | Pausa |
| `resumeMission` | `void resumeMission(CompletionCallback cb)` | Resume |
| `interruptDecision` | `void interruptDecision(WaypointV2MissionInterruptDecision d, CompletionCallback cb)` | Decisão pós-interrupção |
| `addMissionOperatorListener` | `void addMissionOperatorListener(WaypointV2MissionOperatorListener l)` | Listener |
| `addActionListener` | `void addActionListener(WaypointV2ActionListener l)` | Listener de acções |

**WaypointV2Mission.Builder:**
```java
WaypointV2Mission.Builder()
    .waypointList(List<WaypointV2>)
    .maxFlightSpeed(float)
    .autoFlightSpeed(float)
    .finishedAction(WaypointV2MissionFinishedAction)  // EXIT, GO_HOME, AUTO_LAND, NO_ACTION
    .headingMode(WaypointV2MissionHeadingMode)
    .flightPathMode(WaypointV2MissionFlightPathMode)  // NORMAL, CURVED
    .gotoFirstWaypointMode(WaypointV2MissionGotoFirstWaypointMode)
    .exitMissionOnRCSignalLostEnabled(boolean)
    .build()
```

**WaypointV2 (Builder):**
```java
WaypointV2.Builder()
    .coordinate(LocationCoordinate3D)
    .altitude(float)
    .heading(float)
    .turnMode(WaypointV2TurnMode)
    .gimbalPitchRotationEnabled(boolean)
    .gimbalPitch(float)
    .flightPathMode(WaypointV2FlightPathMode)  // CURVE_AND_STOP, CURVE_AND_PASS
    .dampingDistance(float)                    // para modo curvo
    .build()
```

**WaypointV2Action (Builder):**
```java
WaypointV2Action.Builder()
    .actionID(int)
    .trigger(WaypointTrigger trigger)
    .actuator(WaypointActuator actuator)
    .build()
```

**WaypointTrigger types:**
- `ReachPointTrigger` — Ao chegar ao waypoint N
- `IntervalTrigger` — A cada N metros ou N segundos
- `TrajectoryTrigger` — Durante trajectória entre waypoints
- `AssociateTrigger` — Associado a outra acção

**WaypointActuator types:**
- `CameraActuator` — Tirar foto, gravar vídeo, zoom, foco
- `GimbalActuator` — Rotação do gimbal
- `AircraftControlActuator` — Controlo de yaw, parar/continuar
- `LidarActuator` — Iniciar/parar gravação point cloud

---

### 5.4 FollowMeMissionOperator [G]

**Package:** `dji.sdk.mission.followme`  
**Descrição:** Faz o drone seguir um alvo em movimento (posição GPS do dispositivo móvel).

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `startMission` | `void startMission(FollowMeMission mission, CompletionCallback cb)` | Inicia |
| `stopMission` | `void stopMission(CompletionCallback cb)` | Para |
| `updateFollowingTarget` | `void updateFollowingTarget(LocationCoordinate2D loc, CompletionCallback cb)` | Actualiza posição do alvo |
| `addListener` | `void addListener(FollowMeMissionOperatorListener l)` | Listener |

**FollowMeMission:**
```java
FollowMeMission mission = new FollowMeMission();
mission.heading = FollowMeHeading.TOWARD_FOLLOW_POSITION; // ou CONTROLLED_BY_REMOTE_CONTROLLER
mission.altitude = float;        // altitude absoluta (m)
```

---

### 5.5 HotpointMissionOperator [G]

**Package:** `dji.sdk.mission.hotpoint`  
**Descrição:** Orbita o drone em torno de um ponto de interesse.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `startMission` | `void startMission(HotpointMission mission, CompletionCallback cb)` | Inicia |
| `stopMission` | `void stopMission(CompletionCallback cb)` | Para |
| `pauseMission` | `void pauseMission(CompletionCallback cb)` | Pausa |
| `resumeMission` | `void resumeMission(CompletionCallback cb)` | Resume |
| `setAngularVelocity` | `void setAngularVelocity(float degsPerSec, CompletionCallback cb)` | Velocidade angular |
| `resetHeading` | `void resetHeading(CompletionCallback cb)` | Reseta orientação |
| `addListener` | `void addListener(HotpointMissionOperatorListener l)` | Listener |

**HotpointMission:**
```java
HotpointMission mission = new HotpointMission();
mission.hotpoint = LocationCoordinate2D;   // lat, lon do centro
mission.altitude = float;                  // altitude (m)
mission.radius = double;                   // raio em metros (5–500)
mission.angularVelocity = float;           // graus/segundo
mission.isClockwise = boolean;
mission.startPoint = HotpointStartPoint;   // NEAREST, NORTH, SOUTH, EAST, WEST, CURRENT
mission.heading = HotpointHeading;         // TOWARD_HOT_POINT, ALONG_CIRCLE_LOOKING_RIGHT, etc.
```

---

### 5.6 IntelligentHotpointMissionOperator [G]

**Package:** `dji.sdk.mission.intelligenthotpoint`  
**Descrição:** Versão inteligente do Hotpoint — calcula automaticamente o centro a partir de detecção visual.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `startMission` | `void startMission(IntelligentHotpointMission m, RectF target, CompletionCallback cb)` | Inicia com alvo visual |
| `stopMission` | `void stopMission(CompletionCallback cb)` | Para |
| `pauseMission` | `void pauseMission(CompletionCallback cb)` | Pausa |
| `resumeMission` | `void resumeMission(CompletionCallback cb)` | Resume |
| `setRadius` | `void setRadius(float meters, CompletionCallback cb)` | Define raio |
| `addListener` | `void addListener(IntelligentHotpointMissionOperatorListener l)` | Listener |

---

### 5.7 TapFlyMissionOperator [G]

**Package:** `dji.sdk.mission.tapfly`  
**Descrição:** Drone voa automaticamente em direcção a um ponto tocado no ecrã.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `startMission` | `void startMission(TapFlyMission mission, CompletionCallback cb)` | Inicia |
| `stopMission` | `void stopMission(CompletionCallback cb)` | Para |
| `setBypassDirection` | `void setBypassDirection(BypassDirection dir, CompletionCallback cb)` | Direcção de desvio de obstáculos |
| `addListener` | `void addListener(TapFlyMissionOperatorListener l)` | Listener |

**TapFlyMission:**
```java
TapFlyMission mission = new TapFlyMission();
mission.target = PointF;                    // ponto no ecrã (0.0–1.0)
mission.tapFlyMode = TapFlyMode.FORWARD;    // FORWARD, BACKWARD, FREE
mission.isHorizontalObstacleAvoidanceEnabled = boolean;
mission.autoFlightSpeed = float;
```

---

### 5.8 ActiveTrackOperator [G]

**Package:** `dji.sdk.mission.activetrack`  
**Descrição:** Seguimento activo de sujeito — o drone segue e mantém o alvo no frame.

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `startAutoSensingMission` | `void startAutoSensingMission(ActiveTrackMission m, CompletionCallback cb)` | Inicia com detecção automática |
| `startAutoSensingForQuickShotMission` | `void startAutoSensingForQuickShotMission(ActiveTrackMission m, CompletionCallback cb)` | Para QuickShot |
| `stopMission` | `void stopMission(CompletionCallback cb)` | Para |
| `acceptConfirmation` | `void acceptConfirmation(CompletionCallback cb)` | Confirma alvo para tracking |
| `rejectConfirmation` | `void rejectConfirmation(CompletionCallback cb)` | Rejeita alvo |
| `setGestureModeEnabled` | `void setGestureModeEnabled(boolean en, CompletionCallback cb)` | Modo gesto |
| `setRetreatEnabled` | `void setRetreatEnabled(boolean en, CompletionCallback cb)` | Recuo automático |
| `addListener` | `void addListener(ActiveTrackMissionOperatorListener l)` | Listener |

**ActiveTrackMission:**
```java
ActiveTrackMission mission = new ActiveTrackMission(RectF targetRect, ActiveTrackMode mode);
// mode: TRACE, SPOTLIGHT, SPOTLIGHT_FIXED_RADIUS, QUICK_SHOT
```

---

### 5.9 PanoramaMissionOperator [G]

**Package:** `dji.sdk.mission.panorama`  
**Descrição:** Captura automática de panoramas (360°, esfera, cilindro, timelapse).

| Método | Assinatura | Descrição |
|--------|-----------|-----------|
| `startMission` | `void startMission(PanoramaMission m, CompletionCallback cb)` | Inicia |
| `stopMission` | `void stopMission(CompletionCallback cb)` | Para |
| `addListener` | `void addListener(PanoramaMissionOperatorListener l)` | Listener |

**PanoramaMission:**
```java
PanoramaMission mission = new PanoramaMission();
mission.mode = PanoramaMode.FULL_SPHERE;  // FULL_SPHERE, 180, SUPER_RESOLUTION, VERTICAL
```

---

## 6. Misc Classes

### 6.1 CommonCallbacks [P]

**Package:** `dji.common.util`  
**Descrição:** Interfaces genéricas de callback usadas em toda a SDK.

```java
// Operação sem retorno de dados
interface CompletionCallback {
    void onResult(DJIError error);  // null = sucesso
}

// Operação com retorno de um valor
interface CompletionCallbackWith<T> {
    void onSuccess(T value);
    void onFailure(DJIError error);
}

// Operação com retorno de dois valores
interface CompletionCallbackWithTwoParam<T, Y> {
    void onSuccess(T param1, Y param2);
    void onFailure(DJIError error);
}
```

---

### 6.2 DJIError [P]

**Package:** `dji.common.error`  
**Descrição:** Classe base para todos os erros do SDK.

```java
String getDescription()     // Descrição legível do erro
String toString()
```

**Subclasses de erro por domínio:**

| Classe | Domínio |
|--------|---------|
| `DJISDKError` | Erros gerais do SDK (registo, ligação, timeout) |
| `DJISDKCacheError` | Cache do SDK |
| `DJICameraError` | Câmara |
| `DJIFlightControllerError` | Controlador de voo |
| `DJIGimbalError` | Gimbal |
| `DJIBatteryError` | Bateria |
| `DJIAirLinkError` | AirLink |
| `DJIRemoteControllerError` | Comando |
| `DJIMissionError` | Missões |
| `DJIFlySafeError` | GEO / zonas de voo |
| `DJIFlightHubError` | FlightHub |
| `DJIPayloadError` | Payload |
| `DJIRTKNetworkServiceError` | RTK rede |
| `DJIAccessoryAggregationError` | Acessórios |
| `DJIAccessLockerError` | Access locker |
| `DJIUTMISSError` | UTMISS |
| `DJILDMError` | LDM |
| `DJIUpgradeError` | Upgrade firmware |
| `DJIWaypointV2Error` | Waypoint V2 |
| `PipelineError` | Pipeline |
| `DataProtectionError` | Protecção de dados |

---

### 6.3 DJIDiagnostics [G]

**Package:** `dji.sdk.diagnostics`  
**Descrição:** Diagnósticos em tempo real — problemas activos no produto e seus componentes.

```java
// Obtido via BaseProduct.getDiagnostics() ou KeyManager
int getCode()
String getReason()
String getSolution()
ComponentType getComponentType()
int getComponentIndex()
String getSubsystemType()
```

**Via KeyManager (DiagnosticsKey):**
```java
DiagnosticsKey key = DiagnosticsKey.create(DiagnosticsKey.DEVICE_HEALTH_INFORMATION);
keyManager.addListener(key, (oldValue, newValue) -> {
    // newValue = List<DeviceHealthInformation>
});
```

**DeviceHealthInformation:**
```java
int getWarningLevel()        // 0 = normal, 1 = notice, 2 = caution, 3 = warning
String getInformationCode()
String getSensorSN()
ComponentType getComponentType()
```

---

### 6.4 DJICodecManager [G]

**Package:** `dji.sdk.codec`  
**Descrição:** Descodifica o stream de vídeo H.264/H.265 do drone para exibição ou processamento de frames YUV/RGBA.

```java
// Construtor
DJICodecManager(Context ctx, Surface surface, int width, int height)
DJICodecManager(Context ctx, Surface surface, int width, int height, PhysicalSource source)

// Métodos principais
void cleanSurface()
void destroyCodec()
void enabledYuvData(boolean enabled)           // Activa callback YUV
void setYuvDataCallback(YuvDataCallback cb)    // Recebe frames YUV
void setEnableSoftDecode(boolean enable)       // Soft decode (CPU) vs hardware

// YuvDataCallback
interface YuvDataCallback {
    void onYuvDataReceived(MediaFormat format, ByteBuffer yuvFrame, int dataSize, int width, int height);
}
```

**Nota:** Para usar com câmara, subscrever `VideoFeeder.getPrimaryVideoFeed().addVideoDataListener()` e passar os bytes ao `DJICodecManager.sendDataToDecoder()`.

---

### 6.5 DJIParamCapability [P]

**Package:** `dji.common.camera` (e outros)  
**Descrição:** Descreve se um parâmetro é suportado e os seus limites.

```java
boolean isSupported()          // Parâmetro suportado neste hardware?

// DJIParamMinMaxCapability (herda de DJIParamCapability)
Number getMin()
Number getMax()
```

**Uso típico (via Camera.Capabilities):**
```java
camera.getCapabilities(new CompletionCallbackWith<Capabilities>() {
    public void onSuccess(Capabilities cap) {
        DJIParamCapability isoCapability = cap.ISORange();
        if (isoCapability.isSupported()) {
            // parâmetro disponível neste modelo
        }
    }
});
```

---

## Apêndice A — Estrutura de Acesso Típica

```java
// 1. Obter o SDK Manager
DJISDKManager sdk = DJISDKManager.getInstance();

// 2. Registar a app
sdk.registerApp(context, new SDKManagerCallback() {
    @Override
    public void onRegister(DJIError error) {
        if (error == null) sdk.startConnectionToProduct();
    }
    @Override
    public void onProductConnect(BaseProduct product) {
        if (product instanceof Aircraft) {
            Aircraft aircraft = (Aircraft) product;
            FlightController fc = aircraft.getFlightController();
            Camera cam = aircraft.getCameras().get(0);
            Gimbal gimbal = aircraft.getGimbals().get(0);
        }
    }
    // ... outros callbacks
});
```

---

## Apêndice B — Virtual Sticks (controlo programático)

```java
FlightController fc = aircraft.getFlightController();

// Configuração
fc.setVirtualStickModeEnabled(true, null);
fc.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
fc.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
fc.setVerticalControlMode(VerticalControlMode.VELOCITY);
fc.setRollPitchCoordinateSystem(FlightCoordinateSystem.GROUND);

// Envio de comandos (deve ser enviado a 5–25 Hz)
FlightControlData data = new FlightControlData(
    pitch,          // m/s (frente/trás no modo VELOCITY)
    roll,           // m/s (esquerda/direita)
    yaw,            // graus/segundo
    throttle        // m/s (cima/baixo)
);
fc.sendVirtualStickFlightControlData(data, null);
```

---

## Apêndice C — Waypoint Mission (exemplo completo)

```java
WaypointMissionOperator op = DJISDKManager.getInstance()
    .getMissionControl().getWaypointMissionOperator();

// Criar waypoints
Waypoint wp1 = new Waypoint(38.7169, -9.1399, 30f);
wp1.addAction(new WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0));
Waypoint wp2 = new Waypoint(38.7175, -9.1405, 40f);
wp2.gimbalPitch = -45f;

WaypointMission mission = new WaypointMission.Builder()
    .addWaypoint(wp1)
    .addWaypoint(wp2)
    .autoFlightSpeed(5f)
    .maxFlightSpeed(10f)
    .finishedAction(WaypointMissionFinishedAction.GO_HOME)
    .headingMode(WaypointMissionHeadingMode.AUTO)
    .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
    .build();

DJIError error = op.loadMission(mission);
if (error == null) {
    op.uploadMission(e -> {
        if (e == null) op.startMission(null);
    });
}
```

---

## Apêndice D — Hierarquia de Classes

```
BaseProduct
├── Aircraft (DJIAircraft)
│   ├── FlightController
│   │   ├── Compass
│   │   ├── RTK
│   │   ├── Simulator
│   │   ├── LandingGear
│   │   └── FlightAssistant
│   ├── Camera (lista)
│   ├── Gimbal (lista)
│   ├── Battery (lista)
│   ├── RemoteController
│   ├── AirLink
│   │   ├── LightbridgeLink
│   │   ├── OcuSyncLink
│   │   └── WiFiLink
│   ├── Payload (lista)
│   ├── Lidar
│   ├── Radar
│   └── AccessoryAggregation
│       ├── Spotlight
│       ├── Speaker
│       └── Beacon
└── HandHeld (DJIHandheld)
    ├── Camera
    ├── Gimbal
    ├── HandheldController
    ├── MobileRemoteController
    └── AirLink (WiFiLink)

DJISDKManager (singleton)
├── KeyManager
├── FlyZoneManager
├── MissionControl
│   ├── WaypointMissionOperator
│   ├── WaypointV2MissionOperator
│   ├── HotpointMissionOperator
│   ├── IntelligentHotpointMissionOperator
│   ├── FollowMeMissionOperator
│   ├── TapFlyMissionOperator
│   ├── ActiveTrackOperator
│   └── PanoramaMissionOperator
├── FlightHubManager
├── LiveStreamManager
├── LDMManager
├── UTMISSManager
├── AppActivationManager
├── UserAccountManager
├── RTKNetworkServiceProvider
├── UpgradeManager
└── UASRemoteIDManager
```

---

*Documento gerado a partir da DJI Mobile SDK Android API Reference v4.*  
*Fonte oficial: https://developer.dji.com/api-reference/android-api/*
