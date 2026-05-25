# CLAUDE.md — Android App (`src/android/`)

> Guia de arquitectura, convenções e integração para o benchmark OneM2M.
> Esta versão da app foi adaptada do projecto de produção (dboidsView/DuvopsView)
> para uso académico — benchmarking de protocolos OneM2M com ACME CSE.

## Build Commands
- `.\gradlew.bat assembleDebug` — Build debug APK (Windows)
- `.\gradlew.bat assembleRelease` — Build release APK
- `.\gradlew.bat connectedAndroidTest` — Run instrumentation tests

---

## Project Overview

App Android para DJI Mavic 2 Enterprise Advanced (M2EA), instalada no RC (Remote Controller).
**Neste contexto**, funciona como AE (Application Entity) OneM2M que:

1. Regista-se no ACME CSE ao ligar
2. Envia telemetria do drone como `m2m:cin` (contentInstance) a cada 250 ms
3. Recebe comandos de voo via notificações OneM2M (`m2m:sgn`)
4. Suporta múltiplos protocolos de transporte via interface `ProtocolClient`

Funcionalidades de produção que não pertencem ao benchmark foram removidas:
RTMP streaming, LDM mode, bridge mode, Bluetooth connector, firmware version display.

---

## Core Architecture

### DJI SDK Integration (v4.16.4)
- **M2EA Specifics**:
  - Hybrid Zoom: `factor × 240 = focalLength_mm` (24 mm = 1.0×, 7680 mm = 32.0×)
  - Telemetria via `TelemetryManager` (timer 250 ms, KeySDK listeners)
  - Voo via `FlightManager` (virtual sticks + PID, missões waypoint)
- **Camera Control**:
  - RGB Mode → WIDE video stream
  - IR Mode → INFRARED_THERMAL stream
  - SPLIT Mode → PIP (WIDE + INFRARED_THERMAL)
- **Mission Execution** (PID + virtual sticks):
  - `kp=0.2, ki=0.0001, kd=0.2`
  - Estados sincronizados: RUNNING/PAUSED/STOPPED
  - Distância de paragem: 2 m, Haversine formula
- **ABI**: `armeabi-v7a` + `arm64-v8a` (obrigatório para .so do DJI SDK)

### Communication Layer — ProtocolClient + OneM2M

A comunicação foi refactorizada para uma interface abstracta `ProtocolClient`
que permite substituir o transporte (WebSocket → MQTT → HTTP → CoAP) sem
alterar a lógica de voo ou telemetria.

**Stack de comunicação actual:**

```
DuvopsView / TelemetryManager
    ↓ ProtocolClient interface
OneM2MSession           ← gere o protocolo OneM2M (registo AE, CIN, SUB)
    ↓ NetworkManager    ← transporte WebSocket (okhttp3) raw
        ↓ SocketListener
```

**`ProtocolClient` interface** (`network/ProtocolClient.java`):
```java
void connect(String host, int port, String aeId);
void disconnect();
void sendTelemetry(String jsonPayload);   // OneM2MSession envolve em m2m:cin
boolean isConnected();
void setCommandLogListener(CommandLogListener listener);
```

**Sequência de inicialização OneM2M** (automática após WebSocket abrir, 6 passos):
```
connect(host, 8180, serialNumber)
  → WebSocket abre (subprotocolo "oneM2M.json", header X-M2M-Origin: C+serial)
  → registerAE()              [to="id-in", ty=2, poa=["ws://host:8180"]]
  → resp 2001/4105 → createTelemetryContainer  [to="cse-in/uxv", ty=3, rn=telemetry]
  → resp 2001/4105 → createCommandsContainer   [to="cse-in/uxv", ty=3, rn=commands]
  → resp 2001/4105 → createSubscription        [to="cse-in/uxv/commands", ty=23, nu=aeOriginator]
  → resp 2001/4105 → createAckContainer        [to="cse-in/uxv", ty=3, rn=ack]
  → resp 2001/4105 → onSessionReady()
  → commandListener.onConnectionStatusChange(true, ...) → startTelemetry()
```

