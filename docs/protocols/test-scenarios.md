# Protocol Test Scenarios

> Reference for benchmark execution and per-protocol configuration.
> Reflects the empirically verified ACME CSE v2025.11 implementation.
> Last updated: 2026-06-01.

---

## Scenarios

Each of the four protocols (WebSocket, MQTT, HTTP, CoAP) is evaluated under two primary scenarios:

### Scenario 1 — Telemetry Stream

Drone hovers at a fixed position. The Android app pushes telemetry at a fixed rate (via a 250 ms timer by default). The Streamlit dashboard subscribes to `cse-in/uxv/telemetry` and records arrival timestamps.

**Rate control:** Streamlit sends a command CIN to change the interval:
```json
{"command": "setTelemetryRate", "intervalMs": 200}
```

**Rates tested:** 1 msg/s (1000 ms), 5 msg/s (200 ms), 10 msg/s (100 ms)

**Metrics:** `latency_ms` (NTP-dependent), `payload_bytes`, `header_bytes`, `seq` gaps (packet loss)  
**Duration:** 5 minutes per rate per protocol  
**Repetitions:** ≥30 runs per (rate × protocol) combination

### Scenario 2 — Command Burst

Streamlit sends 50 command CINs in rapid succession to `cse-in/uxv/commands`. The Android app receives each via subscription notification, dispatches to DJI SDK, and sends an ACK CIN to `cse-in/uxv/ack` with timestamps.

**Metrics:** `cin_create_ms` (monotonic, Streamlit-only), `latency_ms` (`t_recv_ms − t_cmd_ms`, NTP-dependent), `delivered` (ACK within 10 s)  
**Duration:** until 50 ACKs or 60 s timeout  
**Repetitions:** ≥30 runs per protocol

### Scenario 3 — Degraded Network (after June 6 deadline)

Network emulation using `tc netem` inside the `acme-cse` container (`NET_ADMIN` capability). Not applicable on Windows host.

```bash
docker exec acme-cse tc qdisc add dev eth0 root netem loss 5%
docker exec acme-cse tc qdisc del dev eth0 root
```

Loss levels: 2%, 5%, 10% — 10 runs per (loss × protocol) combination.

---

## Per-Protocol Configuration

### WebSocket

```
CSE endpoint:   ws://cse_ip:8180
Originator:     CStreamlit  (must start with 'C')
AE poa:         ws://cse_ip:8180
Subscriptions:  sub-streamlit-tel, sub-streamlit-ack
Library:        websockets 13.1 (Python, sync API)
```

**Session setup:**
1. WS upgrade with `Sec-WebSocket-Protocol: oneM2M.json` and `X-M2M-Origin: CStreamlit`
2. Background `recv` thread started
3. AE CREATE `id-in` (or UPDATE poa if existing)
4. SUBs created with `nu=[CStreamlit]` — CSE delivers notifications on active WS connection

**Message format:** flat JSON; `rvi="3"`; `op=5` identifies NOTIFY; `rsc` identifies response  
**NOTIFY ACK:** `{"rsc":2000,"rqi":"...","to":"CStreamlit","fr":"CStreamlit","rvi":"3"}` on same WS

**Android:** `NetworkManager.java` — OkHttp3 WebSocket; `sendAck()` = `sendTelemetry()` (same WS channel)

---

### MQTT

```
CSE endpoint:   tcp://cse_ip:1883  (Mosquitto broker)
Originator:     CStreamlit  (Python) / C+serial  (Android)
AE poa:         mqtt://mosquitto:1883  (Docker service name — not LAN IP)
Subscriptions:  sub-streamlit-tel, sub-streamlit-ack
Library:        paho-mqtt 2.1.0 (Python) / paho-mqtt 1.2.5 (Android)
```

**Topic structure:**

| Direction | Topic |
|---|---|
| AE → CSE request | `/oneM2M/req/{originator}/id-in/json` |
| CSE → AE response | `/oneM2M/resp/{originator}/id-in/json` |
| CSE → AE notification | `/oneM2M/req/id-in/{originator}/json` |

**Session setup:**
1. MQTT CONNECT + SUBSCRIBE to TOPIC_RESP and TOPIC_NOTIF (wait for SUBACK before proceeding)
2. All requests (AE CREATE, SUB CREATE, CIN CREATE) published to TOPIC_REQ
3. Responses arrive on TOPIC_RESP; notifications on TOPIC_NOTIF — same `rawMessageListener` handles both

**NOTIFY ACK:** published to TOPIC_RESP (not TOPIC_REQ) — CSE subscribes to TOPIC_RESP for AE responses

**Android:** `MqttProtocolClient.java` — `sendAck()` overrides `sendTelemetry()` routing to TOPIC_RESP

---

### HTTP

```
CSE endpoint:   http://cse_ip:8080
Originator:     CAdmin  (administrator; no AE registration needed)
Subscriptions:  sub-http-streamlit-tel, sub-http-streamlit-ack
Callback:       HTTPServer on 0.0.0.0:8090
                nu = http://host.docker.internal:8090/notify
Library:        requests 2.32.3 (Python) / OkHttp3 (Android)
```

**Mandatory headers per request:**
```
X-M2M-Origin: CAdmin
X-M2M-RI: <uuid>
X-M2M-RVI: 3
Content-Type: application/json;ty=<resourceType>
```

