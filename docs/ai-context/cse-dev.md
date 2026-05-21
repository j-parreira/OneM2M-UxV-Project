# Development Context — ACME CSE (`src/cse/`)

> Read this before touching anything in `src/cse/`. Complements `project-context.md` and
> `docs/adr/002-cse-deployment.md`.
>
> This file was substantially updated after end-to-end testing of the Android app against
> ACME CSE v2025.11. All protocol specifics reflect **empirically verified behaviour**.

---

## Runtime

ACME CSE runs in **Docker Compose** on the Windows 11 dev machine. It is never run as a bare
Python process on the host. See ADR-002 for the rationale.

```
src/cse/
├── Dockerfile          python:3.11-slim + iproute2 + acmecse==2025.11
├── docker-compose.yml  two services: acme-cse + mosquitto
├── runACME.sh          restart loop (exit 82 = CSE restart, not error)
├── config/
│   ├── acme.ini        main config — mounted as volume, edit without rebuild
│   └── mosquitto.conf  Mosquitto config — allow_anonymous, port 1883
├── README.md           startup instructions for humans
└── test_*.py           protocol connection test scripts (run inside container)
```

Config files are **mounted as a volume** — editing them does not require rebuilding the image.
After editing `acme.ini`, restart with:
```bash
docker compose restart acme-cse
```
After changing `cseID`, `serviceProviderID`, or `[cse.security]`, do a **clean restart**:
```bash
docker compose down -v && docker compose up
```

---

## ACME CSE Version

Current stable: **2025.11** (released 2025-11-24). Installed via `pip install acmecse==2025.11`
inside the container. Do not use an untagged/floating version — reproducibility is required
for a research paper.

**Python requirement:** 3.11 minimum (v2025.11 breaking change from 3.10).

---

## Protocols — Status and Port Map

| Protocol  | Port      | Status | Notes |
|-----------|-----------|--------|-------|
| HTTP      | 8080/tcp  | ✅ Verified | Standard OneM2M REST binding |
| WebSocket | 8180/tcp  | ✅ Verified | Android app connects here; see WebSocket section |
| MQTT      | 1883/tcp  | ✅ Verified | External Mosquitto broker — ACME CSE is MQTT client |
| CoAP      | 5683/udp  | ✅ Port open | Responds to UDP; requires oneM2M options in packets |

> **MQTT architecture:** ACME CSE connects *to* Mosquitto as a client (`MQTTClient.py`).
> Docker Compose includes a separate `mosquitto` service. ACME CSE's `[mqtt]` section
> sets `address=mosquitto` (Docker service name).

> **CoAP gotcha:** Docker requires explicit `/udp` suffix — `5683:5683/udp`.

> **CoAP DTLS:** Not fully implemented in v2025.11 (TODO in `CoAPServer.py`).
> Use `useDTLS=false` (default).

---

## URL Structure — IMPORTANT: /cse-in vs /id-in

**This differs from standard oneM2M documentation and is specific to ACME CSE v2025.11.**

In ACME CSE, the CSE-Base has two identifiers:
- `ri = "id-in"` (Resource ID = CSE-ID) → used for the CSE-Base endpoint: `GET /id-in`
- `rn = "cse-in"` (Resource Name = CSE-Base name) → prefix for ALL child resources

| Operation | Correct HTTP path | Wrong path |
|---|---|---|
| GET CSE-Base | `/id-in` | — |
| GET/POST AE | `/cse-in/{ae-rn}` | `/id-in/{ae-rn}` ← 404 |
| POST child container | `/cse-in/{ae-rn}/{cnt-rn}` | `/id-in/{ae-rn}/{cnt-rn}` ← 404 |
| POST CIN | `/cse-in/{ae-rn}/{cnt-rn}` | — |

**Why:** ACME CSE structures HTTP URLs as `/{cseName}/{child-rn}` for resources UNDER the
CSE-Base, where `cseName = "cse-in"`. The CSE-ID (`/id-in`) is only the root address.

Verified by discovery query: resources returned as `["cse-in/CAdmin", "cse-in/uxv", ...]`.

---

## Resource Tree

Expected tree after Android app connects (6-step registration sequence):

```
/id-in                          ← CSE-Base (CSE-ID, only for GET /id-in)
└── (accessed as /cse-in/...)   ← CSE-Base resource name prefix for child paths

HTTP paths:
  /cse-in/uxv                   ← AE (registered by Android app)
  /cse-in/uxv/telemetry         ← CNT (mni=10) — drone telemetry CINs
  /cse-in/uxv/commands          ← CNT (mni=5)  — command CINs from Streamlit
  /cse-in/uxv/commands/sub-commands  ← SUB — push notifications to Android app
  /cse-in/uxv/ack               ← CNT (mni=200) — command ACK CINs (Scenario 2)
```

