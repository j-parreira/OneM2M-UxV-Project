# Development Context — ACME CSE (`src/cse/`)

> Read this before touching anything in `src/cse/`. Complements `project-context.md` and
> `docs/adr/002-cse-deployment.md`.

---

## Runtime

ACME CSE runs in **Docker Compose** on the Windows 11 dev machine. It is never run as a bare
Python process on the host. See ADR-002 for the rationale.

```
src/cse/
├── Dockerfile
├── docker-compose.yml
├── config/
│   ├── acme.ini          ← main ACME CSE configuration (mounted as volume)
│   └── init.py           ← optional: resource tree initialisation script
└── README.md             ← startup instructions (for humans)
```

Config files are **mounted as a volume** — editing them does not require rebuilding the image.

---

## ACME CSE Version

Current stable: **2025.11** (released 2025-11-24). Installed via `pip install acmecse` inside
the container — no custom image registry needed. Pin this version in the `Dockerfile` with
`RUN pip3 install acmecse==2025.11` once a `requirements.txt` pin is not enough. Do not use
an untagged/floating version — reproducibility is required for a research paper.

---

## Protocols to Enable

All four protocols must be enabled in `acme.ini`. The CSE listens on:

| Protocol  | Port      | Notes |
|-----------|-----------|-------|
| HTTP      | 8080/tcp  | Default OneM2M REST binding |
| MQTT      | 1883/tcp  | **External Mosquitto broker** — ACME CSE is an MQTT *client*, not a broker |
| WebSocket | 8180/tcp  | ACME CSE WebSocket binding (use 8180 to avoid conflict with host :80) |
| CoAP      | 5683/udp  | Must be exposed as UDP in `docker-compose.yml` — easy to miss |

> **MQTT architecture:** ACME CSE connects *to* a Mosquitto broker as a client (`MQTTClient.py`).
> The Docker Compose must include a separate `mosquitto` service. ACME CSE's `[mqtt]` section
> sets `address=mosquitto` (Docker service name) and `enable=true`.

> **CoAP gotcha:** Docker requires explicit `/udp` suffix — `5683:5683/udp`. Without it,
> only TCP is exposed and CoAP silently fails.

---

## Resource Tree

The CSE-Base is configured in `acme.ini`. The Android app (AE) registers itself on startup
— no need to pre-create the AE in config. The containers under the AE are created by the
app after registration.

Expected tree after Android app connects:

```
/onem2m                        ← CSE-Base (configured in acme.ini)
└── uxv                        ← AE (registered by Android app)
    ├── telemetry
    │   ├── gps
    │   ├── battery
    │   └── flight
    └── commands
```

The Streamlit dashboard subscribes to `uxv/telemetry/#` and publishes to `uxv/commands`.

---

## Key `acme.ini` Sections

The official config uses `[basic.config]` as a shared variable block, then per-protocol
sections. Section names differ from what is intuitive — use exactly these names.

```ini
[basic.config]
cseType         = IN
cseID           = id-in
cseName         = cse-in
adminID         = CAdmin
networkInterface = 0.0.0.0
cseHost         = ${hostIPAddress}    ; resolved at runtime by ACME CSE
httpPort        = 8080
logLevel        = info
databaseType    = tinydb
consoleTheme    = light

[http]
; section name is [http], NOT [server.http]
port            = ${basic.config:httpPort}
listenIF        = ${basic.config:networkInterface}
address         = http://${basic.config:cseHost}:${basic.config:httpPort}
enableManagementEndpoint = true

[mqtt]
; section name is [mqtt], NOT [server.mqtt]
; ACME CSE is an MQTT CLIENT — set address to the Mosquitto broker service name
enable          = true
address         = mosquitto           ; Docker Compose service name for the broker
port            = 1883

[websocket]
; section name is [websocket], NOT [server.websocket]
enable          = true
port            = 8180
listenIF        = 0.0.0.0
address         = ws://${basic.config:cseHost}:8180

[coap]
; section name is [coap], NOT [server.coap]
enable          = true
port            = 5683
listenIF        = ${basic.config:networkInterface}
address         = coap://${basic.config:cseHost}:5683

[database]
type            = ${basic.config:databaseType}

[database.tinydb]
; file-based DB, persisted in a Docker volume mounted at /data
path            = /data/db

[textui]
startWithTUI    = False               ; required for headless Docker operation
```

> Parameters not listed here: use ACME CSE defaults unless a specific reason to change.

---

## Docker Compose Structure

Two services are required: `acme-cse` and `mosquitto` (MQTT broker).

```yaml
services:
  mosquitto:
    image: eclipse-mosquitto:2
    ports:
      - "1883:1883"    # MQTT — mapped for Android RC on LAN
    volumes:
      - ./config/mosquitto.conf:/mosquitto/config/mosquitto.conf

  acme-cse:
    build: .           # uses src/cse/Dockerfile
    depends_on:
      - mosquitto
    ports:
      - "8080:8080"    # HTTP
      - "8180:8180"    # WebSocket
      - "5683:5683/udp"  # CoAP — UDP explicit, easy to miss
    volumes:
      - ./config/acme.ini:/data/acme.ini   # config mounted, no rebuild needed
      - acme-data:/data/db                  # persistent TinyDB volume
    cap_add:
      - NET_ADMIN      # required for tc netem (Scenario 3)

volumes:
  acme-data:
```

### Dockerfile (official pattern, pinned version)

```dockerfile
FROM python:3.11
RUN apt-get update && apt-get install -y iproute2   # for tc netem (Scenario 3)
RUN pip3 install acmecse==2025.11
RUN mkdir -p /data
COPY config/acme.ini /data/acme.ini
COPY runACME.sh /runACME.sh
EXPOSE 8080/tcp 8180/tcp 5683/udp
CMD ["/bin/sh", "/runACME.sh"]
```

> **Port 1883 is exposed by the `mosquitto` service, not by `acme-cse`.** All four protocol
> ports are reachable from the Android RC because they are mapped to the host.

---

## Scenario 3 — Network Degradation

`tc netem` runs **inside the container** before each degraded-network run. The Streamlit
orchestrator issues the command via `docker exec`:

```bash
# inject 5% packet loss
docker exec acme-cse tc qdisc add dev eth0 root netem loss 5%

# reset after run
docker exec acme-cse tc qdisc del dev eth0 root
```

The container image must include `iproute2` (`apt-get install -y iproute2` in Dockerfile)
and run with `--cap-add NET_ADMIN` in `docker-compose.yml`.

---

## Health Check

The CSE exposes a REST endpoint at `GET http://localhost:8080/onem2m` that returns the
CSE-Base resource. Use this as the Docker health check and as the integration smoke test.

Expected response: HTTP 200 with `Content-Type: application/json` and a body containing
`"ty": 5` (CSE-Base resource type).

---

## What Depends on the Android App

Once the Android app is reviewed:
- Confirm which protocols are already implemented on the app side
- Align the AE name and container paths (the app registers with a specific AE name)
- Check if the app expects the CSE at a specific URL path prefix

Do not hardcode the CSE IP in config — the Android app connects to a configurable IP.
