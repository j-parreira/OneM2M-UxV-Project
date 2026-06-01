# System Architecture — OneM2M UxV Benchmark

> Reference document for the MDPI paper.
> Reflects the empirically verified ACME CSE v2025.11 implementation.
> Last updated: 2026-06-01.

---

## 1. System Overview

The benchmark system comprises three distinct components connected via a local-area WiFi network.

```
┌──────────────────────────────────────────────────┐
│  DJI RC (Android — src/android/)                 │
│  OneM2MSession + {WS|MQTT|HTTP|CoAP}ProtocolClient│
│  TelemetryManager (250 ms timer)                 │
│  FlightManager (DJI SDK v4 commands)             │
└──────────────┬───────────────────────────────────┘
               │ LAN WiFi — 192.168.1.83
               │ Protocol: WS / MQTT / HTTP / CoAP/UDP
┌──────────────▼───────────────────────────────────┐
│  ACME CSE v2025.11 — Docker Compose (src/cse/)   │
│  ├── acme-cse  :8080(HTTP) :8180(WS) :5683(CoAP) │
│  └── mosquitto :1883(MQTT)                       │
└──────────────┬───────────────────────────────────┘
               │ LAN — 192.168.1.71 (host.docker.internal for TCP)
               │ Protocol: WS / MQTT / HTTP / HTTP(CoAP workaround)
┌──────────────▼───────────────────────────────────┐
│  Streamlit Dashboard (src/frontend/)             │
│  {WS|MQTT|HTTP|CoAP}Client                      │
│  Callback HTTP server — 0.0.0.0:8090             │
└──────────────────────────────────────────────────┘
```

**Component roles:**
- **Android AE** — Application Entity; publishes telemetry CINs; receives command CINs via subscription; sends ACK CINs.
- **ACME CSE** — Infrastructure Node; stores all resources; routes subscription notifications; mediates all inter-entity communication.
- **Streamlit** — Monitoring and control entity; subscribes to telemetry and ACK containers; publishes command CINs; records metrics.

---

## 2. CSE Infrastructure

### Docker Compose Services

| Service | Image | Role | Ports |
|---|---|---|---|
| `acme-cse` | Custom (python:3.11-slim + acmecse==2025.11) | OneM2M CSE | 8080/tcp (HTTP), 8180/tcp (WS), 5683/udp (CoAP) |
| `mosquitto` | eclipse-mosquitto:2 | MQTT broker | 1883/tcp |

The ACME CSE is an MQTT **client** — it connects to Mosquitto as a subscriber/publisher. Both services share an internal Docker bridge network (`cse-net`); the CSE resolves the broker by its compose service name `mosquitto` via Docker DNS.

### Applied Patches

Two source-level patches are volume-mounted into the CSE container at startup:

| Patch | File patched | Reason |
|---|---|---|
| `patches/WebSocketServer.py` | `acmecse/protocols/WebSocketServer.py` | Disables WebSocket keepalive pings. Under benchmark load (≥4 msg/s), the CSE's periodic ping/pong cycle interleaved with notifications, causing OkHttp to close the Android WebSocket connection. |
| `patches/CoAPthonTools.py` | `acmecse/helpers/CoAPthonTools.py` | Adds `Operation.NOTIFY: defines.Codes.POST.number` to `operationsMethodsMap`. ACME CSE v2025.11 omits this entry, raising `KeyError` when the CSE attempts to deliver a CoAP notification. |

### Key acme.ini Settings

| Setting | Value | Effect |
|---|---|---|
| `cseID` | `id-in` | CSE-Base identifier; only used in AE registration `to` field |
| `cseName` | `cse-in` | CSE-Base resource name; prefix for all child resource paths |
| `allowedAEOriginators` | `C*,S*` | Android uses `C`+serial; Streamlit uses `CStreamlit` |
| `enableACPChecks` | `false` | Disables ACP — all entities trusted in lab environment |
| `enableSubscriptionVerificationRequests` | `false` | Disables vrq NOTIFY on SUB creation (CoAP NOTIFY had no handler in v2025.11; disabled for all protocols uniformly) |
| `asyncSubscriptionNotifications` | `true` | Non-blocking notification delivery |

