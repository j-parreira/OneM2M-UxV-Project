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

Pin the version at setup time. Check the latest stable tag at
`https://github.com/ankraft/ACME-oneM2M-CSE/releases` and record it in the `Dockerfile`.
Do not use `latest` or an untagged image — reproducibility is required for a research paper.

---

## Protocols to Enable

All four protocols must be enabled in `acme.ini`. The CSE listens on:

| Protocol  | Port      | Notes |
|-----------|-----------|-------|
| HTTP      | 8080/tcp  | Default OneM2M REST binding |
| MQTT      | 1883/tcp  | ACME CSE has built-in MQTT broker (Mosquitto not needed separately) |
| WebSocket | 8180/tcp  | ACME CSE WebSocket binding (use 8180 to avoid conflict with host :80) |
| CoAP      | 5683/udp  | Must be exposed as UDP in `docker-compose.yml` — easy to miss |

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

```ini
[cse]
type            = IN                  ; Infrastructure Node
cseId           = /id-in
cseName         = cse-in
defaultSerialization = JSON

[server.http]
port            = 8080
listenIF        = 0.0.0.0             ; must listen on all interfaces inside container

[server.mqtt]
enable          = true
port            = 1883

[server.websocket]
enable          = true
port            = 8180

[server.coap]
enable          = true
port            = 5683

[database]
type            = tinydb              ; file-based, no external DB needed
path            = ./data              ; persisted in a Docker volume
```

> Parameters not listed here: use ACME CSE defaults unless a specific reason to change.

---

## Docker Compose Port Mapping

All ports must be mapped to the host so the Android RC (on same LAN) can reach the CSE:

```yaml
ports:
  - "8080:8080"        # HTTP
  - "1883:1883"        # MQTT
  - "8180:8180"        # WebSocket
  - "5683:5683/udp"    # CoAP — UDP explicit
```

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
