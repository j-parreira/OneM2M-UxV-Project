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

> **CoAP DTLS:** Not fully implemented in v2025.11 (TODO comments in `CoAPServer.py`).
> Use `useDTLS=false` (default). Do not configure CoAP certificates.

---

## MQTT Topic Structure

Required for implementing `MqttProtocolClient` on Android. All topics use JSON serialisation.

| Direction | Topic | Example |
|---|---|---|
| AE → CSE (request) | `/oneM2M/req/{originator}/{cseID}/json` | `/oneM2M/req/C3LKFD12/id-in/json` |
| CSE → AE (response) | `/oneM2M/resp/{cseID}/{originator}/json` | `/oneM2M/resp/id-in/C3LKFD12/json` |
| AE registration | `/oneM2M/reg_req/{originator}/{cseID}/json` | `/oneM2M/reg_req/C3LKFD12/id-in/json` |
| CSE → AE (notification) | `/oneM2M/req/{cseID}/{originator}/json` | `/oneM2M/req/id-in/C3LKFD12/json` |

The Android `MqttProtocolClient` must **subscribe to** `/oneM2M/req/{cseID}/{aeOriginator}/#`
to receive command notifications. The `nu` field in subscriptions must be the MQTT topic URI:
`mqtt://{broker}:{port}/oneM2M/req/{cseID}/{aeOriginator}/json`
or simply the originator (ACME CSE resolves it internally).

**Topic prefix:** optional `topicPrefix` in `[mqtt]` for multi-CSE deployments (leave empty for single-CSE benchmark).

---

## WebSocket Subscription — `nu` Field

**Critical:** the `nu` (notification URI) in a subscription must be the **AE originator**
(e.g. `C3LKFD12ABC`), NOT the AE resource URI (`/id-in/uxv`).

ACME CSE associates WebSocket connections by originator. When a notification fires,
it looks up the connection whose originator matches `nu`. Using the AE resource URI
results in the subscription being created successfully but **notifications never delivered**.

```java
// CORRECT — in OneM2MSession.createSubscription()
.put("nu", new JSONArray().put(aeOriginator))   // e.g. "C3LKFD12ABC"

// WRONG — was incorrectly used before the fix
.put("nu", new JSONArray().put(CSE_BASE + "/" + AE_NAME))  // "/id-in/uxv"
```

**Subscription verification:** when `enableSubscriptionVerificationRequests=true` (default),
the CSE sends a NOTIFY with `vrq=true` immediately after subscription creation. The Android
app's `sendNotifyAck()` handles this correctly — it ACKs any `op=5` NOTIFY regardless of
whether it contains `nev` (event data) or just `vrq` (verification).

---

## MQTT over WebSocket (v2025.11 new feature)

New `[mqtt.websocket]` section enables MQTT protocol over WebSocket transport on a separate port (default 9001). Useful for clients that can only use WebSocket but want MQTT pub/sub semantics. Not currently used in the benchmark but available if needed.

```ini
; Uncomment to enable MQTT over WebSocket
; [mqtt.websocket]
; enable = true
; port   = 9001
```

---

## Resource Tree

The CSE-Base is configured in `acme.ini`. The Android app (AE) registers itself on startup
— no need to pre-create the AE in config. The containers under the AE are created by the
app after registration.

Expected tree after Android app connects (6-step registration sequence):

```
/id-in                         ← CSE-Base (cseID em acme.ini)
└── uxv                        ← AE (registado pela app no arranque)
    ├── telemetry              ← CNT (mni=10) — CINs de telemetria a cada intervalMs
    ├── commands               ← CNT (mni=5)  — CINs de comandos do Streamlit
    │   └── sub-commands       ← SUB — notificação ao AE quando novo CIN chega
    └── ack                    ← CNT (mni=200) — CINs de ACK da app (Cenário 2)
```

**Fluxo de telemetria (Cenário 1 — uplink):**
`App → POST CIN /id-in/uxv/telemetry` → CSE notifica Streamlit via subscrição

**Fluxo de comandos (Cenário 2 — downlink):**
`Streamlit → POST CIN /id-in/uxv/commands` → CSE notifica App → App executa + ACK → `POST CIN /id-in/uxv/ack` → CSE notifica Streamlit

O Streamlit subscreve `/id-in/uxv/telemetry` e `/id-in/uxv/ack`; publica em `/id-in/uxv/commands`.

---

## Key `acme.ini` Sections

Section names must be exactly as listed — they differ from what is intuitive.
See `src/cse/config/acme.ini` for the full annotated configuration.

```ini
[basic.config]
cseType           = IN
cseID             = id-in
cseName           = cse-in
serviceProviderID = //uxv.benchmark   ; affects TinyDB filename in v2025.11
adminID           = CAdmin
dataDirectory     = ${baseDirectory}  ; resolves to /data (from -dir /data)
networkInterface  = 0.0.0.0
cseHost           = ${hostIPAddress}  ; NO inline comments on this line — parser bug
httpPort          = 8080
logLevel          = info
databaseType      = tinydb

[cse]
defaultSerialization             = json
asyncSubscriptionNotifications   = true
enableSubscriptionVerificationRequests = true

[http]
port             = ${basic.config:httpPort}
listenIF         = ${basic.config:networkInterface}
address          = http://${basic.config:cseHost}:${basic.config:httpPort}
enableManagementEndpoint = true

[mqtt]
enable           = true
address          = mosquitto
port             = 1883

[websocket]
enable           = true
port             = 8180
listenIF         = 0.0.0.0
address          = ws://${basic.config:cseHost}:8180

[coap]
enable                  = true
port                    = 5683
listenIF                = ${basic.config:networkInterface}
address                 = coap://${basic.config:cseHost}:5683
clientConnectionCacheSize = 200

[database]
type             = ${basic.config:databaseType}

[database.tinydb]
path             = /data/db
writeDelay       = 10

[cse.registration]
allowedAEOriginators  = C*,S*,/id-in/C*
allowedCSROriginators = /id-mn

[textui]
startWithTUI     = False
```

> **Parser gotcha:** inline comments (`;`) on the same line as a value are included in the
> value by ACME CSE's config parser. `cseHost = ${hostIPAddress} ; comment` produces
> `poa: ["http://172.18.0.3 ; comment:8080"]`. Always put comments on separate lines.

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

The CSE-Base is accessible at `GET http://localhost:8080/id-in` (path = `/{cseID}`).
OneM2M HTTP requests require three mandatory headers:

```bash
curl http://localhost:8080/id-in \
  -H "X-M2M-RI: test-001" \
  -H "X-M2M-Origin: CAdmin" \
  -H "X-M2M-RVI: 3" \
  -H "Accept: application/json"
```

Expected response: HTTP 200 with body containing `"ty": 5` (CSE-Base resource type)
and `"csi": "/id-in"`.

> The old path `/onem2m` was incorrect — the correct path is `/{cseID}` = `/id-in`.

---

## What Depends on the Android App

Once the Android app is reviewed:
- Confirm which protocols are already implemented on the app side
- Align the AE name and container paths (the app registers with a specific AE name)
- Check if the app expects the CSE at a specific URL path prefix

Do not hardcode the CSE IP in config — the Android app connects to a configurable IP.
