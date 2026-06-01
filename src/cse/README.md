# ACME CSE — `src/cse/`

Docker Compose setup for **ACME oneM2M CSE v2025.11** used as middleware in the UxV benchmark.

---

## Stack

| Service | Image | Role |
|---|---|---|
| `acme-cse` | Custom (python:3.11-slim + acmecse==2025.11) | OneM2M CSE — HTTP, WebSocket, CoAP, MQTT client |
| `mosquitto` | eclipse-mosquitto:2 | MQTT broker — ACME CSE connects as a client |

> **ACME CSE is an MQTT client, not a broker.** Mosquitto provides the broker. Both Android RC and ACME CSE connect to it as clients.

---

## Ports

| Port | Protocol | Service |
|---|---|---|
| `8080/tcp` | HTTP | acme-cse — REST binding |
| `8180/tcp` | WebSocket | acme-cse — persistent bidirectional binding |
| `5683/udp` | CoAP | acme-cse — UDP; `/udp` suffix is mandatory in Docker |
| `1883/tcp` | MQTT | mosquitto — Android RC + Streamlit connect here |

---

## Quick Start

```bash
cd src/cse

# First time or after Dockerfile change
docker compose up --build

# Subsequent starts
docker compose up
```

Verify CSE is healthy (~45 s after start):

```bash
curl http://localhost:8080/id-in \
  -H "X-M2M-RI: test-001" \
  -H "X-M2M-Origin: CAdmin" \
  -H "X-M2M-RVI: 3" \
  -H "Accept: application/json"
# Expected: HTTP 200, body contains "ty": 5 and "csi": "/id-in"
```

Web UI (resource tree browser): `http://localhost:8080/webui`

---

## File Structure

```
src/cse/
├── Dockerfile              python:3.11-slim + iproute2 + acmecse==2025.11
├── docker-compose.yml      Two services: acme-cse + mosquitto
├── runACME.sh              Restart loop (exit 82 = CSE restart, not error)
├── config/
│   ├── acme.ini            ACME CSE config — mounted as volume; edit without rebuild
│   └── mosquitto.conf      Mosquitto config — anonymous, port 1883
└── patches/
    ├── WebSocketServer.py  Disables WS keepalive pings
    └── CoAPthonTools.py    Adds NOTIFY→POST to operationsMethodsMap
```

---

## Configuration

`config/acme.ini` is **mounted into the container** — edit and restart without rebuilding:

```bash
docker compose restart acme-cse
```

Key settings:

| Setting | Value | Notes |
|---|---|---|
| `cseID` | `id-in` | CSE-Base identifier (used only in AE registration `to` field) |
| `cseName` | `cse-in` | CSE-Base resource name (prefix for all child resource paths) |
| `httpPort` | `8080` | HTTP binding |
| `[websocket] port` | `8180` | WebSocket binding |
| `[coap] port` | `5683` | CoAP binding |
| `[mqtt] address` | `mosquitto` | Docker service name (internal DNS) |
| `[database.tinydb] path` | `/data/db` | Named Docker volume — persists across restarts |
| `allowedAEOriginators` | `C*,S*` | Android: `C`+serial; Streamlit: `CStreamlit` |
| `enableACPChecks` | `false` | All ACP checks disabled — controlled lab environment |
| `enableSubscriptionVerificationRequests` | `false` | Subscription vrq NOTIFY disabled (see Patches) |

---

## OneM2M Resource Tree

After Android connects and Streamlit subscribes (WS/MQTT example):

```
/id-in                                    ← CSE-Base (ty=5)
  └── /cse-in/uxv                         ← AE (ty=2) — Android registers this
        ├── /cse-in/uxv/telemetry         ← CNT (mni=10) — 250 ms telemetry CINs
        │     └── sub-streamlit-tel       ← SUB — Streamlit subscription
        ├── /cse-in/uxv/commands          ← CNT (mni=5)  — command CINs from Streamlit
        │     └── sub-commands            ← SUB — Android subscription
        └── /cse-in/uxv/ack              ← CNT (mni=200) — Android ACK CINs
              └── sub-streamlit-ack       ← SUB — Streamlit subscription
```