**Árvore de recursos após ligação** (HTTP paths usam `/cse-in/`, NÃO `/id-in/`):
```
/id-in                   ← CSE-Base (apenas para GET /id-in)
/cse-in/uxv              ← AE (HTTP path real; WS to = "cse-in/uxv")
/cse-in/uxv/telemetry    ← CNT (mni=10)  — CINs de telemetria
/cse-in/uxv/commands     ← CNT (mni=5)   — CINs de comandos
/cse-in/uxv/commands/sub-commands  ← SUB — notificação ao AE
/cse-in/uxv/ack          ← CNT (mni=200) — ACKs de comandos (Cenário 2)
```

**CSE host**: configurado no campo `hostname` da `DuvopsView`.
- Default: `192.168.1.100` (IP da máquina dev na LAN)
- Persisted em `SharedPreferences` (`duvops_prefs → server_url`)
- Aceita formato `host` ou `host:port` (port default: 8180 WS)

**Originator OneM2M**: `"C" + serialNumber` (limpo, max 32 chars)
- Ex: serial `3LKFD12ABC` → originator `C3LKFD12ABC`

**Subscrição de comandos** (validado contra ACME CSE v2025.11):
- Container alvo: `cse-in/uxv/commands` (WS `to` field)
- `nu`: **`aeOriginator`** (ex: `C3LKFD12ABC`) — NÃO o URI do recurso
- `enc.net = [3]`: notificar na criação de filho directo
- `nct`: **OMITIR** — `nct=2` + `net=[3]` é inválido em v2025.11 (rsc=4000)
- `poa`: **OBRIGATÓRIO** no registo AE — sem `poa`, o CSE descarta notificações silenciosamente

**Telemetria** (250 ms → `m2m:cin` em `cse-in/uxv/telemetry`, flat JSON — sem wrapper):
```json
{
  "op": 1, "to": "cse-in/uxv/telemetry", "fr": "CserialXYZ",
  "rqi": "rqi-42", "rvi": "3", "ty": 4,
  "pc": {
    "m2m:cin": {
      "con": "{ <telemetry JSON abaixo> }"
    }
  }
}
```
> `cnf` OMITIDO — `"application/json"` falha validação em ACME CSE v2025.11 (rsc=4000)

**Telemetry JSON** (campo `con`, 20+ campos):
```json
{
  "lat": 39.933, "lng": -8.892, "alt": 15.2,
  "velX": 1.2, "velY": -0.5, "velZ": 0.3,
  "isFlying": true, "satCount": 18, "rft": 1200.0,
  "isGoingHome": false, "areMotorsOn": true,
  "isHomeLocationSet": true, "homeLocation": {"lat": 39.933, "lng": -8.892},
  "hdg": 180.5, "isTraveling": false,
  "model": "Mavic 2 Enterprise Advanced",
  "bat": {"lvl": 85, "remaining": 3200, "temperature": 38.5,
          "charging": false, "connectionState": "CONNECTED",
          "voltage": 16800, "current": 250},
  "gimbal": {"pitch": -45.0, "roll": 0.0, "yaw": 12.3},
  "rcBat": {"lvl": 90, "remainingMah": 3200, "charging": false},
  "zoom": 2.5, "cameraMode": "RGB",
  "seq": 1234,
  "t_send_ms": 1748000000000
}
```
> `seq` — contador monotónico (reinicia por sessão); gaps no servidor = packet loss  
> `t_send_ms` — `System.currentTimeMillis()` no momento do envio; latência one-way se NTP sincronizado  
> Referência completa: `docs/telemetry-reference.md`

