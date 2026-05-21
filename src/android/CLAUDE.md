# CLAUDE.md

> Guia de convenções, decisões arquiteturis, e estrutura do projeto dboidsView/DuvopsView.

## Build Commands
- `./gradlew assembleDebug` - Build debug APK
- `./gradlew assembleRelease` - Build release APK
- `./gradlew connectedAndroidTest` - Run instrumentation tests

## Project Overview

Android application for DJI Mavic 2 Enterprise Advanced (M2EA) drones, installed on the remote controller (RC) to enable:
- **Environmental monitoring**: Aerial surveys, ecological assessments, and conservation operations
- **Incident control**: Emergency response, disaster assessment, and search/rescue missions
- **Automated operations**: Waypoint-based mission execution for consistent data collection

## Core Architecture

### DJI SDK Integration (v4.16.4)
- **M2EA Specifics**:
  - Hybrid Zoom implementation (1.0x-32.0x via focal length control)
  - Telemetry via `TelemetryManager.java` (250ms interval)
  - Flight management through `FlightManager.java`
- **Camera Control**:
  - **RGB Mode**: Uses WIDE video stream
  - **IR Mode**: Uses INFRARED_THERMAL stream
  - **SPLIT Mode**: PIP with SIDE_BY_SIDE positioning
  - Zoom conversion: `factor × 240` (24mm = 1.0x)
- **Mission Execution**:
  - Waypoint navigation with PID controllers
    - Parameters: `kp=0.2, ki=0.0001, kd=0.2`
    - Safety limits: max pitch ±15°
  - Takeoff/landing as mission start/end actions
  - Synchronized mission states (RUNNING/PAUSED/STOPPED)
- **ABI Configuration**:
  - `armeabi-v7a`/`arm64-v8a` only (critical for DJI native libraries)
  - Anti-distortion library explicitly excluded

### WebSocket Protocol
- **Server URL**:
  - Default: `www.ciic.pt`
  - Persisted via `SharedPreferences` (`duvops_prefs` → `server_url`) in `DuvopsView`
  - URL format: `ws://<server>` (auto-prefixed with `ws://` if missing)
  - Required header: `dboidsID` (drone serial number)
  - Connection states: CONNECTED/CLOSING/CLOSED/ERROR (logged in `SocketListener`)
- **Command Logging** (`NetworkManager.CommandLogListener`):
  - Notified on every incoming command for debug
  - `DuvopsView` displays it in `messageField` as `"CMD: <command_name>"` in blue
- **Telemetry Format** (250ms interval):
  ```json
  {
    "lat": 37.77,
    "lng": -122.42,
    "alt": 15.2,
    "velX": 1.2,
    "velY": -0.5,
    "velZ": 0.3,
    "isFlying": true,
    "satCount": 18,
    "rft": 1200.0,
    "isGoingHome": false,
    "areMotorsOn": true,
    "isHomeLocationSet": true,
    "homeLocation": { "lat": 37.77, "lng": -122.42 },
    "hdg": 180.5,
    "isTraveling": false,
    "model": "Mavic 2 Enterprise Advanced",
    "bat": { "lvl": 85, "remaining": 3200, "temperature": 38.5, "charging": false, "connectionState": "CONNECTED", "voltage": 16800, "current": 250 },
    "gimbal": { "pitch": -45.0, "roll": 0.0, "yaw": 12.3 },
    "rcBat": { "lvl": 90, "remainingMah": 3200, "charging": false },
    "zoom": 2.5,
    "cameraMode": "RGB"
  }
  ```
  *20+ fields with `bat` (drone) and `rcBat` (RC) sub-objects. Full reference: `docs/telemetry-reference.md`*
