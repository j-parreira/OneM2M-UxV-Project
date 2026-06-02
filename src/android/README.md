# UxV Benchmark — Android App

Android application for the **DJI Mavic 2 Enterprise Advanced (M2EA)**, installed on the DJI RC remote controller. Implements a **OneM2M Application Entity (AE)** that integrates with an ACME CSE to enable protocol benchmarking across WebSocket, MQTT, HTTP, and CoAP.

Part of an academic research project at **IPL Leiria** benchmarking OneM2M middleware performance for unmanned vehicle (UxV) operations.

---

## Hardware Requirements

| Component | Requirement |
|---|---|
| Drone | DJI Mavic 2 Enterprise Advanced (M2EA) |
| Controller | DJI Smart Controller (RC with Android) |
| Network | RC and CSE host on the **same WiFi LAN** |
| Android SDK | DJI SDK v4.16.4 — physical device required for camera/flight |

> The DJI simulator supports basic UI testing but not camera, video feed, or real telemetry values.

---

## Quick Start

### 1. Build and install

```bat
cd src\android
.\gradlew.bat assembleDebug
adb install app\build\outputs\apk\debug\app-debug.apk
```

Requires Android Studio or standalone Android SDK with `adb` on PATH. The DJI API key is already embedded in `AndroidManifest.xml`.

### 2. Start the ACME CSE

Before launching the app, the CSE must be running and reachable on the LAN:

```bash
cd src/cse
docker compose up
```

Wait for the CSE to be healthy on `:8080`. See `docs/ai-context/cse-dev.md`.

### 3. Launch the app

1. Connect the M2EA drone to the RC via OcuSync
2. Open **UxV Benchmark** on the RC
3. Tap **Registar App** — DJI SDK registers (requires internet on first run)
4. Once the drone is connected, tap **Open Benchmark**
5. Enter the CSE host IP in the top field (e.g. `192.168.1.100`)
6. Tap **Connect CSE** — the app registers as AE and starts sending telemetry

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                   Android App (RC)                   │
│                                                      │
│  DuvopsView ──► TelemetryManager                     │
│      │  (protocol spinner)  │ sendTelemetry()        │
│      ▼                      ▼                        │
│  OneM2MSession        (ProtocolClient)               │
│      │   AE registration, CIN, SUB, ACK              │
│      ▼                                               │
│  ┌─────────────┬────────────┬────────────┐           │
│  │NetworkMgr   │MqttProto   │HttpProto   │CoApProto  │
│  │(WebSocket)  │(Paho 1.2.5)│(OkHttp +  │(Californium│
│  │             │            │NanoHTTPD) │2.7.4)      │
│  └──────┬──────┴─────┬──────┴─────┬─────┴─────┬─────┘
│         │            │            │           │      │
└─────────┼────────────┼────────────┼───────────┼──────┘
          │ ws:8180    │ mqtt:1883  │ http:8080 │ coap:5683
          └────────────┴────────────┴───────────┴──────►
                                ACME CSE /cse-in/uxv/
```

The `ProtocolClient` interface decouples the transport from all flight and telemetry logic. `OneM2MSession`, `FlightManager`, and `TelemetryManager` are protocol-agnostic. Swapping protocols is done at connect time via `DuvopsView.buildTransport()`.

---

## OneM2M Integration

### Resource tree

After connecting, the app creates the following OneM2M resource tree on the CSE:

```
/id-in                    ← CSE-Base (configured in acme.ini)
└── uxv                   ← AE  (registered by the app on startup)
    ├── telemetry         ← CNT (mni=10)  — uplink: drone state
    ├── commands          ← CNT (mni=5)   — downlink: control commands
    │   └── sub-commands  ← SUB           — push notification to app
    └── ack               ← CNT (mni=200) — command receipt timestamps
```

### Registration sequence (6 async steps, all transports)

```
connect(host, port, serialNumber)
  → transport connects  → registerAE()              [ty=2, to="id-in", poa=[transport URL]]
  → 2001/4105           → createTelemetryContainer  [ty=3, rn=telemetry]
  → 2001/4105           → createCommandsContainer   [ty=3, rn=commands]
  → 2001/4105           → createSubscription        [ty=23, to="cse-in/uxv/commands"]
  → 2001/4105           → createAckContainer        [ty=3, rn=ack]
  → 2001/4105           → session ready → startTelemetry()