**Comandos** chegam via notificação OneM2M (campo `con` do `m2m:cin`):
```json
{"command": "takeoff"}
{"command": "land"}
{"command": "setZoom", "factor": 2.5}
{"command": "setCameraMode", "mode": "IR"}
{"command": "startMission", "startAction": "takeoff", "endAction": "land",
 "path": [{"lat": 39.933, "lng": -8.892}], "altitude": 50, "repeat": 1}
{"command": "stopMission"}
{"command": "pauseMission"}
{"command": "virtualSticksInput", "roll": 0.5, "pitch": 0.3, "yaw": 0, "throttle": 0.2}
{"command": "gpsInput", "lat": 39.933, "lng": -8.892}
{"command": "perform360"}
{"command": "identify", "state": true}
{"command": "gimbalAngle", "pitch": -45, "yaw": 0, "mode": "absolute"}
{"command": "gimbalReset"}
{"command": "motors", "state": true}
```
> Referência completa: `docs/command-reference.md`

### UI Architecture
- **`DuvopsView`** — ecrã de controlo principal; instancia e liga os managers
- **`FlightActivity`** — wrapper fullscreen para `DuvopsView`
- **`MainContent`** — home screen; SDK registration + botão para abrir FlightActivity

---

## Estado da Implementação Multi-Protocolo

### Completo ✅ (2026-05-24)
| Item | Notas |
|---|---|
| Campos `seq` + `t_send_ms` na telemetria | — |
| Reconnect automático com backoff | 1s→30s |
| Timeout na registration sequence | 10s/request |
| Command ACK (`cse-in/uxv/ack`) | `{command, seq_cmd, t_cmd_ms, t_recv_ms, t_exec_ms}` |
| Configurable telemetry rate (`setTelemetryRate`) | — |
| Estado do drone na UI | droneStateField |
| WebSocket transport (`NetworkManager.java`) | Testado end-to-end contra ACME CSE v2025.11 |
| `ProtocolClient` interface | Refactored: `getPoaUrl()`, `requiresExplicitNotifyAck()`, `sendAck()`, `RawMessageListener` |
| `OneM2MSession` (decorator) | Protocol-agnostic; partilhado pelos 4 transportes; bug fix reconnect |
| `MqttProtocolClient` | Paho 1.2.5; TOPIC_REQ/RESP/NOTIF; ACK via TOPIC_RESP (override) |
| `HttpProtocolClient` | OkHttp async POST + NanoHTTPD 2.3.1 callback (porta 8181) |
| `CoApProtocolClient` | Californium 2.7.4 CON POST + CoapServer callback (porta 5684) |
| Protocol selector spinner | Spinner na barra superior: WebSocket / MQTT / HTTP / CoAP |

### Pendente 🔜 (para benchmark)
| Item | Notas |
|---|---|
| End-to-end MQTT test | Testar registo AE + notificações vs ACME CSE real |
| End-to-end HTTP test | Verificar callback reachability (Docker → RC WiFi IP) |
| End-to-end CoAP test | Flat JSON body vs ACME CSE CoAP handler — verificar parsing |
| Benchmark runs (S1, S2) | ≥30 runs × 4 protocolos |

---

## Implementação por Protocolo — Notas de Transporte

Para todos os protocolos, o originator é sempre `"C" + serialNumber` (max 32 chars).
A `OneM2MSession` não muda — só o transport concreto é substituído.

### WebSocket — `NetworkManager.java` (✅ COMPLETO)

```
Ligação: ws://cse_ip:8180, subprotocolo "oneM2M.json", header X-M2M-Origin: C<serial>
AE poa:  ["ws://cse_ip:8180"]
Notificações: CSE reutiliza a ligação WS activa (mesmo originator em associatedConnections)
```

### MQTT — `MqttProtocolClient.java` (✅ COMPLETO)

```
Ligação: MQTT broker em cse_ip:1883, clientId = originator (ex: C3LKFD12ABC)
AE poa:  ["mqtt://cse_ip:1883"]
CIN send: publish para /oneM2M/req/C<serial>/id-in/json (QoS 1, retained=false)
Notificações: subscribe em /oneM2M/req/id-in/C<serial>/json (broker → AE)
```

Dependência: `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`

> **Timing:** `onConnectionStatusChange(true)` só é chamado após SUBACK confirmado — a
> sequência de registo AE não arranca antes de os tópicos estarem activos no broker.

### HTTP — `HttpProtocolClient.java` (✅ COMPLETO)