---

## 3. OneM2M Resource Tree

The tree below reflects the state after Android connects and Streamlit subscribes. All child resources reside under the CSE-Base resource name `/cse-in/` (not the CSE-ID `/id-in/`).

```
/id-in                                 ← CSE-Base (ty=5)
  └── /cse-in/uxv                      ← AE (ty=2) — created by Android
        ├── /cse-in/uxv/telemetry      ← CNT (ty=3, mni=10) — telemetry stream
        │     ├── <CIN> <CIN> ...      ← rolling window, up to 10 instances
        │     ├── sub-streamlit-tel    ← SUB (ty=23) — WS/MQTT Streamlit subscription
        │     ├── sub-http-streamlit-tel  ← SUB (ty=23) — HTTP Streamlit subscription
        │     └── sub-coap-streamlit-tel  ← SUB (ty=23) — CoAP Streamlit subscription
        │
        ├── /cse-in/uxv/commands       ← CNT (ty=3, mni=5) — command queue
        │     ├── <CIN> <CIN> ...
        │     └── sub-commands         ← SUB (ty=23) — Android subscription
        │
        └── /cse-in/uxv/ack            ← CNT (ty=3, mni=200) — command ACK queue
              ├── <CIN> <CIN> ...
              ├── sub-streamlit-ack    ← SUB (ty=23) — WS/MQTT Streamlit subscription
              ├── sub-http-streamlit-ack  ← SUB (ty=23) — HTTP Streamlit subscription
              └── sub-coap-streamlit-ack  ← SUB (ty=23) — CoAP Streamlit subscription
```

> **Note:** WS and MQTT share the same subscription resource names (`sub-streamlit-tel`, `sub-streamlit-ack`). On protocol switch, `disconnect()` deletes these subscriptions from the CSE before closing the transport, preventing 4005 CONFLICT on the next `connect()`.

### Container Parameters

| Container | Path | mni | Producer | Consumer |
|---|---|---|---|---|
| `telemetry` | `cse-in/uxv/telemetry` | 10 | Android (250 ms) | Streamlit (via SUB) |
| `commands` | `cse-in/uxv/commands` | 5 | Streamlit | Android (via SUB) |
| `ack` | `cse-in/uxv/ack` | 200 | Android (per command) | Streamlit (via SUB) |

---

## 4. Android AE Session Establishment

The `OneM2MSession` class manages the full 6-step registration sequence over any transport. Steps execute serially via callback chaining; each step waits for a CSE response (10 s timeout) before proceeding.

```
transport.connect()
    → [transport up] → onConnectionStatusChange(true)

OneM2MSession.registerAE():
  [MQTT/HTTP/CoAP only]  DELETE  cse-in/uxv          (clean up stale transport poa)
  CREATE  id-in           ty=2   rn=uxv, api=N.com.uxv.onem2m, rr=true, poa=[...]
  ← rsc=2001 (created) or 4105/4117 (already exists) → both treated as success

OneM2MSession.doUpdateAePoa():
  UPDATE  cse-in/uxv      ty=0   poa=[current-transport-url]
  ← rsc=2004

OneM2MSession.createTelemetryContainer():
  CREATE  cse-in/uxv      ty=3   rn=telemetry, mni=10
  ← rsc=2001 or 4105

OneM2MSession.createCommandsContainer():
  CREATE  cse-in/uxv      ty=3   rn=commands, mni=5
  ← rsc=2001 or 4105

OneM2MSession.createSubscription():
  CREATE  cse-in/uxv/commands  ty=23  rn=sub-commands, nu=[aeOriginator], enc.net=[3]
  ← rsc=2001 or 4105

OneM2MSession.createAckContainer():
  CREATE  cse-in/uxv      ty=3   rn=ack, mni=200
  ← rsc=2001 or 4105

→ onSessionReady() → commandListener.onConnectionStatusChange(true)
→ TelemetryManager.startTelemetry()   ← 250 ms timer begins
```

**Transport-specific differences in registration:**