**Data flows:**

```
Scenario 1 (uplink telemetry):
  Android → POST CIN /cse-in/uxv/telemetry
          → CSE notifies Streamlit via subscription

Scenario 2 (downlink commands + ACK):
  Streamlit → POST CIN /cse-in/uxv/commands
            → CSE notifies Android via subscription
            → Android executes command
            → Android → POST CIN /cse-in/uxv/ack
            → CSE notifies Streamlit

Streamlit subscribes: /cse-in/uxv/telemetry  and  /cse-in/uxv/ack
Streamlit publishes:  /cse-in/uxv/commands
```

---

## WebSocket Binding — Complete Integration Guide

This section documents all requirements discovered by testing against ACME CSE v2025.11.
**All points are empirically verified** — deviating from any of them will break the connection.

### 1. WebSocket upgrade requirements

The HTTP upgrade request MUST include:

```
Sec-WebSocket-Protocol: oneM2M.json
X-M2M-Origin: {aeOriginator}
```

- Without `Sec-WebSocket-Protocol`: HTTP 400 "Failed to open a WebSocket connection: missing subprotocol"
- Without `X-M2M-Origin`: All non-CAdmin requests return `rsc=4103` "Unknown WS connections (no X-M2M-Origin header) are only allowed for AE registrations"

In OkHttp (Android):
```java
Request request = new Request.Builder()
    .url("ws://host:8180")
    .addHeader("Sec-WebSocket-Protocol", "oneM2M.json")
    .addHeader("X-M2M-Origin", aeOriginator)   // "C" + serialNumber
    .build();
```

### 2. Request format — flat JSON (no m2m:rqp wrapper)

ACME CSE v2025.11 WebSocket binding uses **flat JSON** for all messages.
The `m2m:rqp`/`m2m:rsp` wrapper format is **not supported** on WebSocket.

```json
// CORRECT — flat format
{
  "op":  1,
  "to":  "cse-in/uxv/telemetry",
  "fr":  "C3LKFD12ABC",
  "rqi": "rqi-42",
  "rvi": "3",
  "ty":  4,
  "pc":  { "m2m:cin": { "con": "..." } }
}

// WRONG — wrapper format (causes rsc=4000 "to/target parameter is mandatory")
{ "m2m:rqp": { "op": 1, "to": "...", ... } }
```

### 3. The `to` field format

| Target | Correct `to` value | Notes |
|---|---|---|
| AE registration (CSE-Base) | `"id-in"` | CSE-relative ri, NO leading slash |
| Any resource under AE | `"cse-in/{ae-rn}/..."` | CSE-Base rn prefix, NO leading slash |

Wrong `to` values and their errors:
- `"/id-in"` → `rsc=4000` "ID too short: /id-in. Must be /<cseid>/<structured|unstructured>"
- `"/cse-in/uxv"` → `rsc=4000` same error (leading slash makes it SP-relative = too short)
- `"//uxv.benchmark/id-in"` → `rsc=4000` same error

### 4. Mandatory field: `rvi`

Every request MUST include `"rvi": "3"` (Release Version Indicator).
Without it: `rsc=4000` "release Version Indicator is missing".

### 5. Response and notification detection

Responses and notifications are also flat JSON:
```python
# Response: has "rsc" at top level
if obj.has("rsc"):
    handle_response(obj)

# Notification: has "op" = 5 at top level
elif obj.optInt("op") == 5:
    handle_notification(obj)
    send_ack(obj.optString("rqi"))
```

Notification structure (flat):
```json
{
  "op": 5,
  "to": "C3LKFD12ABC",
  "fr": "/id-in",
  "rqi": "notif-xyz",
  "pc": {
    "m2m:sgn": {
      "sur": "cse-in/uxv/commands/sub-commands",
      "nev": {
        "rep": { "m2m:cin": { "con": "{\"command\": \"takeoff\"}" } },
        "net": 3
      }
    }
  }
}
```

Verification request (sent after subscription creation):
```json
{ "op": 5, "rqi": "vrq-xyz", "pc": { "m2m:sgn": { "vrq": true, "sur": "..." } } }
```
Must be ACKed with a flat response.

### 6. ACK format — also flat