```
CIN send: OkHttp POST assíncrono para http://cse_ip:8080/{to}
          Headers: X-M2M-Origin, X-M2M-RI, X-M2M-RVI, Content-Type: application/json;ty={ty}
          rsc lido de X-M2M-RSC (não do HTTP status code)
AE poa:  ["http://rc_ip:8181"]   ← IP WiFi do RC (WifiManager), porta fixa 8181
Notificações: NanoHTTPD 2.3.1 na porta 8181
              CSE faz POST com {"m2m:sgn":{...}}
              ACK: HTTP 200 OK (requiresExplicitNotifyAck = false)
```

> **Reachability:** O contentor Docker do CSE deve conseguir atingir o IP do RC na LAN.
> O IP é obtido via `WifiManager` em `connect()`. Porta de callback fixa: 8181.

### CoAP — `CoApProtocolClient.java` (✅ COMPLETO)

```
CIN send: Californium 2.7.4 CON POST assíncrono para coap://cse_ip:5683/{to}
          Body: flat JSON completo (igual ao WS binding — sem URI query params)
          Content-Format: 50 (application/json)
          sharedEndpoint (porta efémera) reutilizado entre requests — sem criar socket por send
AE poa:  ["coap://rc_ip:5684/notify"]   ← IP WiFi do RC, porta fixa 5684, path /notify (UDP)
Notificações: CoapServer (Californium) na porta 5684 UDP, resource /notify
              CSE envia POST/PUT com {"m2m:sgn":{...}}
              ACK: CoAP 2.04 Changed (requiresExplicitNotifyAck = false)
```

> Californium 2.7.4 (Java 8) — não usar 3.x (requer Java 11).
> CoAP usa UDP — verificar que a LAN não bloqueia UDP entre container e RC.
> build.gradle: usar `org.nanohttpd:nanohttpd:2.3.1` (Maven Central) + excluir `META-INF/legal/**`.

---

## ACME CSE v2025.11 — WebSocket Integration Findings

Descobertos por testes end-to-end contra o CSE real. **Todos verificados empiricamente.**
Ver `docs/ai-context/cse-dev.md` → secção "WebSocket Binding" para detalhe completo.

### Formato das mensagens (flat JSON — sem wrappers)

```
// Request  → flat: {"op":1,"to":"cse-in/uxv/telemetry","fr":"Cxxx","rqi":"r1","rvi":"3","ty":4,"pc":{...}}
// Response → flat: {"rsc":2001,"rqi":"r1","pc":{...}}          — detectar por "rsc" no topo
// Notify   → flat: {"op":5,"rqi":"n1","pc":{"m2m:sgn":{...}}} — detectar por "op"=5 no topo
// ACK      → flat: {"rsc":2000,"rqi":"n1","to":"Cxxx","fr":"Cxxx"}
```

### Regras específicas de v2025.11

| Regra | Detalhes |
|---|---|
| Subprotocolo WS obrigatório | `Sec-WebSocket-Protocol: oneM2M.json` no upgrade |
| `X-M2M-Origin` no upgrade | Header obrigatório — sem ele rsc=4103 em todas as ops |
| `rvi="3"` em todos os requests | Campo obrigatório — sem ele rsc=4000 |
| `to` para AE registration | `"id-in"` (CSE-relative, SEM leading slash) |
| `to` para outros recursos | `"cse-in/{ae-rn}/..."` (CSE-Base rn, SEM leading slash) |
| `aei` no body do AE | PROIBIDO — non-provision attribute → rsc=4000 |
| `poa` no body do AE | OBRIGATÓRIO — sem `poa` as notificações são descartadas silenciosamente |
| `nct` na subscrição | OMITIR — nct=2 + net=[3] é inválido → rsc=4000 |
| `cnf` no CIN | OMITIR — "application/json" falha validação → rsc=4000 |
| ACP para o AE | Não é criado automaticamente → `enableACPChecks=false` no CSE |

### `poa` — a regra mais importante (e menos óbvia)