- **Full Command Reference**:
  - `{"command": "takeoff"}`
  - `{"command": "land"}`
  - `{"command": "setZoom", "factor": 2.5}` (1.0-32.0x)
  - `{"command": "setCameraMode", "mode": "IR"}` (RGB/IR/SPLIT)
  - `{"command": "startMission", "startAction": "takeoff", "endAction": "land", "path": [{"lat": 37.77, "lng": -122.42}], "altitude": 50, "repeat": 1}`
  - `{"command": "stopMission"}`
  - `{"command": "pauseMission"}`
  - `{"command": "virtualSticksInput", "roll": 0.5, "pitch": 0.3, "yaw": 0, "throttle": 0.2}`
  - `{"command": "gpsInput", "lat": 37.77, "lng": -122.42}`
  - `{"command": "perform360"}`
  - `{"command": "startRTMP"}`
  - `{"command": "identify", "state": true}`
  - `{"command": "gimbalAngle", "pitch": -45, "yaw": 0, "mode": "absolute"}` (pitch: -90 to +30, yaw: -75 to +75)
  - `{"command": "gimbalReset"}` (return to neutral)
  - `{"command": "motors", "state": true/false}` (motor on/off)

## UI Architecture

### Main Interface (New)
- **Class**: `DuvopsView.java` — **active target for bug fixes**
- **Key Components**:
  - WebSocket connection manager (`connectws` button)
  - RTMP streaming controller
  - Simulator launcher (fixed coordinates: 39.933219, -8.892509)
  - Status display with color-coded feedback (red/green)
- **Component Wiring**:
  ```
  NetworkManager → FlightManager → TelemetryManager/CameraManager
  ```
- **Video Feed**:
  - Primary feed via `VideoFeedView`
  - RTMP configuration: 1080p, 1.5Mbps bitrate

### Main Interface (Legacy Reference)
- **Class**: `DboidsView.java` — **READ ONLY, never edit**
- Monolithic legacy implementation (single file, ~1200 lines)
- All logic (WebSocket, mission, virtual sticks, RTMP, telemetry) inline
- Used as implementation reference for features not yet migrated to DuvopsView

### Mission Control
- **Layout**: `view_mission.xml`
- **Key Elements**:
  - Mission progress bar (`pb_mission`)
  - Action buttons: Start/Pause/Resume/Stop
  - Waypoint management (Load/Upload/Download)
  - Simulator control
- **Safety Features**:
  - Visual status indicators
  - Confirmation steps for critical operations

## Utility Components

### Critical SDK Utilities
- **Module Verification** (`ModuleVerificationUtil.java`):
  - Ensures safe access to DJI components with model-specific checks:
    - `isMavic2Product()` (checks Mavic 2 Pro/Zoom compatibility)
    - `getFlightController()` (used by `FlightManager`)
    - Model-specific handling for M2EA Enterprise operations
  - **Critical Safety Check**: All flight operations must verify controller availability

- **UI Feedback** (`ToastUtils.java`):
  - Thread-safe message handling via `Handler`
  - Key methods:
    - `setResultToToast()` - Error display (used in connection failures)
    - `setResultToText()` - Status updates (e.g., connection status)
  - **Safety Requirement**: All critical errors must use this for user notification

- **Video Management** (`VideoFeedView.java`):
  - Handles primary video feed rendering with:
    - Automatic cover view when feed stops (>500ms timeout)
    - Mavic 2-specific video stream handling
    - Key frame reset via `changeSourceResetKeyFrame()`
  - **Hardware Note**: Video feed requires physical device testing (simulator limitations)

## Critical Development Notes

### Hardware Requirements
- **Physical device testing mandatory** for:
  - Camera controls (zoom/mode transitions)
  - Flight logic and safety features
  - Telemetry validation
- SDK simulation has significant limitations

### Safety Constraints
- All flight commands require safety validation (e.g., `isFlying()` check)
- Implement retry logic for critical operations
- Validate all telemetry values for NaN/invalid states
- Mission execution must include failsafe waypoints
- **Connection Handling**:
  - Monitor WebSocket states (`onOpen`/`onFailure` in `SocketListener`)
  - Handle network errors via `onConnectionStatusChange`
- **UI Safety**: Critical actions require confirmation in mission interface

## System Architecture