| Step | WebSocket | MQTT | HTTP | CoAP |
|---|---|---|---|---|
| DELETE AE before CREATE | **No** — CSE closes active WS when AE deleted | Yes | Yes | Yes |
| `poa` in AE CREATE | `ws://cse_ip:8180` | `mqtt://mosquitto:1883` | `http://rc_ip:8181` | `http://rc_ip:8182` |
| Explicit NOTIFY ACK | Yes (`{"rsc":2000,...}` on WS) | Yes (publish to TOPIC_RESP) | No (HTTP 200 = ACK) | No (HTTP 200 = ACK) |
| `rsc=4117` handling | Treat as 4105 | Treat as 4105 | N/A | N/A |

> **WebSocket DELETE skip:** Deleting the AE closes the `associatedConnections` entry in the CSE, which terminates the WebSocket session in progress. MQTT/HTTP/CoAP perform DELETE+CREATE to reset the `poa` to the current transport without this side effect.

> **MQTT `poa` host:** Uses Docker service name `mosquitto` (not the device LAN IP). The ACME CSE resolves this name via the Docker Compose internal DNS when delivering notifications.

> **HTTP/CoAP `poa`:** Points to the device's WiFi IP and the NanoHTTPD callback port, since the CSE must reach the callback server over TCP (see §8 — Lab Constraints).

---

## 5. Streamlit Session Establishment

Streamlit registers as AE `CStreamlit` and creates subscriptions to `telemetry` and `ack`. The exact mechanism depends on the protocol.

```
ProtocolClient.connect()
  → [transport ready]

[WS/MQTT only]
  CREATE  id-in  ty=2  rn=streamlit, api=N.com.uxv.benchmark.streamlit,
                       rr=true, poa=[transport-specific]
  ← rsc=2001 or 4105/4117 → UPDATE poa to current transport URL

[All protocols]
  DELETE  cse-in/uxv/telemetry/sub-{prefix}-tel    (best-effort)
  CREATE  cse-in/uxv/telemetry  ty=23  rn=sub-{prefix}-tel,
          nu=[CStreamlit], enc.net=[3]
  ← rsc=2001 → capture subscription ri (auto-generated, e.g. /id-in/subXXX)

  DELETE  cse-in/uxv/ack/sub-{prefix}-ack
  CREATE  cse-in/uxv/ack  ty=23  rn=sub-{prefix}-ack,
          nu=[CStreamlit], enc.net=[3]
  ← rsc=2001 → capture subscription ri
```

Subscription resource name prefix per protocol:

| Protocol | Prefix | `nu` field | AE registration |
|---|---|---|---|
| WebSocket | `streamlit` | `CStreamlit` | Yes |
| MQTT | `streamlit` | `CStreamlit` | Yes |
| HTTP | `http-streamlit` | `http://host.docker.internal:8090/notify` | No (CAdmin) |
| CoAP | `coap-streamlit` | `http://host.docker.internal:8090/notify` | No (CAdmin) |

> **`sur` field matching:** ACME CSE v2025.11 places the subscription's auto-generated `ri` (e.g. `/id-in/subBPiTR1sRvs`) in the `sur` field of notifications, not the human-readable path. All protocol clients capture `ri` at subscription time and match it against `sur` at notification time.

> **HTTP/CoAP `nu`:** Uses `host.docker.internal` (Docker Desktop TCP bridge, resolves to 192.168.65.254) so the CSE container can reach the callback server over TCP. The LAN IP is not reachable from inside Docker Desktop containers.

---

## 6. Scenario 1 — Telemetry Uplink (Android → CSE → Streamlit)

```
Android                          ACME CSE                   Streamlit
  │  t_send_ms = System.currentTimeMillis()                      │
  │                                │                              │
  │  CREATE cse-in/uxv/telemetry   │                              │
  │  CIN.con = telemetry JSON      │                              │
  ├────────────────────────────────►                              │
  │◄──── rsc=2001 ─────────────────┤                              │
  │  (fire-and-forget; response    │   SUB notification           │
  │   discarded by Android)        ├──────────────────────────────►
  │                                │   m2m:sgn.nev.rep.m2m:cin   │ timestamp_ms = now()
  │                                │   .con = telemetry JSON      │
  │                                │◄─ ACK rsc=2000 ─────────────┤
```

**Telemetry CIN payload** (field `con`, serialised JSON string):