Sem `poa` no registo do AE, as notificações de subscrição são **silenciosamente descartadas**.
O CSE resolve o alvo da notificação (`nu`) → obtém o AE → lê o `poa` → se `poa=None` → devolve
lista vazia → notificação perdida. Nenhum erro é reportado.

A `poa` deve ser o endereço WebSocket do CSE: `ws://{host}:{port}` (o mesmo a que a app ligou).
O CSE tenta abrir uma nova ligação WS para esse endereço, mas como já existe uma ligação com
o mesmo originator em `associatedConnections`, reutiliza-a.

---

## Critical Development Notes

### Hardware Requirements
- **Testes físicos obrigatórios** para: câmara, zoom, voo, telemetria
- SDK simulator: só para UI/layout — câmara e telemetria não funcionam

### Safety Constraints
- Todos os comandos de voo validados em `FlightManager.checkController()`
- `Double.isNaN()` em todos os valores de telemetria antes de usar
- Virtual sticks timeout: modo desactivado automaticamente no `onStopMission()`

### OneM2M Integration Notes
- AE registration usa `rr=true` (reachable resource) — necessário para notificações WS
- Containers criados com `mni=10` (telemetry) e `mni=5` (commands)
- Conflito (rsc 4105) é tratado como sucesso — permite reconnect sem limpeza do CSE
- Notificações chegam como `m2m:rqp` com `op=5` — ACK obrigatório (enviado em `sendNotifyAck()`)
- `OneM2MSession.sendTelemetry()` é fire-and-forget — respostas de CIN ignoradas

---

## System Architecture

- **Event Bus**: Otto (`App.java`) para comunicação `MainActivity → MainContent`
- **MultiDex**: Enabled (`minSdkVersion 23+`, DJI SDK é grande)
- **Navigation**: PID controllers + virtual sticks (5Hz, Haversine distance)
- **Camera**: `CameraManager` via KeySDK (`CameraKey.HYBRID_ZOOM_FOCAL_LENGTH`, etc.)
- **Threading**: Timer (250ms telemetria), Thread separada para missões e GPS moves

---

## Project Conventions

### Nomenclatura

| Contexto | Convenção | Exemplo |
|---|---|---|
| Java methods | camelCase | `setVirtualStickModeEnabled` |
| Java fields | camelCase | `protocolClient` |
| Java constants | SCREAMING_SNAKE_CASE | `DEFAULT_CSE_WS_PORT` |
| Java classes | PascalCase | `OneM2MSession` |
| Layout XML IDs | snake_case | `pb_mission` |
| OneM2M commands | camelCase | `setZoom`, `startMission` |
| Telemetry JSON fields | camelCase | `batLvl`, `cameraMode` |

### Code Style
- Cabeçalho em cada ficheiro Java com propósito descrito
- Docblock em cada método: `@param`, `@return`, `@throws`
- Comentários inline apenas para **porquê**, não **o quê**
- Erros críticos via `ToastUtils.setResultToToast()` sempre
- Telemetria validada para NaN: `Double.isNaN(value)` antes de usar

---

## Managers Reference

### ProtocolClient (interface)
| Método | Descrição |
|---|---|
| `connect(host, port, aeId)` | Liga ao CSE; port=8180 (WS), 1883 (MQTT), 8080 (HTTP), 5683 (CoAP) |
| `disconnect()` | Desliga e liberta recursos |
| `sendTelemetry(json)` | Envia payload (OneM2MSession envolve em m2m:cin) |
| `isConnected()` | true se sessão OneM2M pronta |
| `setCommandLogListener(l)` | Listener de debug para comandos recebidos |