**Session setup:**
1. HTTPServer thread started on port 8090
2. SUBs created with `nu=["http://host.docker.internal:8090/notify"]`
3. No AE registration — CAdmin is a pre-configured administrator originator

**Notification delivery:** CSE POSTs `{"m2m:sgn":{...}}` to the callback URL; HTTP 200 response = ACK  
**RSC:** from `X-M2M-RSC` header; HTTP 201 = rsc 2001

**Android:** `HttpProtocolClient.java` — OkHttp async POST; NanoHTTPD 2.3.1 callback on port 8181

---

### CoAP

```
CSE endpoint:   coap://cse_ip:5683  (UDP)
Originator:     CAdmin
Subscriptions:  sub-coap-streamlit-tel, sub-coap-streamlit-ack
Callback:       HTTPServer on 0.0.0.0:8090  (shared with HTTP client)
                nu = http://host.docker.internal:8090/notify  [lab constraint]
Library:        aiocoap 0.4.8 (Python) / Californium 2.7.4 (Android)
```

**oneM2M CoAP options (TS-0010 binding):**

| Option | Number | Content |
|---|---|---|
| oneM2M-TY | 267 | Resource type (UINT, CREATE only) |
| oneM2M-RVI | 271 | `b"3"` (Opaque) |
| oneM2M-FR | 279 | Originator bytes (Opaque) |
| oneM2M-RQI | 283 | Request ID bytes (Opaque) |
| oneM2M-RSC | 307 | Response status code (read from response, big-endian int) |

**Request body:** resource representation only (contents of `pc`); no oneM2M envelope  
**Content-Format:** 50 (application/json)  
**Message type:** CON (Confirmable) for reliability

**Session setup:**
1. aiocoap `create_client_context()` (no server binding — notifications via HTTP)
2. HTTP callback server started on port 8090
3. SUBs created via CoAP/UDP with `nu=["http://host.docker.internal:8090/notify"]`

**Lab constraint:** Docker Desktop on Windows blocks UDP from containers to external LAN hosts. The CSE cannot deliver CoAP NOTIFY messages (CoAP/UDP) to Streamlit (localhost) or Android RC (192.168.1.83). Workaround: both endpoints register HTTP URLs as notification targets (`nu`). Outgoing CoAP/UDP requests are not affected. See `docs/architecture/system-overview.md §10` for full analysis.

**Android:** `CoApProtocolClient.java` — Californium `sharedEndpoint` for outgoing requests; NanoHTTPD 2.3.1 on port 8182 for incoming notifications (HTTP/TCP)

---

## Raw Data Schema

### Per-message CSV (`data/raw/<protocol>_s<N>_<YYYYMMDD>_run<NNN>.csv`)

| Column | Type | Description | Source |
|---|---|---|---|
| `timestamp_ms` | int | Wall-clock at Streamlit reception | Streamlit |
| `run_id` | str | `<protocol>_s<scenario>_<YYYYMMDD>_run<N>` | Streamlit |
| `protocol` | str | websocket / mqtt / http / coap | Streamlit |
| `scenario` | int | 1 or 2 | Streamlit |
| `latency_ms` | float\|null | S1: `timestamp_ms−t_send_ms`; S2: `t_recv_ms−t_cmd_ms` | Computed |
| `cin_create_ms` | float\|null | S2: Streamlit→CSE CIN RTT (monotonic) | Streamlit |
| `payload_bytes` | int | `len(con)` in bytes | Measured |
| `header_bytes` | int | Protocol framing overhead | `get_header_bytes()` |
| `delivered` | bool | True if received within timeout | Derived |
| `seq` | int | Android monotonic counter | From CIN |
| `t_send_ms` | int\|null | Android send time (S1) | From CIN |
| `t_cmd_ms` | int\|null | Streamlit command time (S2) | From ACK CIN |
| `t_recv_ms` | int\|null | Android reception time (S2) | From ACK CIN |
| `t_exec_ms` | int\|null | Android DJI dispatch time (S2) | From ACK CIN |

### Metadata sidecar (per run, same filename stem, `.json` extension)

```json
{
  "run_id": "websocket_s1_20260601_run001",
  "protocol": "websocket",
  "scenario": 1,
  "rate_msg_s": 5,
  "duration_s": 300,
  "cse_version": "2025.11",
  "android_app_version": "4.0",
  "drone_model": "Mavic 2 Enterprise Advanced",
  "start_timestamp_ms": 1748000000000
}
```

---

## Benchmark Run Checklist

**Before each run:**
- [ ] CSE healthy: `curl http://localhost:8080/id-in -H "X-M2M-RI: t" -H "X-M2M-Origin: CAdmin" -H "X-M2M-RVI: 3"`
- [ ] Android app connected (resource tree visible in `http://localhost:8080/webui`)
- [ ] `cse-in/uxv/telemetry`, `cse-in/uxv/commands`, `cse-in/uxv/ack` all present
- [ ] Drone battery > 50% | RC battery > 50%
- [ ] Streamlit dashboard running with correct protocol selected
- [ ] Wi-Fi network profile set to Private (Windows Firewall)

**After each run:**
- [ ] CSV and metadata JSON saved to `data/raw/`
- [ ] Scenario 3: `docker exec acme-cse tc qdisc del dev eth0 root`