```

HTTP 4105 (Conflict — resource already exists) is treated as success, enabling reconnect without clearing the CSE.

### Reconnect behaviour

On transport failure, `OneM2MSession` retries with **exponential backoff**: 1 s → 2 s → 4 s → … → 30 s (capped). Each reconnect restarts the full 6-step registration. Backoff resets when the session becomes ready.

Each registration request has a **10-second timeout**. If the CSE does not respond, the registration fails and a reconnect is scheduled.

---

## Telemetry (Uplink — Scenario 1)

The app sends a `m2m:cin` to `cse-in/uxv/telemetry` at a configurable rate (default 250 ms). The JSON payload in the `con` field:

```json
{
  "lat": 39.933,  "lng": -8.892,  "alt": 15.2,
  "velX": 1.2,    "velY": -0.5,   "velZ": 0.3,
  "isFlying": true,  "satCount": 18,  "rft": 1200.0,
  "isGoingHome": false,  "areMotorsOn": true,
  "isHomeLocationSet": true,
  "homeLocation": {"lat": 39.933, "lng": -8.892},
  "hdg": 180.5,  "isTraveling": false,
  "model": "Mavic 2 Enterprise Advanced",
  "bat": {"lvl": 85, "remaining": 3200, "temperature": 38.5,
          "charging": false, "connectionState": "CONNECTED",
          "voltage": 16800, "current": 250},
  "gimbal": {"pitch": -45.0, "roll": 0.0, "yaw": 12.3},
  "rcBat": {"lvl": 90, "remainingMah": 3200, "charging": false},
  "zoom": 2.5,  "cameraMode": "RGB",
  "seq": 1234,
  "t_send_ms": 1748000000000
}
```

| Field | Purpose |
|---|---|
| `seq` | Monotonic counter per session — gaps indicate packet loss |
| `t_send_ms` | `System.currentTimeMillis()` at send time — one-way latency if NTP-synced |

Full field reference: [`docs/telemetry-reference.md`](docs/telemetry-reference.md)

**Changing the telemetry rate** (Scenario 1 uses 4 msg/s and 16 msg/s):

```json
{"command": "setTelemetryRate", "intervalMs": 250}
```

Rates: `250` ms = 4 msg/s (single drone) · `62` ms = 16 msg/s (4 drones simulated)

---

## Commands (Downlink — Scenario 2)

The Streamlit dashboard creates a `m2m:cin` in `cse-in/uxv/commands`. The `con` field contains:

```json
{"command": "takeoff", "t_cmd_ms": 1748000000000, "seq_cmd": 1}
```

The app receives the command via OneM2M push notification, executes it, and posts an **ACK** to `cse-in/uxv/ack`:

```json
{
  "command":   "takeoff",
  "seq_cmd":   1,
  "t_cmd_ms":  1748000000000,
  "t_recv_ms": 1748000000087,
  "t_exec_ms": 1748000000092
}
```

**Command latency** measured by the dashboard: `t_recv_ms − t_cmd_ms`

### Supported commands

| Command | Parameters | Effect |
|---|---|---|
| `takeoff` | — | Start takeoff |
| `land` | — | Start landing |
| `motors` | `state` (bool) | Turn motors on/off |
| `startGoHome` | — | Return to home |
| `gpsInput` | `lat`, `lng` | Navigate to GPS coordinates (PID) |
| `virtualSticksInput` | `roll`, `pitch`, `yaw`, `throttle` | Direct stick control |
| `virtualSticks` | `state` (bool) | Enable/disable virtual stick mode |
| `gimbalAngle` | `pitch`, `yaw`, `mode` | Set gimbal angle (`absolute`/`relative`) |
| `gimbalReset` | — | Reset gimbal to neutral |
| `startMission` | `path`, `altitude`, `startAction`, `endAction`, `repeat` | Waypoint mission |
| `stopMission` | — | Abort mission |
| `pauseMission` | — | Pause/resume mission (toggle) |
| `setZoom` | `factor` (1.0–32.0) | Set hybrid zoom |
| `setCameraMode` | `mode` (`RGB`/`IR`/`SPLIT`) | Switch camera stream |
| `perform360` | — | 360° yaw rotation |
| `identify` | `state` (bool) | Toggle LEDs/beacons |
| `setTelemetryRate` | `intervalMs` | **Benchmark** — change telemetry rate |

Full reference: [`docs/command-reference.md`](docs/command-reference.md)

---

## UI Overview

```
┌──────────────────────────────────────────────────────────┐
│  [192.168.1.100    ] [Connect CSE] [Simulator] [Abort]  │  ← top bar
│                                                          │
│              H.264 video feed (fullscreen)               │
│                                                          │
│  Registering AE (C3LKFD12ABC)...                        │  ← statusField
│  TX: seq=1234                                            │  ← messageField
│  Bat: 85% | Flying | 18 sats                            │  ← droneStateField
└──────────────────────────────────────────────────────────┘
```

| UI element | Content |
|---|---|
| Host field | CSE IP or `host:port` — persisted across restarts |
| Connect CSE / Disconnect | Toggle — shows session state |
| Simulator | Starts DJI simulator at IPL Leiria coordinates (39.933, −8.892) |
| Abort | Emergency exit — calls `cleanup()` before `System.exit()` |
| `statusField` | OneM2M session state (white = intermediate, green = ready, red = error) |
| `messageField` | Live TX counter (`TX: seq=N`) or last received command (`CMD: X`) |
| `droneStateField` | `Bat: 85% | Flying | 18 sats` — updated every ~1 s |

---

## Project Structure

```
src/android/
├── app/src/main/java/com/dji/sdk/duvops/
│   ├── app/
│   │   ├── App.java                  EventBus singleton + DJI product accessor
│   │   ├── MainActivity.java         Launcher, USB accessory handler
│   │   └── MainContent.java          SDK registration screen
│   ├── flight/
│   │   ├── DuvopsView.java  ⭐        Main UI; protocol spinner; buildTransport()
│   │   ├── FlightActivity.java       Fullscreen wrapper for DuvopsView
│   │   ├── FlightManager.java ⭐      Executes 18 flight commands via DJI SDK
│   │   ├── TelemetryManager.java ⭐   250 ms timer, 22+ telemetry fields
│   │   ├── CameraManager.java        Zoom (×240 focal length) + RGB/IR/SPLIT modes
│   │   └── PIDController.java        PID for GPS waypoint navigation
│   └── network/
│       ├── ProtocolClient.java ⭐     Transport abstraction interface (4 transports)
│       ├── OneM2MSession.java  ⭐     AE registration, CIN, SUB, ACK, reconnect
│       ├── NetworkManager.java ⭐     WebSocket transport (okhttp3)
│       ├── MqttProtocolClient.java ⭐ MQTT transport (Paho 1.2.5)
│       ├── HttpProtocolClient.java ⭐ HTTP transport (OkHttp + NanoHTTPD 2.3.1)
│       ├── CoApProtocolClient.java ⭐ CoAP transport (Californium 2.7.4)
│       ├── SocketListener.java       WebSocket lifecycle callbacks
│       └── DroneCommandListener.java 18-method command interface
├── docs/
│   ├── telemetry-reference.md        All telemetry fields documented
│   ├── command-reference.md          All commands with parameters and ACK format
│   └── DJIMobileSDKAndroidAPIReference.md  Local copy of DJI SDK v4 API docs
└── CLAUDE.md                         Architecture guide for AI-assisted development
```

---

## Protocol Transports

All 4 transports are implemented. The spinner in the `DuvopsView` top bar selects the protocol at connect time. `DuvopsView.buildTransport(protocol)` instantiates the right client; `session.setTransport(newTransport)` swaps it before `connect()` is called.

| Transport | Class | Default port | `poa` in AE | Notification mechanism |
|---|---|---|---|---|
| WebSocket | `NetworkManager` | 8180 | `ws://cse_ip:8180` | CSE reuses active WS connection |
| MQTT | `MqttProtocolClient` | 1883 | `mqtt://cse_ip:1883` | Broker publishes to `TOPIC_NOTIF` |
| HTTP | `HttpProtocolClient` | 8080 | `http://rc_ip:8181` | CSE POSTs to embedded NanoHTTPD |
| CoAP | `CoApProtocolClient` | 5683 | `coap://rc_ip:5684/notify` | CSE sends CON POST to embedded Californium server |