- **Event Bus**: Otto (`App.java`) for inter-component communication
- **MultiDex**: Enabled for broader device support (minSdkVersion 23+)
- **Flight Logic**: Thread-safe mission management with synchronized states
- **Navigation**: PID controllers for smooth GPS waypoint transitions
- **Camera Management**: Dedicated `CameraManager` class handling stream sources
- **Network Layer**: `NetworkManager` implements full command/response cycle with error handling

---

## Project Conventions

### Nomenclatura (snake_case / camelCase)

| Contexto | Convenção | Exemplo |
|----------|-----------|---------|
| Java method names | camelCase | `setVirtualStickModeEnabled` |
| Java field names | camelCase | `primaryVideoFeedView` |
| Java constant names | SCREAMING_SNAKE_CASE | `MAX_FLIGHT_SPEED` |
| Java class names | PascalCase | `DuvopsView` |
| Layout XML IDs | snake_case | `pb_mission` |
| Layout XML filenames | snake_case | `view_mission.xml` |
| WebSocket command names | camelCase | `setZoom`, `startMission` |
| JSON telemetry fields | camelCase | `batLvl`, `cameraMode` |
| Log tag prefix | `DJI/DEBUG` | `Log.d("DEBUG", ...)` |

### Code Style Rules

- Todos os ficheiros Java têm cabeçalho com propósito descrito
- Cada método tem docblock: `/**` com `@param`, `@return`, `@throws` (quando aplicável)
- Comentários inline apenas para **porquê** (não **o quê**)
- Erros críticos exibidos via `ToastUtils.setResultToToast()` (sempre)
- Telemetria validada para NaN antes de uso: `Double.isNaN(value)`

---

## Managers Reference

### DroneCommandListener (interface)
| Método | Descrição |
|--------|------|
| `onConnectionStatusChange(boolean, String)` | WebSocket connection status |
| `onTakeOff()` | Start takeoff |
| `onLand()` | Start landing |
| `onMotors(boolean)` | Turn motors on/off |
| `onGoHome()` | Return to home |
| `onMoveTo(double, double)` | GPS navigation to lat/lng |
| `onVirtualStickInput(float, float, float, float)` | Roll, pitch, yaw, throttle |
| `onVirtualStickState(boolean)` | Enable/disable virtual sticks |
| `onPerform360()` | 360° yaw rotation |
| `onIdentify(boolean)` | Toggle LEDs/beacons |
| `onStartMission(...)` | Start waypoint mission |
| `onStopMission()` | Stop mission |
| `onPauseMission()` | Pause/resume mission |
| `onStartRTMP()` | Start RTMP stream |
| `onSetZoom(float)` | Set zoom factor |
| `onSetCameraMode(String)` | Set camera mode (RGB/IR/SPLIT) |
| `onGimbalAngle(float, float, String)` | Set gimbal pitch/yaw (absolute/relative) |
| `onGimbalReset()` | Reset gimbal to neutral |

### NetworkManager
| Método | Descrição |
|--------|------|
| `connect(String url, String serialNumber)` | Open WebSocket connection |
| `disconnect()` | Close WebSocket |
| `sendStatus(String jsonStatus)` | Send telemetry JSON |
| `handleRawMessage(String text)` | Parse incoming JSON → command dispatch |
| `notifyConnectionChange(boolean, String)` | Notify listener of connection state |
| `setCommandLogListener(CommandLogListener)` | Set debug callback for received commands |
| `CommandLogListener.onCommandReceived(String, String)` | Callback: command name + raw JSON |