### OneM2MSession (implements ProtocolClient, DroneCommandListener)
| Método / Campo | Descrição |
|---|---|
| `OneM2MSession(DroneCommandListener)` | Constructor — passa FlightManager |
| `setTransport(NetworkManager)` | Regista raw message listener no transport |
| `setSessionListener(SessionListener)` | Callback de estados intermédios → statusField |
| `setTelemetryRateListener(l)` | Callback para `setTelemetryRate` → TelemetryManager |
| `connect(host, port, aeId)` | Computa originator, inicia sequência de 6 passos |
| `sendTelemetry(json)` | Envolve em m2m:cin, envia para `cse-in/uxv/telemetry` |
| `dispatchCommand(json)` | Parseia comando, despacha, envia ACK a `cse-in/uxv/ack` |
| `sendCommandAck(...)` | CIN com `{command, seq_cmd, t_cmd_ms, t_recv_ms, t_exec_ms}` |
| `shutdown()` | Cancela reconnect, para scheduler, desliga transport |
| `CSE_BASE = "cse-in"` | Path base do CSE — sem slash inicial (flat JSON `to` field) |
| `AE_NAME = "uxv"` | Nome do recurso AE |

### NetworkManager (implements ProtocolClient)
| Método | Descrição |
|---|---|
| `connect(host, port, aeId)` | Constrói `ws://host:port`, abre WebSocket |
| `disconnect()` | Fecha WebSocket (code 1000) |
| `sendTelemetry(json)` | `ws.send(json)` — envia string raw |
| `handleRawMessage(text)` | Se rawMessageListener definido → delega; senão → processCommand() |
| `setRawMessageListener(l)` | Usado por OneM2MSession para interceptar mensagens |
| `notifyConnectionChange(b, msg)` | Chama DroneCommandListener.onConnectionStatusChange() |

### FlightManager (implements DroneCommandListener)
| Método | Descrição |
|---|---|
| `onTakeOff()` | `flightController.startTakeoff()` |
| `onLand()` | `flightController.startLanding()` |
| `onMotors(boolean)` | `turnOnMotors()` / `turnOffMotors()` |
| `onGoHome()` | `flightController.startGoHome()` |
| `onVirtualStickState(boolean)` | Enable/disable virtual sticks + advanced mode |
| `onVirtualStickInput(r, p, y, t)` | Actualiza roll/pitch/yaw/throttle (scale: 10×/20×/4×) |
| `onMoveTo(lat, lng)` | PID navigation thread (Haversine, stop at 2 m) |
| `onPerform360()` | Yaw 30°/s durante ~12 s |
| `onStartMission(...)` | Thread de missão waypoint (RUNNING/PAUSED/STOPPED) |
| `onSetZoom(float)` | Delega ao CameraManager |
| `onSetCameraMode(String)` | Delega ao CameraManager |
| `onGimbalAngle(pitch, yaw, mode)` | `gimbal.rotate()`, clamp pitch[-90,30] yaw[-75,75] |
| `onGimbalReset()` | `gimbal.reset()` |

### CameraManager
| Método | Descrição |
|---|---|
| `setZoom(float factor)` | `focalLength = factor × 240`, via KeyManager |
| `setCameraMode(String mode)` | Troca stream: RGB/WIDE, IR/INFRARED_THERMAL, SPLIT/PIP |

### TelemetryManager
| Método | Descrição |
|---|---|
| `startTelemetry()` | Inicia timer com `intervalMs` (default 250 ms) |
| `stopTelemetry()` | Para timer |
| `setRate(int intervalMs)` | Altera taxa; reinicia timer se activo (Cenário 1) |
| `setTickListener(l)` | Callback `onSlowTick(seq, bat, isFlying, sats)` a cada ~1 s |
| `setFlightController(fc)` | Actualiza referência do FC |
| `setTraveling(boolean)` | Actualiza flag isTraveling |
| `setModelName(String)` | Nome do modelo no JSON de telemetria |

---

## Architecture Decision Log (ADRs)

### ADR-001: WebSocket como protocolo inicial
- **Decisão:** WebSocket (okhttp3) como primeiro transporte a implementar
- **Racional:** Bidirecional, persistente, suportado pelo ACME CSE, okhttp3 já é dep do DJI SDK
- **Consequências:** Sem reconnect automático nativo — implementar manualmente

### ADR-002: Otto Event Bus vs LiveData/Compose
- **Decisão:** Manter Otto para comunicação inter-componente
- **Racional:** Código existente usa Otto extensivamente; migração seria breaking change
- **Consequências:** Thread-safety manual necessária; sem lifecycle awareness