`OneM2MSession` is protocol-agnostic — registration sequence, telemetry wrapping, ACK sending, and reconnect are all unchanged across transports.

### Transport-specific behaviour

| | WS ACK | MQTT ACK | HTTP ACK | CoAP ACK |
|---|---|---|---|---|
| `requiresExplicitNotifyAck()` | `true` | `true` | `false` | `false` |
| Notification format | `{"op":5,"pc":{"m2m:sgn":{...}}}` | `{"op":5,"pc":{"m2m:sgn":{...}}}` | `{"m2m:sgn":{...}}` | `{"m2m:sgn":{...}}` |
| ACK sent via | `sendTelemetry()` → TOPIC_REQ | `sendAck()` → TOPIC_RESP | HTTP 200 (implicit) | CoAP 2.04 (implicit) |

---

## Known Issues

| Issue | Status / Workaround |
|---|---|
| Video feed unavailable in simulator | Test on physical RC only |
| Virtual sticks drift on long missions | PID updates every 200 ms — do not reduce interval |
| Zoom commands fail in IR mode | Send `setCameraMode RGB` before `setZoom` |
| Gimbal pitch out of range on some missions | Pitch is clamped to [−90, +30] before sending |
| `t_send_ms` drift under heavy NTP adjustments | Use `seq` gaps for packet loss; treat `latency_ms` as NTP-dependent |
| HTTP/CoAP notification reachability | CSE container must reach RC's WiFi LAN IP — both on same subnet |
| CoAP flat JSON body — needs end-to-end test | ACME CSE v2025.11 CoAP binding not yet tested with full flat JSON payload |