```json
{
  "lat": 39.933, "lng": -8.892, "alt": 15.2,
  "velX": 1.2, "velY": -0.5, "velZ": 0.3,
  "hdg": 180.5, "satCount": 18,
  "bat": {"lvl": 85, "remaining": 3200, "voltage": 16800, "current": 250,
          "temperature": 38.5, "charging": false},
  "gimbal": {"pitch": -45.0, "roll": 0.0, "yaw": 12.3},
  "isFlying": true, "areMotorsOn": true, "isGoingHome": false,
  "model": "Mavic 2 Enterprise Advanced",
  "seq": 1234,
  "t_send_ms": 1748000000000
}
```

**Metrics captured per CIN:**

| Metric | Formula | NTP dependency |
|---|---|---|
| `latency_ms` | `timestamp_ms − t_send_ms` | **Yes** |
| `payload_bytes` | `len(con)` in bytes | — |
| `header_bytes` | Protocol framing (see §7) | — |
| `delivered` | CIN received = True; gap in `seq` = False | — |

---

## 7. Scenario 2 — Command Downlink (Streamlit → CSE → Android → ACK → Streamlit)

```
Streamlit                        ACME CSE                   Android
  │  t_cmd_ms = int(time.time()*1000)                            │
  │  CREATE cse-in/uxv/commands   │                              │
  │  CIN.con = command JSON        │                              │
  ├────────────────────────────────►                              │
  │◄──── rsc=2001 ─────────────────┤  cin_create_ms = RTT       │
  │                                │   SUB notification           │
  │                                ├──────────────────────────────►
  │                                │   CIN.con = command JSON     │ t_recv_ms = now()
  │                                │◄─ ACK rsc=2000 ─────────────┤ dispatch to DJI SDK
  │                                │                              │ t_exec_ms = now()
  │                                │   CREATE cse-in/uxv/ack     │
  │                                │◄──────────────────────────── │
  │   SUB notification (ACK CIN)   │                              │
  │◄───────────────────────────────┤                              │
  │  con = {command, seq_cmd,       │                              │
  │         t_cmd_ms, t_recv_ms,   │                              │
  │         t_exec_ms}             │                              │
```

**Metrics captured per command:**

| Metric | Formula | NTP dependency |
|---|---|---|
| `cin_create_ms` | Streamlit monotonic RTT (publish→rsc=2001) | No |
| `latency_ms` | `t_recv_ms − t_cmd_ms` | **Yes** (two devices) |
| `t_exec_ms` | Android wall-clock after DJI SDK dispatch | — |
| `delivered` | ACK received within 10 s timeout | — |

---

## 8. Per-Protocol Message Encoding

### WebSocket

- **Transport:** Persistent TCP connection; subprotocol `oneM2M.json`; upgrade header `X-M2M-Origin: CStreamlit`
- **Message format:** flat JSON (no `m2m:rqp`/`m2m:rsp` wrappers); `rvi="3"` mandatory in all requests
- **Request routing:** single JSON object per WS frame; `rsc` in response identifies response type; `op=5` identifies NOTIFY
- **Notification ACK:** flat JSON `{"rsc":2000,"rqi":"...","to":"CStreamlit","fr":"CStreamlit","rvi":"3"}` sent on the same WS connection
- **Header overhead:** 6 bytes (payload ≤125 B), 8 bytes (≤65535 B), 14 bytes (>65535 B) per RFC 6455 framing

### MQTT

- **Transport:** MQTT v3.1.1; QoS 1; broker Eclipse Mosquitto 2

**Topic structure:**

| Direction | Topic |
|---|---|
| AE → CSE request | `/oneM2M/req/{originator}/id-in/json` |
| CSE → AE response | `/oneM2M/resp/{originator}/id-in/json` |
| CSE → AE notification | `/oneM2M/req/id-in/{originator}/json` |

> Originator comes **first** in request/response topics; CSE-ID comes first in notification topics.

- **Message format:** same flat JSON as WebSocket
- **Notification ACK:** published to TOPIC_RESP (`/oneM2M/resp/{originator}/id-in/json`), not to TOPIC_REQ — CSE subscribes to this topic for AE responses
- **Header overhead:** 1 fixed byte + varint remaining-length + 2-byte topic-length field + topic bytes + 2-byte packet-id (QoS ≥1)