### ADR-003: Zoom conversion factor = ×240
- **Decisão:** `zoomFactor × 240 = focalLength_mm`
- **Racional:** M2EA usa focal length em mm; 24 mm = 1.0×, confirmado experimentalmente

### ADR-004: Virtual Sticks + PID vs WaypointMissionOperator
- **Decisão:** Virtual Sticks + PID (5 Hz) para navegação GPS
- **Racional:** Waypoint V2 é upload-only, sem feedback real-time; PID permite correcção contínua
- **Consequências:** Cálculo manual de distância/direcção (Haversine), mais complexidade mas mais controlo

### ADR-005: Hybrid Zoom via `setOpticalZoomFocalLength`
- **Decisão:** Focal length em vez de digital zoom
- **Racional:** M2EA tem 8× óptico + 4× digital; focal length controla a parte óptica sem perda de qualidade

### ADR-006: Camera stream mapping por mode
- **Decisão:** `RGB → WIDE`, `IR → INFRARED_THERMAL`, `SPLIT → PIP`
- **Racional:** M2EA tem duas câmaras; selecção via VideoStreamSource

### ADR-007: DuvopsView (activo) vs DboidsView (referência)
- **Decisão:** DuvopsView é o alvo de desenvolvimento; DboidsView é read-only
- **Racional:** DboidsView é monolítico (~1200 linhas); DuvopsView separa concerns em managers

### ADR-008: Testes físicos obrigatórios para câmara/voo
- **Decisão:** Nunca confiar no simulator para câmara ou telemetria real
- **Racional:** SDK simulator tem limitações significativas; video feed não funciona

### ADR-009: ProtocolClient abstraction para multi-protocolo
- **Decisão:** Interface `ProtocolClient` isola o transporte da lógica de voo/telemetria
- **Racional:** Benchmark requer 4 protocolos (WS, MQTT, HTTP, CoAP); sem abstracção seria
  necessário manter 4 versões separadas do app
- **Consequências:**
  - `OneM2MSession` é um decorator sobre `ProtocolClient` que adiciona semântica OneM2M
  - `NetworkManager` implementa `ProtocolClient` para WebSocket
  - Future: `MqttProtocolClient`, `HttpProtocolClient`, `CoApProtocolClient`
  - `DuvopsView` usa tipo abstracto `ProtocolClient` — mudança de protocolo sem alterar UI

### ADR-010: OneM2M session na app (AE completo vs proxy simples)
- **Decisão:** A app é um AE oneM2M completo — regista-se, cria containers, subscreve
- **Racional:** Para o benchmark ser válido, o protocolo oneM2M deve ser usado correctamente
  end-to-end; um proxy simples não mediria o overhead real do middleware
- **Consequências:**
  - `OneM2MSession` gere a sequência de registo (6 passos assíncronos)
  - Conflito (rsc 4105) é tratado como sucesso para permitir reconnect sem limpar o CSE
  - Telemetria é fire-and-forget (sem aguardar ACK) para não bloquear o timer de 250 ms

---

## Known Issues & Workarounds

| Issue | Workaround |
|---|---|
| Video feed morre após 500 ms no simulator | Testar apenas no device físico |
| Virtual sticks derivam em missões longas | PID tuning a cada 200 ms (não mais rápido) |
| Zoom indisponível em modo IR | Verificar stream antes do comando zoom |
| WebSocket desliga quando RC entra em sleep | Reconnect automático implementado (backoff 1 s→30 s) |
| Câmara térmica requer mudança de modo | Definir cameraMode antes do zoom |
| Gimbal pitch fora de range em algumas missões | Clamp pitch para [-90,30] antes de enviar |
| Notificações não chegam sem `poa` | `poa=['ws://host:port']` obrigatório no registo AE — já corrigido |
| `nu='/id-in/uxv'` não entregava notificações | `nu=aeOriginator` correcto — já corrigido |
| HTTP paths incorrectos `/id-in/...` | Paths corrector: `/cse-in/...` — já corrigido em CSE_BASE |

