# System Architecture Overview

> Last updated: 2026-05-21. All resource paths and port numbers reflect the
> empirically verified ACME CSE v2025.11 implementation.

---

## OneM2M Resource Tree

```
CSE-Base: GET http://host:8080/id-in   (CSE-ID = "id-in")

HTTP paths for all child resources use /cse-in/ prefix (CSE-Base resource name):

/cse-in/uxv                        AE  — registered by Android app on startup
/cse-in/uxv/telemetry              CNT — uplink: drone state at 250ms (configurable)
/cse-in/uxv/commands               CNT — downlink: control commands from Streamlit
/cse-in/uxv/commands/sub-commands  SUB — push notifications to Android app
/cse-in/uxv/ack                    CNT — command receipt timestamps (Scenario 2 latency)
```

> Note: `/id-in` (CSE-ID) is ONLY valid for `GET /id-in` (CSE-Base).
> All child resources use `/cse-in/` (CSE-Base resource name). This is ACME CSE v2025.11
> specific — earlier documentation used `/onem2m/...` which is incorrect for this version.

---

## Data Flow (Telemetry — Scenario 1 Uplink)

```
DJI M2EA drone
  │  DJI SDK v4 callbacks (GPS, battery, gimbal, ...)
  ▼
Android App (DJI RC)
  │  POST m2m:cin to /cse-in/uxv/telemetry  [WebSocket / MQTT / HTTP / CoAP]
  │  Body: {lat, lng, alt, velX/Y/Z, bat, gimbal, seq, t_send_ms, ...}
  ▼
ACME CSE (Docker container, :8080/:8180/:1883/:5683)
  │  Stores CIN, fires subscription notification
  ▼
Streamlit Dashboard
  │  Receives notification, records t_receive_ms
  │  Latency = t_receive_ms - t_send_ms  (requires NTP sync)
  │  Packet loss = gaps in seq counter
  └─ Logs to data/raw/<protocol>_s1_<date>_run<N>.csv
```

## Data Flow (Commands — Scenario 2 Downlink)

```
Streamlit Dashboard
  │  POST m2m:cin to /cse-in/uxv/commands  [admin originator, HTTP]
  │  Body: {command, seq_cmd, t_cmd_ms}
  ▼
ACME CSE
  │  Fires subscription notification to Android app
  ▼
Android App
  │  Receives notification (WebSocket push, on same connection)
  │  Dispatches command to DJI SDK (takeoff, land, setZoom, ...)
  │  Records t_recv_ms (reception) + t_exec_ms (after dispatch)
  │  POST m2m:cin to /cse-in/uxv/ack
  │  Body: {command, seq_cmd, t_cmd_ms, t_recv_ms, t_exec_ms}
  ▼
ACME CSE
  │  Stores ACK CIN, fires subscription notification
  ▼
Streamlit Dashboard
  │  Receives ACK
  │  Latency = t_recv_ms - t_cmd_ms   (device-to-device, no NTP dependency)
  └─ Logs to data/raw/<protocol>_s2_<date>_run<N>.csv
```

---

## Network Topology (Lab Setup)

```
[DJI M2EA drone] ─OcuSync─▶ [DJI RC (Android)]
                                    │
                               WiFi (LAN)
                                    │
                      ┌─────────────▼──────────────────┐
                      │       Dev Machine — Windows 11  │
                      │                                 │
                      │  Docker: acme-cse               │
                      │    :8080/tcp  HTTP              │
                      │    :8180/tcp  WebSocket         │
                      │    :5683/udp  CoAP              │
                      │                                 │
                      │  Docker: mosquitto              │
                      │    :1883/tcp  MQTT              │
                      │    (ACME CSE connects to it)    │
                      │                                 │
                      │  Streamlit dashboard            │
                      │    :8501 (host Python venv)     │
                      │                                 │
                      │  tc netem: inside acme-cse      │
                      │  container (Scenario 3)         │
                      └─────────────────────────────────┘
```

The Android app is configured with the dev machine's LAN IP via a field in the app UI.
No IPs are hardcoded in the app or in committed configuration files.

---

## Protocol Binding Summary

| Protocol | Port | Transport | OneM2M binding | Measurement approach |
|---|---|---|---|---|
| WebSocket | 8180 | TCP | Persistent connection; flat JSON; `oneM2M.json` subprotocol | Full end-to-end; push notifications |
| MQTT | 1883 | TCP | AE client → Mosquitto broker → CSE MQTT client | Pub/sub; seq-based loss detection |
| HTTP | 8080 | TCP | REST POST/GET; stateless | Per-request ACK in response |
| CoAP | 5683 | UDP | Confirmable (CON) messages | UDP; DTLS not used in v2025.11 |

---

## Key OneM2M Concepts Used

| Concept | Abbreviation | Role in this project |
|---|---|---|
| Common Services Entity | CSE | ACME CSE — central message broker |
| Application Entity | AE | Android app on DJI RC |
| Container | CNT | Logical channel: telemetry / commands / ack |
| ContentInstance | CIN | Individual telemetry frame or command/ACK |
| Subscription | SUB | Push notification to AE or dashboard |

---

## Latency Measurement Methodology

### Scenario 1 — Telemetry uplink (device → CSE → dashboard)

```
t_send_ms   = System.currentTimeMillis() on Android at send time  (in CIN.con)
t_recv_ms   = time.time_ns()//1_000_000 on Streamlit at reception
latency_ms  = t_recv_ms - t_send_ms

Requires: NTP synchronisation between Android RC and dev machine.
Fallback: use seq gaps for relative packet loss without absolute latency.
```

### Scenario 2 — Command downlink (dashboard → CSE → device → ACK → dashboard)

```
t_cmd_ms    = int(time.time()*1000) on Streamlit at command send time (in CIN.con)
t_recv_ms   = System.currentTimeMillis() on Android at notification receipt (in ACK CIN)
latency_ms  = t_recv_ms - t_cmd_ms   (one-way: dashboard to app)

No NTP dependency: both timestamps are wall-clock, difference is meaningful
as long as the two devices are NTP-synced (typical LAN sync ≈ 1–10 ms).
```

### Packet loss

```
seq         = AtomicInteger counter in Android TelemetryManager, increments each send
              Gaps in seq on Streamlit side = dropped messages
              Resets per session (app restart), not per run
```