```json
// CORRECT — flat ACK
{ "rsc": 2000, "rqi": "notif-xyz", "to": "C3LKFD12ABC", "fr": "C3LKFD12ABC" }

// WRONG — wrapper format
{ "m2m:rsp": { "rsc": 2000, ... } }
```

### 7. AE registration specifics

```json
{
  "op":  1,
  "to":  "id-in",
  "fr":  "C3LKFD12ABC",
  "rqi": "rqi-1",
  "rvi": "3",
  "ty":  2,
  "pc": {
    "m2m:ae": {
      "rn":  "uxv",
      "api": "N.com.uxv.onem2m",
      "srv": ["3"],
      "rr":  true,
      "poa": ["ws://192.168.1.100:8180"]
    }
  }
}
```

**`aei` MUST be omitted** — it is a non-provision attribute in v2025.11. Including it causes
`rsc=4000` "found non-provision attribute: aei". The CSE assigns `aei = originator (fr)` automatically.

**`poa` is REQUIRED** — Without `poa`, subscription notifications are **silently discarded**.
The CSE resolves the `nu` field to a notification route via the AE's `poa`. With `poa=None`,
the CSE logs "Resource has no poa attribute" and returns an empty delivery list.

The `poa` value must be the CSE's own WebSocket address (`ws://{cse-host}:{ws-port}`).
When the CSE tries to deliver to `poa=['ws://host:8180']`, it finds the existing WS connection
(associated by originator) and reuses it instead of opening a new one.

### 8. Subscription specifics

```json
"m2m:sub": {
  "rn":  "sub-commands",
  "nu":  ["C3LKFD12ABC"],
  "enc": { "net": [3] }
}
```

- **`nct` MUST be omitted** — `nct=2` combined with `net=[3]` causes `rsc=4000`
  "nct=2 is not allowed for one or more values in enc/net=[3]"
- **`nu` must be the AE originator** (e.g. `"C3LKFD12ABC"`), not the AE resource URI
  (`/cse-in/uxv`). ACME CSE looks up the `nu` value in the AE's resource → gets its `poa`
  → routes the notification. Using the resource URI works the same way, but the originator
  is the simpler form.

### 9. ContentInstance (CIN) specifics

```json
"m2m:cin": {
  "con": "{\"lat\": 39.933, \"seq\": 1}"
}
```

**`cnf` MUST be omitted** — `"cnf": "application/json"` causes `rsc=4000`
"validation of cnf attribute failed: application/json". Use the default by not specifying `cnf`.

---

## MQTT Topic Structure

Verified by end-to-end test (paho-mqtt 2.1.0 → mosquitto broker → ACME CSE MQTT client):

| Direction | Topic | Example |
|---|---|---|
| AE → CSE (request) | `/oneM2M/req/{originator}/{cseID}/json` | `/oneM2M/req/C3LKFD12/id-in/json` |
| CSE → AE (response) | `/oneM2M/resp/{originator}/{cseID}/json` | `/oneM2M/resp/C3LKFD12/id-in/json` |
| AE → CSE (registration) | `/oneM2M/reg_req/{originator}/{cseID}/json` | — |
| CSE → AE (notification) | `/oneM2M/req/{cseID}/{originator}/json` | `/oneM2M/req/id-in/C3LKFD12/json` |

> **Note on response topic:** the format is `/oneM2M/resp/{originator}/{cseID}/json`
> (originator FIRST), NOT `/oneM2M/resp/{cseID}/{originator}/json`.
> This was empirically verified — earlier documentation had the order reversed.

**Android MqttProtocolClient must subscribe to:**
- `/oneM2M/resp/{aeOriginator}/{cseID}/#` — for responses to its requests
- `/oneM2M/req/{cseID}/{aeOriginator}/#` — for command notifications

**Topic prefix:** optional `topicPrefix` in `[mqtt]` for multi-CSE deployments (leave empty).

---

## MQTT over WebSocket (v2025.11 new feature)

New `[mqtt.websocket]` section enables MQTT protocol over WebSocket on a separate port
(default 9001). Not currently used in the benchmark.

```ini
; [mqtt.websocket]
; enable = true
; port   = 9001
```

---

## Access Control

ACME CSE v2025.11 does **not** automatically create an ACP (Access Control Policy) when an AE
registers. Without an ACP, all non-CAdmin requests to the AE's own resource subtree return
`rsc=4103` "The Originator does not have permission to access this resource."

**Benchmark solution:** disable ACP checks entirely in `[cse.security]`:

```ini
[cse.security]
enableACPChecks = false
```

This is intentional for a controlled lab environment. Do not use in production.