### FlightManager
| Método | Descrição |
|--------|------|
| `onTakeOff()` | `flightController.startTakeoff()` |
| `onLand()` | `flightController.startLanding()` |
| `onMotors(boolean)` | `flightController.turnOnMotors()` / `turnOffMotors()` |
| `onGoHome()` | `flightController.startGoHome()` |
| `onIdentify(boolean)` | Set LED/beacon settings |
| `onVirtualStickState(boolean)` | Enable/disable virtual stick mode |
| `onVirtualStickInput(float, float, float, float)` | Update roll/pitch/yaw/throttle values |
| `onMoveTo(double, double)` | GPS navigation via PID + virtual sticks |
| `onPerform360()` | Yaw 360° rotation |
| `onStartMission(String, String, int, float, String)` | Waypoint mission loop |
| `onStopMission()` | Stop mission, reset states |
| `onPauseMission()` | Pause/resume mission |
| `onSetZoom(float)` | Delegates to CameraManager |
| `onSetCameraMode(String)` | Delegates to CameraManager |
| `onGimbalAngle(float, float, String)` | `gimbal.rotate(Rotation, callback)` |
| `onGimbalReset()` | `gimbal.reset(callback)` |

### CameraManager
| Método | Descrição |
|--------|------|
| `setZoom(float factor)` | `focalLength = factor × 240`, via KeyManager |
| `setCameraMode(String mode)` | Switch camera stream: RGB/WIDE, IR/INFRARED_THERMAL, SPLIT/PIP |

### TelemetryManager
| Método | Descrição |
|--------|------|
| `startTelemetry()` | Start 250ms telemetry timer |
| `stopTelemetry()` | Stop telemetry timer |
| `setFlightController(FlightController)` | Update FC reference |
| `setTraveling(boolean)` | Update isTraveling flag |
| `setModelName(String)` | Update drone model name |

---

## Architecture Decision Log (ADRs)

### ADR-001: WebSocket vs MQTT para comunicação drone-server
- **Decisão:** Usar WebSocket (okhttp3) em vez de MQTT
- **Contexto:** Necessidade de comunicação bidirecional em tempo real com JSON payloads
- **Racional:**
  - WebSocket permite JSON nativo sem serialização adicional
  - okHttp já é dependência do projeto (via DJI SDK)
  - Simples de implementar e testar
- **Consequências:**
  - Sem reconnection automática nativa (implementado manualmente)
  - Sem QoS guarantees (aceitável para telemetria)
  - Mensagens de 200ms com retry logic implementada

### ADR-002: Otto Event Bus vs LiveData/Compose
- **Decisão:** Continuar com Otto (square/otto) em vez de LiveData/ViewDataBinding
- **Contexto:** Projeto baseado em Views com comunicação inter-componente
- **Racional:**
  - Otto é leve e simples (single `EventBus.java` em `App.java`)
  - Código existente já usa Otto extensivamente
  - migração para Compose/LiveData seria breaking change
- **Consequências:**
  - Thread-safety manual necessária (`EventBus().post()` em background thread)
  - Sem lifecycle awareness (cuidado com memory leaks)

### ADR-003: Zoom conversion factor = x240
- **Decisão:** Fator de conversão `zoomFactor × 240 = focalLength_mm`
- **Contexto:** DJI SDK usa focal length em mm para zoom óptico
- **Racional:**
  - 24mm (wide) = 1.0x base
  - Fator linear confirmado experimentalmente
  - `setOpticalZoomFocalLength(factor × 240, callback)`
- **Consequências:**
  - Range: 1.0x (24mm) a 32.0x (7680mm — digital overlay)

### ADR-004: Virtual Sticks vs Waypoint V2 para navegação
- **Decisão:** Usar Virtual Sticks + PID control em vez de WaypointMissionOperator
- **Contexto:** Necessidade de controle fino de waypoints com adaptação em tempo real
- **Racional:**
  - Waypoint V2 é upload-based (sem feedback em tempo real durante execução)
  - Virtual Sticks permite PID loop a 5Hz com correção contínua
  - Mais flexível para missions dinâmicos
- **Consequências:**
  - Requires manual distance/direction calculation (Haversine)
  - Precisa de `setVirtualStickAdvancedModeEnabled(true)` para Attitude mode
  - Mais complexidade mas mais controle

### ADR-005: Hybrid Zoom (optical + digital)
- **Decisão:** Usar `setOpticalZoomFocalLength` em vez de `setDigitalZoomFactor`
- **Contexto:** M2EA tem zoom híbrido (8x optical + 4x digital = 32x total)
- **Racional:**
  - `focalLength` controla optical zoom
  - Digital zoom é overlay pós-processamento (perda de qualidade)
  - Factor max focal = 7680mm (32x)