Resource names for Streamlit subscriptions vary by protocol:

| Protocol | Telemetry SUB rn | ACK SUB rn |
|---|---|---|
| WebSocket | `sub-streamlit-tel` | `sub-streamlit-ack` |
| MQTT | `sub-streamlit-tel` | `sub-streamlit-ack` |
| HTTP | `sub-http-streamlit-tel` | `sub-http-streamlit-ack` |
| CoAP | `sub-coap-streamlit-tel` | `sub-coap-streamlit-ack` |

---

## Patches

### `patches/WebSocketServer.py`

**Patches:** `acmecse/protocols/WebSocketServer.py`

**Problem:** ACME CSE v2025.11 sends periodic WebSocket ping frames to connected AEs. Under benchmark load (≥4 msg/s), the ping/pong cycle interleaved with in-flight request–response exchanges. Android's OkHttp3 WebSocket implementation closed the connection when it could not respond to a ping within the expected window.

**Fix:** Disabled the CSE-side keepalive ping scheduler. The WS connection remains open as long as messages flow (benchmark conditions ensure continuous traffic).

### `patches/CoAPthonTools.py`

**Patches:** `acmecse/helpers/CoAPthonTools.py`

**Problem:** ACME CSE v2025.11 uses CoAPthon3 internally to send CoAP messages. The `operationsMethodsMap` dictionary maps oneM2M operation codes to CoAP method codes. The `Operation.NOTIFY` entry was missing, causing a `KeyError` whenever the CSE attempted to deliver a CoAP notification to a subscriber.

**Fix:** Added `Operation.NOTIFY: defines.Codes.POST.number` to the map. CoAP NOTIFY is encoded as a CoAP POST, consistent with the oneM2M TS-0010 binding specification.

> **Note:** With `enableSubscriptionVerificationRequests=false` in `acme.ini`, the CSE does not send verification NOTIFYs on SUB creation. This makes the CoAPthonTools patch relevant only for actual subscription event notifications, and sidesteps a secondary issue (ACME CSE CoAPServer.py also lacked a NOTIFY handler in its routing table).

---

## Scenario 3 — Network Degradation

Inject packet loss **inside the `acme-cse` container** using `tc netem`. The container has `NET_ADMIN` capability and `iproute2` installed.

```bash
# Inject 5% packet loss on CSE egress
docker exec acme-cse tc qdisc add dev eth0 root netem loss 5%

# Inspect current qdisc
docker exec acme-cse tc qdisc show dev eth0

# Reset to normal
docker exec acme-cse tc qdisc del dev eth0 root
```

---

## Useful Commands

```bash
# Real-time CSE logs
docker compose logs -f acme-cse

# Restart CSE after editing acme.ini
docker compose restart acme-cse

# Stop (data volume preserved)
docker compose down

# Clean slate — removes all stored resources
docker compose down -v

# Shell in CSE container
docker exec -it acme-cse /bin/bash
```

---

## Startup Order

1. `docker compose up` from `src/cse/` — wait for CSE healthy on :8080
2. Launch Android app on DJI RC — registers AE and creates containers
3. `streamlit run app.py` from `src/frontend/` — subscribes to existing containers

> Streamlit must start **after** the Android app because `_ensure_subscription()` requires the CNT resources (`telemetry`, `ack`) to already exist in the CSE.

---

## References

- [ACME CSE GitHub](https://github.com/ankraft/ACME-oneM2M-CSE)
- `docs/architecture/system-overview.md` — full protocol flows and lab constraints
- `docs/ai-context/cse-dev.md` — development context and design decisions
- `docs/adr/002-cse-deployment.md` — rationale for Docker deployment