### HTTP

- **Transport:** HTTP/1.1 over TCP; stateless; no persistent connection
- **Originator:** `CAdmin` (administrator; no AE registration required)
- **Request:** `POST http://cse_ip:8080/cse-in/uxv/commands`
- **Mandatory headers:** `X-M2M-Origin`, `X-M2M-RI`, `X-M2M-RVI: 3`, `Content-Type: application/json;ty={resourceType}`
- **RSC:** read from `X-M2M-RSC` response header; HTTP 201 = rsc=2001
- **Notification delivery:** embedded `HTTPServer` on `0.0.0.0:8090`; CSE POSTs `{"m2m:sgn":{...}}` to `http://host.docker.internal:8090/notify`
- **Notification ACK:** HTTP 200 response from the callback server (no JSON ACK needed)
- **Header overhead:** sum of request headers + response headers (measured per request)

### CoAP

- **Transport:** CoAP/UDP (Confirmable messages); no DTLS (not implemented in v2025.11)
- **Originator:** `CAdmin` (no AE registration required)
- **Request:** CON POST to `coap://cse_ip:5683/cse-in/uxv/commands`
- **oneM2M CoAP options** (per TS-0010):

| Option | Number | Type | Value |
|---|---|---|---|
| oneM2M-TY | 267 | UINT | Resource type (CREATE only) |
| oneM2M-RVI | 271 | Opaque | `b"3"` |
| oneM2M-FR | 279 | Opaque | originator bytes |
| oneM2M-RQI | 283 | Opaque | request ID bytes |
| oneM2M-RSC | 307 | Opaque | response status (read from response) |

- **Request body:** resource representation only (the `pc` field content); no `op`/`fr`/`rqi` in the body
- **RSC:** decoded from option 307 as big-endian integer
- **Notification delivery:** embedded `HTTPServer` on `0.0.0.0:8090` (shared with HTTP client); CSE POSTs `{"m2m:sgn":{...}}` via HTTP/TCP (see §9 — Lab Constraints)
- **Header overhead (estimated):** 4 bytes fixed header + 8 bytes token + 2+len(host) bytes Uri-Host option + ~10 bytes other options + 1 byte payload marker

---

## 9. Notification Delivery Mechanisms

The CSE delivers subscription notifications to the AE using the `poa` (Point of Access) registered with the AE resource. Delivery mechanism differs per protocol:

| Protocol | Android notification delivery | Streamlit notification delivery |
|---|---|---|
| WebSocket | CSE reuses the active WS connection (matched by originator in `associatedConnections`) | Same |
| MQTT | CSE publishes to `/oneM2M/req/id-in/{originator}/json`; AE subscribed to this topic | Same |
| HTTP | CSE POSTs to `http://rc_ip:8181` (NanoHTTPD server on Android) | CSE POSTs to `http://host.docker.internal:8090/notify` (Python HTTPServer) |
| CoAP | CSE POSTs to `http://rc_ip:8182` (NanoHTTPD on Android) — **HTTP, not CoAP** | CSE POSTs to `http://host.docker.internal:8090/notify` — **HTTP, not CoAP** |

---

## 10. Lab Constraints and Engineering Decisions

### Docker Desktop UDP Routing (Critical)

**Constraint:** Docker Desktop on Windows does not route UDP packets originating inside a container to external LAN devices. Confirmed empirically: `docker exec acme-cse python3 -c "import socket; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.sendto(b'test', ('192.168.1.83', 5684))"` produced no output on the Android CoAP server.

**Impact:** The ACME CSE cannot deliver CoAP NOTIFY messages (CoAP/UDP) to Android or Streamlit when running inside Docker Desktop on Windows. TCP connections to `host.docker.internal` are routed via the Docker Desktop TCP bridge (192.168.65.254) and are not affected.

**Workaround applied to both endpoints:**