---

## Source Code Structure

```
app/src/main/java/com/dji/sdk/duvops/
├── app/
│   ├── App.java                  # EventBus singleton + product accessors
│   ├── MainActivity.java         # Launcher; USB accessory handler
│   └── MainContent.java          # Home screen; DJI SDK registration + permissões
├── flight/
│   ├── DuvopsView.java           # ⭐ UI principal; spinner protocolo; buildTransport()
│   ├── FlightActivity.java       # Wrapper fullscreen para DuvopsView
│   ├── FlightManager.java        # ⭐ Executa comandos de voo; impl DroneCommandListener
│   ├── CameraManager.java        # Zoom e modo de câmara via KeyManager
│   ├── TelemetryManager.java     # ⭐ Timer 250 ms; recolhe e envia telemetria
│   └── PIDController.java        # PID para navegação GPS
└── network/
    ├── ProtocolClient.java        # ⭐ Interface de transporte (WS/MQTT/HTTP/CoAP)
    ├── OneM2MSession.java         # ⭐ Sessão OneM2M: AE reg + CIN + SUB + dispatch
    ├── NetworkManager.java        # ⭐ Transporte WebSocket (okhttp3); impl ProtocolClient
    ├── MqttProtocolClient.java    # ⭐ Transporte MQTT (Paho 1.2.5); impl ProtocolClient
    ├── HttpProtocolClient.java    # ⭐ Transporte HTTP (OkHttp + NanoHTTPD); impl ProtocolClient
    ├── CoApProtocolClient.java    # ⭐ Transporte CoAP (Californium 2.7.4); impl ProtocolClient
    ├── SocketListener.java        # Callbacks do WebSocket → NetworkManager
    └── DroneCommandListener.java  # Interface de comandos de voo (18 métodos)
```
> ⭐ Ficheiros principais — ler antes de qualquer alteração

---

## Arquitectura Multi-Protocolo — Estado Actual (2026-05-24)

Todos os 4 transportes estão implementados. O fluxo de selecção é:

```
DuvopsView.connectToCse()
  → protocolSpinner.getSelectedItem() → "WebSocket" / "MQTT" / "HTTP" / "CoAP"
  → buildTransport(protocol) → NetworkManager / MqttProtocolClient / Http... / CoAp...
  → session.setTransport(newTransport) → swap do transport na sessão
  → session.connect(host, port, serialNumber) → inicia sequência de 6 passos OneM2M
```

Ao trocar o transport, `OneM2MSession` mantém toda a lógica oneM2M — só o canal de rede muda.
O `protocolClient` (= session) nunca muda — apenas o transport interno.

### Diferenças entre transportes

| Transport | ACK explícito | Formato notificação | Thread do callback |
|---|---|---|---|
| WebSocket | Sim (`sendAck` = `sendTelemetry`) | `{"op":5,"pc":{"m2m:sgn":{...}}}` | OkHttp callback |
| MQTT | Sim (`sendAck` publica TOPIC_RESP) | `{"op":5,"pc":{"m2m:sgn":{...}}}` flat | Paho callback |
| HTTP | Não (HTTP 200 = ACK) | `{"m2m:sgn":{...}}` directo | NanoHTTPD thread |
| CoAP | Não (2.04 Changed = ACK) | `{"m2m:sgn":{...}}` directo | Californium thread |

---

## Documentation References

| Recurso | Local | Propósito |
|---|---|---|
| DJI SDK v4 API Reference | `docs/DJIMobileSDKAndroidAPIReference.md` | Referência completa da API (local) |
| `docs/DboidsView.java` | Código legado (~1200 linhas, READ ONLY) | Referência de implementação |
| `docs/mavic2_gimbal.txt` | Exemplos de rotação do gimbal | Referência |
| `docs/telemetry-reference.md` | Todos os campos de telemetria | Referência |
| `docs/command-reference.md` | Todos os comandos suportados | Referência |
| ACME CSE WebSocket | `docs/ai-context/cse-dev.md` | Configuração do CSE e protocolo |