After adding this setting, do a **clean restart** (`docker compose down -v && up`) so the
TinyDB is rebuilt without stale ACPs.

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
cseHost           = ${hostIPAddress}  ; NO inline comments on this line
httpPort          = 8080
logLevel          = info
databaseType      = tinydb

[cse]
defaultSerialization             = json
asyncSubscriptionNotifications   = true
enableSubscriptionVerificationRequests = true

[cse.security]
; Disable ACP checks — no ACP auto-created for AEs in v2025.11
enableACPChecks  = false

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
> value. `cseHost = ${hostIPAddress} ; comment` produces `poa: ["http://host ; comment:8080"]`.
> Always put comments on separate lines.

---

## Docker Compose Structure

```yaml
services:
  mosquitto:
    image: eclipse-mosquitto:2
    ports:
      - "1883:1883"
    volumes:
      - ./config/mosquitto.conf:/mosquitto/config/mosquitto.conf

  acme-cse:
    build: .
    depends_on:
      mosquitto:
        condition: service_healthy
    ports:
      - "8080:8080"         # HTTP
      - "8180:8180"         # WebSocket
      - "5683:5683/udp"     # CoAP — /udp suffix is mandatory
    volumes:
      - ./config/acme.ini:/data/acme.ini
      - acme-data:/data/db
    cap_add:
      - NET_ADMIN           # for tc netem (Scenario 3)

volumes:
  acme-data:
```

---

## Health Check

```bash
curl http://localhost:8080/id-in \
  -H "X-M2M-RI: test-001" \
  -H "X-M2M-Origin: CAdmin" \
  -H "X-M2M-RVI: 3" \
  -H "Accept: application/json"
```

Expected: HTTP 200, body contains `"ty": 5` (CSE-Base) and `"csi": "/id-in"`.

> Path is `/{cseID}` = `/id-in`. The old path `/onem2m` does not exist.
> All three headers are mandatory — plain GET returns 422 "request identifier is mandatory".

---

## Scenario 3 — Network Degradation

`tc netem` runs **inside the container** before each degraded-network run:

```bash
# inject 5% packet loss
docker exec acme-cse tc qdisc add dev eth0 root netem loss 5%

# reset after run
docker exec acme-cse tc qdisc del dev eth0 root
```

The container has `iproute2` installed and `--cap-add NET_ADMIN`.

---

## Test Scripts

Protocol validation scripts in `src/cse/`, runnable inside the container:

```bash
# Copy and run
docker cp src/cse/test_ws_flow.py acme-cse:/x.py && docker exec acme-cse python3 /x.py
```

| Script | What it tests | Expected result |
|---|---|---|
| `test_ws_flow.py` | Full 9-step WebSocket OneM2M session | 9/9 steps pass |
| `test_http_paths.py` | HTTP URL structure discovery | Shows correct /cse-in/ paths |
| `test_connections.py` | All 4 protocols (HTTP, WS, MQTT, CoAP) | 4/4 connected |
| `test_nct_cnf.py` | Valid nct/cnf values for v2025.11 | Both omitted |
| `test_notif*.py` | WebSocket notification debugging | Historical — poa fix resolved it |

The `test_ws_flow.py` script is the integration smoke test — it simulates the exact
Android app OneM2M session and verifies the full command notification loop.

---

## v2025.11 Breaking Changes (relevant to this project)

| Change | Impact | Fix |
|---|---|---|
| `aei` is non-provision attribute | `rsc=4000` if included in AE body | Remove `aei` from body |
| Default release version changed to 5 | `rvi="3"` must be explicit | Add `"rvi":"3"` to all WS requests |
| ACP not auto-created for AEs | `rsc=4103` for all AE operations | `enableACPChecks=false` in acme.ini |
| Flat WS format required | `m2m:rqp` wrapper fails silently | Use flat JSON throughout |
| `X-M2M-Origin` required in WS upgrade | `rsc=4103` without it | Header in WebSocket upgrade |
| `cnf='application/json'` fails | `rsc=4000` on CIN creation | Omit `cnf` from CIN body |
| `nct=2` + `net=[3]` invalid | `rsc=4000` on SUB creation | Omit `nct` from SUB body |
| `poa` required for WS notifications | Notifications silently discarded | Set `poa=['ws://host:port']` in AE |
| TinyDB filename uses serviceProviderID | Filename changes if spID not set | Set `serviceProviderID` in basic.config |
| ACP name changed to `acpCreateRootResources` | (already reflected in acpi field) | No action needed |