| Endpoint | What changed | Effect on outgoing requests |
|---|---|---|
| Streamlit (`coap_client.py`) | `nu` set to `http://host.docker.internal:8090/notify`; embedded HTTPServer receives notifications | CoAP/UDP still used for all outgoing requests (CIN CREATE, SUB CREATE, DELETE) |
| Android (`CoApProtocolClient.java`) | `poa` set to `http://rc_ip:8182`; NanoHTTPD on port 8182 receives notifications | CoAP/UDP (Californium `sharedEndpoint`) still used for all outgoing requests |

**Paper note:** Both endpoints use CoAP/UDP for outgoing requests. Incoming notification delivery uses HTTP/TCP as a lab-environment workaround. This constraint affects notification latency for the CoAP protocol (includes TCP connection overhead). Documented as a limitation in methodology.

### Subscription Conflict on Protocol Switch (4005 CONFLICT)

When switching protocols in the Streamlit dashboard, the previous client's subscriptions may persist in the CSE if `disconnect()` does not delete them. On the next `connect()`, the `_ensure_subscription()` call issues a DELETE followed by a CREATE. If DELETE times out (e.g. network briefly unavailable during teardown), the CREATE returns rsc=4005. All clients handle this via a GET fallback to retrieve the existing subscription's `ri`.

### AE Registration Conflict (4105/4117)

ACME CSE v2025.11 returns rsc=4105 (CONFLICT) when trying to CREATE an AE that already exists. It returns rsc=4117 (`ORIGINATOR_ALREADY_REGISTERED`) when the AE exists **and** the CSE still has an active transport association for that originator. Both are treated as success, followed by an UPDATE of the `poa` to the current transport URL.

### `cnf` Field Omission

ACME CSE v2025.11 rejects CIN resources with the `cnf` field set to `"application/json"` (rsc=4000). The field is omitted in all CIN CREATE requests.

### `nct` Field Omission

Setting `nct=2` (notification content type = resource) together with `enc.net=[3]` (notify on child CREATE) is invalid in v2025.11 (rsc=4000). The `nct` field is omitted; the CSE defaults to delivering the full resource representation.

---

## 11. Network Topology

```
[DJI M2EA drone] ─── OcuSync ───► [DJI RC — Android app]
                                          │
                                     WiFi 802.11 (LAN: 192.168.1.x)
                                          │
                         ┌────────────────▼────────────────────────┐
                         │  Dev Machine — Windows 11              │
                         │  LAN IP: 192.168.1.71                  │
                         │                                         │
                         │  Docker Desktop (WSL2, mirrored net)   │
                         │  ┌──────────────────────────────────┐  │
                         │  │  mosquitto  :1883/tcp            │  │
                         │  ├──────────────────────────────────┤  │
                         │  │  acme-cse   :8080/tcp (HTTP)     │  │
                         │  │             :8180/tcp (WS)       │  │
                         │  │             :5683/udp (CoAP)     │  │
                         │  └──────────────────────────────────┘  │
                         │                                         │
                         │  Streamlit (.venv Python 3.12)         │
                         │  :8501 dashboard                       │
                         │  :8090/tcp  HTTP callback server       │
                         └─────────────────────────────────────────┘
```

**Firewall:** Windows Firewall GPO-managed rules; Wi-Fi profile must be set to **Private** for inbound connections from Android RC to be accepted.

---

## 12. Latency Measurement

### Scenario 1 — One-way telemetry latency

```
latency_ms = timestamp_ms (Streamlit, wall-clock) − t_send_ms (Android, System.currentTimeMillis())
```

Requires NTP synchronisation between Android RC and dev machine. Typical LAN NTP offset: 1–10 ms. Raw `latency_ms` values below ~10 ms should be interpreted cautiously.

### Scenario 2 — Command delivery latency

```
latency_ms = t_recv_ms (Android) − t_cmd_ms (Streamlit)
cin_create_ms = monotonic RTT from Streamlit CIN publish to rsc=2001 response
```

`cin_create_ms` has no NTP dependency (single device, monotonic clock). `latency_ms` requires NTP sync (two devices). `t_exec_ms − t_recv_ms` measures DJI SDK dispatch overhead (single device, no NTP dependency).

### Packet loss

```
seq gaps = packet_loss_count   (seq counter increments monotonically per Android session)
loss_rate = gaps / (max_seq − min_seq + 1)
```