### ADR-006: Camera stream selection by mode
- **Decisão:** Mapear cameraMode → stream para video feed
- **Racional:** M2EA tem múltiplas câmaras (RGB + FLIR thermal)
- **Mapeamento:**
  | cameraMode | stream |
  |------------|--------|
  | RGB | WIDE (RGB primary) |
  | IR | INFRARED_THERMAL |
  | SPLIT | PIP (WIDE + INFRARED_THERMAL) |

### ADR-007: DuvopsView vs DboidsView
- **Decisão:** DuvopsView (novo) é alvo de correção de bugs; DboidsView (legado) é referência apenas
- **Contexto:** DboidsView era código monolítico funcional mas difícil de manter
- **Racional:**
  - Separar concerns: NetworkManager, FlightManager, CameraManager, TelemetryManager
  - DuvopsView é apenas UI — logic em managers dedicados
  - DboidsView tem toda a lógica inline (anti-pattern mas funcional)

### ADR-008: Physical device testing mandatory
- **Decisão:** Nunca confiar em simulator para features de camera/flight
- **Contexto:** SDK simulator tem limitações significativas
- **Racional:**
  - Video feed não funciona no simulator
  - Camera commands falham silenciosamente
  - Telemetry é simulated (não reflete real hardware behavior)
- **Consequências:**
  - Todas as PRs com changes em camera/flight requerem device testing
  - Simulator apenas para UI/layout debugging

---

## Known Issues & Workarounds

| Issue | Workaround |
|-------|-------|
| Video feed dies after 500ms in simulator | Test only on physical device |
| Virtual sticks drift on long missions | PID tuning every 200ms (not faster) |
| Zoom not available in IR mode | Check camera stream before zoom command |
| WebSocket disconnects on RC sleep | Implement reconnection in SocketListener |
| Thermal camera needs mode switch | Camera mode must be set before zoom |
| Gimbal pitch out of range on some missions | Clamp pitch to -90..30 before sending |

---

## Documentation References

| Recurso | Local | Propósito |
|-----|------|------|
| DJI SDK v4 API Reference | `docs/DJIMobileSDKAndroidAPIReference.md` | Referência completa da API (local) |
| DJI SDK Online | https://developer.dji.com/api-reference/android-api/ | Referência oficial atualizada |
| `docs/DboidsView.java` | Código legado (~1200 linhas, READ ONLY) | Referência |
| `docs/mavic2_gimbal.txt` | Exemplos de rotação do gimbal (pitch/yaw) | Referência |
| `docs/telemetry-reference.md` | Todos os campos de telemetria enviados (20+ campos) | Novo |
| `docs/command-reference.md` | Todos os comandos suportados explicados | Novo |

---

## Source Code Structure & File Reference

```
app/src/main/java/com/dji/sdk/duvops/
├── app/                    # Entry point & lifecycle
│   ├── App.java            # EventBus singleton + product accessors
│   ├── MainActivity.java  # Launcher activity, USB accessory handler
│   ├── MainContent.java   # Home screen UI, SDK registration, permission flow
│   ├── LoginView.java     # DJI account login/logout custom view
│   └── HealthInformationView.java  # HMS diagnostics display
├── flight/                 # Flight & drone logic
│   ├── DuvopsView.java     # Core flight control UI (target for bug fixes)
│   ├── FlightActivity.java # Full-screen host for DuvopsView
│   ├── FlightManager.java  # takeoff, land, mission, virtual sticks
│   ├── CameraManager.java  # camera mode, zoom via KeyManager
│   ├── TelemetryManager.java  # telemetry polling/sending
│   └── PIDController.java
├── network/                # WebSocket layer
│   ├── NetworkManager.java  # WebSocket connect/disconnect/status + command dispatch
│   ├── SocketListener.java  # WebSocket event callbacks
│   └── DroneCommandListener.java  # Command interface (16 methods)
└── utils/                  # Shared utilities (DJI SDK sample code heritage)
    ├── ModuleVerificationUtil.java  # DJI component safety checks
    ├── ToastUtils.java        # Thread-safe toast messages
    ├── VideoFeedView.java     # H.264 decode + display
    ├── CallbackHandlers.java  # Common callback helpers
    ├── DialogUtils.java       # Dialog display utilities
    ├── DownloadHandler.java   # File download management
    ├── GeneralUtils.java      # General-purpose helpers
    ├── Helper.java            # HMS info + legacy helpers
    ├── DensityUtil.java       # DPI/density conversions
    ├── ViewHelper.java        # View manipulation helpers
    └── OnCompletionCallback.java  # Completion callback interface
```

