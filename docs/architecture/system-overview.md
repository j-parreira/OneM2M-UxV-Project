# System Architecture Overview

## OneM2M Resource Tree

```
/onem2m
└── uxv                          (AE — Application Entity, registered by Android app)
    ├── telemetry                (cnt — Container)
    │   ├── gps                  (cnt)
    │   │   └── <cin>            (contentInstance — GPS coords, heading)
    │   ├── battery              (cnt)
    │   │   └── <cin>            (contentInstance — voltage, percentage)
    │   └── flight               (cnt)
    │       └── <cin>            (contentInstance — altitude, speed, status)
    └── commands                 (cnt — Container)
        └── <cin>                (contentInstance — command payloads)
```

## Data Flow (Command Path)

```
Dashboard (Streamlit)
  │  POST /onem2m/uxv/commands  [HTTP / CoAP / MQTT / WS]
  ▼
ACME CSE
  │  Subscription notification to AE
  ▼
ACMECSE Android App (on DJI RC)
  │  DJI SDK v4 command call
  ▼
DJI Mavic 2 Enterprise Advanced (M2EA)
  │  ACK / status update
  ▼
ACMECSE Android App
  │  POST /onem2m/uxv/telemetry/flight
  ▼
ACME CSE
  │  Subscription notification to Dashboard
  ▼
Dashboard  ← latency measured here (t_ACK - t_command)
```

## Data Flow (Telemetry Path)

```
DJI M2EA
  │  Status broadcast (DJI SDK callbacks)
  ▼
ACMECSE Android App
  │  POST /onem2m/uxv/telemetry/<type>  [protocol under test]
  ▼
ACME CSE  (stores contentInstance)
  │  Subscription notification
  ▼
Dashboard  (displays live data, logs to data/raw/)
```

## Network Topology (Lab Setup)

```
[DJI RC / Android] ──WiFi──▶ [Router / Hotspot]
                                    │
                              [Dev Machine — Windows 11]
                              ├── Docker Container: ACME CSE
                              │     :8080 HTTP
                              │     :1883 MQTT
                              │     :5683/udp CoAP
                              │     :80 WebSocket
                              │     (tc netem runs inside container for Scenario 3)
                              └── Streamlit Dashboard  (:8501)
                                    (Python venv on host)
```

The Android app is configured with the dev machine's LAN IP via a settings screen.
No IPs are hardcoded in the app or in configuration files committed to the repo.

## Key OneM2M Concepts Used

| Concept | Abbreviation | Role in this project |
|---------|-------------|---------------------|
| Common Services Entity | CSE | ACME CSE — central broker |
| Application Entity | AE | ACMECSE Android app |
| Container | cnt | Logical channel (telemetry/commands) |
| ContentInstance | cin | Individual data point or command |
| Subscription | sub | Notifies AE or Dashboard of new data |