---

## Build Dependencies

| Library | Version | Purpose |
|---|---|---|
| DJI Mobile SDK | 4.16.4 | Drone control, telemetry, video |
| OkHttp3 | 3.11.0 | WebSocket transport (`NetworkManager`) + HTTP requests (`HttpProtocolClient`) |
| Paho MQTT | 1.2.5 | MQTT transport (`MqttProtocolClient`) |
| NanoHTTPD | 2.3.1 | Embedded HTTP callback server for push notifications (HTTP transport) — `org.nanohttpd:nanohttpd` |
| Californium Core | 2.7.4 | CoAP transport (`CoApProtocolClient`) — Java 8 compatible; 3.x requires Java 11 |
| Otto | 1.3.8 | Event bus (MainActivity ↔ MainContent) |
| AndroidX AppCompat | 1.0.0 | Activity base classes |
| AndroidX ConstraintLayout | 1.1.3 | Required by DJI SDK internal layouts |
| Kotlin stdlib | 1.6.21 | Runtime (DJI SDK internal use) |
| Google Play Services | 11.8.0 | Location services required by DJI SDK |

---

## Research Context

- **Project:** OneM2M UxV Multi-Protocol Study — IPL Leiria, MSc Computer Engineering
- **Paper deadline:** 2026-06-06 (Mobilidade em Sistemas Computacionais)
- **Benchmark scenarios:**
  - **Scenario 1** — Telemetry stream at 4 msg/s and 16 msg/s (120 s)
  - **Scenario 2** — Command round-trip (60 commands at 1/s: takeoff→lights on→land→lights off cycle)
  - **Scenario 3** — Degraded network (`tc netem` inside CSE container)
- **Protocols implemented:** WebSocket ✅ · MQTT ✅ · HTTP ✅ · CoAP ✅
- **Next step:** end-to-end integration test — all 4 vs real ACME CSE