### app/ — Detalhe dos ficheiros

#### App.java
- `getProductInstance()` → `BaseProduct` (via `DJISDKManager.getProduct()`)
- `isAircraftConnected()` / `isHandHeldConnected()` — product type check
- `getInstance()` — Application singleton
- `getEventBus()` — Otto `Bus` singleton
- **Relação:** ponto de entrada para o produto DJI; usado por `DuvopsView`, `MainContent`, etc.

#### MainActivity.java
- `onNewIntent()` — detecta USB accessory attach → triggers DJI SDK connection
- `onConnectivityChange(ConnectivityChangeEvent)` — EventBus subscriber
- **Relação:** Activity principal; lança `MainContent` via `FlightActivity`

#### MainContent.java
- `startSDKRegistration()` — registers app with DJI SDK (standard or LDM mode)
- `refreshSDKRelativeUI()` — updates UI based on product connection state
- `initUI()` — binds UI widgets, sets click listeners, requests permissions
- `notifyStatusChange()` — posts `ConnectivityChangeEvent` via EventBus
- **Relação:** home screen; lança `FlightActivity` → `DuvopsView`

#### LoginView.java
- `onClick(View)` — login/logout via `UserAccountManager`
- `updateLoginState(UserAccountState)` — updates button states
- **Estado:** custom view reutilizável; não usado no fluxo principal do projecto

#### HealthInformationView.java
- `onUpdate(List<DJIDiagnostics>)` — receives diagnostics updates, separates HMS
- `onAttachedToWindow()` / `onDetachedFromWindow()` — register/unregister diagnostics callback
- **Estado:** custom view para display de diagnósticos DJI; não usado no fluxo principal

#### FlightActivity.java
- `onCreate()` — sets `KEEP_SCREEN_ON`, instantiates `DuvopsView` as content view
- **Relação:** activity wrapper para `DuvopsView`; lançada por `MainContent`

### utils/ — Nota de proveniência

Ficheiros em `app/src/main/java/com/dji/sdk/duvops/utils/` são **herdados do sample code da DJI SDK**.
- **`ModuleVerificationUtil.java`** — verificação de segurança de componentes DJI (migrated to project)
- **`VideoFeedView.java`** — decode H.264 + display (adaptado para M2EA)
- **`ToastUtils.java`** — thread-safe toast messages
- **`CallbackHandlers.java`** — common callback wrappers
- **`DialogUtils.java`** — dialog display utilities
- **`DownloadHandler.java`** — file download management
- **`GeneralUtils.java`** — general-purpose helpers
- **`Helper.java`** — HMS info resolution + legacy helpers
- **`DensityUtil.java`** — DPI/density conversions
- **`ViewHelper.java`** — view manipulation helpers
- **`OnCompletionCallback.java`** — completion callback interface

> **Nota:** utils/ não é código do projecto mas biblioteca de utilitários DJI.
> Modificações devem ser cuidadosas — pode afectar múltiplos ficheiros.

## Legacy Reference

| Ficheiro | Descrição | Estado |
|------|- -----|-- ----|
| `docs/DboidsView.java` | Código legacy monolítico (~1200 linhas) — referência de implementação funcional | READ ONLY |
| `docs/mavic2_gimbal.txt` | Exemplos de controlo do gimbal (pitch/yaw) | Referência |
