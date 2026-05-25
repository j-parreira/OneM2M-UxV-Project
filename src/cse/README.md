# ACME CSE — `src/cse/`

Docker Compose setup for the **ACME oneM2M CSE v2025.11** used as the middleware in the UxV benchmark.

---

## Stack

| Service | Image | Role |
|---|---|---|
| `acme-cse` | Custom (python:3.11-slim + acmecse==2025.11) | OneM2M CSE — HTTP, WebSocket, CoAP, MQTT client |
| `mosquitto` | eclipse-mosquitto:2 | MQTT broker — ACME CSE connects as a client |

> **ACME CSE is an MQTT client, not a broker.** The Mosquitto service provides the broker that both ACME CSE and the Android RC connect to.

---

## Ports

| Port | Protocol | Service | Notes |
|---|---|---|---|
| `8080/tcp` | HTTP | acme-cse | OneM2M REST binding |
| `8180/tcp` | WebSocket | acme-cse | Android app connects here |
| `5683/udp` | CoAP | acme-cse | `/udp` suffix is mandatory in Docker |
| `1883/tcp` | MQTT | mosquitto | Android RC connects here for MQTT binding |

---

## Quick Start

```bash
cd src/cse

# First time or after Dockerfile change
docker compose up --build

# Subsequent starts (no rebuild needed)
docker compose up
```

Wait ~45 s for the CSE to initialise, then verify:

```bash
curl http://localhost:8080/id-in \
  -H "X-M2M-RI: test-001" \
  -H "X-M2M-Origin: CAdmin" \
  -H "X-M2M-RVI: 3" \
  -H "Accept: application/json"
# Expected: HTTP 200, body contains "ty": 5 and "csi": "/id-in"
```

Open the ACME CSE web UI (resource tree browser):

```
http://localhost:8080/webui
```

---

## File Structure

```
src/cse/
├── Dockerfile              python:3.11-slim + iproute2 + acmecse==2025.11
├── docker-compose.yml      Two services: acme-cse + mosquitto
├── runACME.sh              Restart loop (exit code 82 = CSE restart, not error)
└── config/
    ├── acme.ini            ACME CSE config — mounted as volume, edit without rebuild
    └── mosquitto.conf      Mosquitto config — anonymous, port 1883
```

---

## Configuration

`config/acme.ini` is **mounted into the container** — edit it and restart without rebuilding the image:

```bash
# Edit config
notepad config\acme.ini

# Apply changes
docker compose restart acme-cse
```

Key settings:

| Setting | Value | Notes |
|---|---|---|
| `cseID` | `id-in` | CSE-Base path is `/id-in` |
| `cseName` | `cse-in` | Human-readable name |
| `httpPort` | `8080` | HTTP binding |
| `[websocket] port` | `8180` | WebSocket binding |
| `[coap] port` | `5683` | CoAP binding |
| `[mqtt] address` | `mosquitto` | Docker service name (internal DNS) |
| `[database.tinydb] path` | `/data/db` | Named Docker volume |
| `allowedAEOriginators` | `C*,S*` | Android app uses `C` + serialNumber |

---

## OneM2M Resource Tree

After the Android app connects and registers, the tree looks like:

```
/id-in                      ← CSE-Base
└── uxv                     ← AE (registered by Android app)
    ├── telemetry           ← CNT — drone telemetry (every 250 ms by default)
    ├── commands            ← CNT — commands from Streamlit dashboard
    │   └── sub-commands    ← SUB — push notification to Android app
    └── ack                 ← CNT — command receipt timestamps (Scenario 2)
```

The Android app creates all resources except the CSE-Base. Conflict (HTTP 4105) is treated as success — reconnecting the app does not require clearing the CSE.

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

The Streamlit orchestrator (`src/frontend/core/orchestrator.py`) calls these commands via `subprocess` before and after each Scenario 3 run.

---

## Health Check

The `acme-cse` container has a built-in Docker healthcheck:

```bash
# Check via Docker
docker compose ps

# Manual check
curl http://localhost:8080/id-in -H "X-M2M-Origin: CAdmin" -H "X-M2M-RI: t" -H "X-M2M-RVI: 3"
```

Expected response body contains `"ty": 5` (resource type = CSE-Base).

---

## Useful Commands

```bash
# View CSE logs in real time
docker compose logs -f acme-cse

# Restart only the CSE (after editing acme.ini)
docker compose restart acme-cse

# Stop everything and remove containers (data volume preserved)
docker compose down

# Stop and destroy all data (clean slate)
docker compose down -v

# Open a shell in the CSE container (for debugging)
docker exec -it acme-cse /bin/bash
```

---

## Startup Order (full system)

1. `docker compose up` from `src/cse/` — wait for CSE healthy on :8080
2. `streamlit run app.py` from `src/frontend/` (venv active)
3. Launch Android app on DJI RC — registers as AE on startup
4. Power on drone before issuing flight commands

---

## References

- [ACME CSE GitHub](https://github.com/ankraft/ACME-oneM2M-CSE)
- [ACME CSE v2025.11 release notes](https://github.com/ankraft/ACME-oneM2M-CSE/releases/tag/2025.11)
- `docs/ai-context/cse-dev.md` — full development context and design decisions
- `docs/adr/002-cse-deployment.md` — rationale for Docker deployment
