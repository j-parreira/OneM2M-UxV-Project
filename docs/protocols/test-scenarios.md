# Protocol Test Scenarios

> Last updated: 2026-05-21. Protocol configurations reflect ACME CSE v2025.11
> and the verified Android app OneM2MSession implementation.

---

## Scenarios

Each of the four protocols (WebSocket, MQTT, HTTP, CoAP) is evaluated under three scenarios:

### Scenario 1 — Idle Telemetry Stream

Drone hovers at fixed position. Android app pushes telemetry at a fixed rate.
The Streamlit dashboard subscribes and records arrival timestamps.

**Rates tested:** 1 msg/s (1000 ms), 5 msg/s (200 ms), 10 msg/s (100 ms)

Change rate via OneM2M command (Streamlit sends to Android):
```json
{"command": "setTelemetryRate", "intervalMs": 200}
```

**Metrics:** latency (one-way, NTP-dependent), throughput, protocol overhead
**Duration:** 5 minutes per rate per protocol
**Repetitions:** 30 runs minimum per combination

### Scenario 2 — Command Burst

Dashboard sends 50 commands in rapid succession. Android receives each via subscription
notification, executes it, and sends an ACK CIN with timestamps.

**Latency measured:** `t_recv_ms - t_cmd_ms` (both in ACK CIN, device-to-device)
**Metrics:** latency (per-message and burst total), packet loss
**Duration:** until 50 ACKs received or 60s timeout
**Repetitions:** 30 runs per protocol

### Scenario 3 — Degraded Network

Network emulation using `tc netem` **inside the ACME CSE container** (Linux network stack).
Windows host does not support netem; the container has `NET_ADMIN` capability.

```bash
# Before run: inject packet loss
docker exec acme-cse tc qdisc add dev eth0 root netem loss 5%

# After run: reset
docker exec acme-cse tc qdisc del dev eth0 root
```

**Loss levels:** 2%, 5%, 10%
**Metrics:** actual vs injected packet loss, latency increase, throughput degradation
**Duration:** 5 minutes per level per protocol
**Repetitions:** 10 runs per combination (loss × protocol)
**Priority:** after June 6 course paper deadline

---

## Protocol Configuration

### WebSocket (verified against ACME CSE v2025.11)

- **Port:** 8180
- **Upgrade headers required:**
  - `Sec-WebSocket-Protocol: oneM2M.json`
  - `X-M2M-Origin: {aeOriginator}`
- **Message format:** flat JSON (no `m2m:rqp`/`m2m:rsp` wrappers)
- **`rvi` field:** `"3"` — mandatory in every request
- **Notification delivery:** requires `poa=['ws://host:8180']` in AE registration body
- **Subscription:** `nct` omitted; `cnf` omitted from CIN body

See `docs/ai-context/cse-dev.md` → "WebSocket Binding" for the complete integration guide.

### MQTT

- **Broker:** Eclipse Mosquitto (separate Docker service on :1883)
- **ACME CSE role:** MQTT **client** (connects to Mosquitto)
- **QoS:** 1 (at least once) — baseline; also test QoS 0 and 2 in Scenario 3
- **Topic structure (verified):**

| Direction | Topic |
|---|---|
| AE → CSE request | `/oneM2M/req/{originator}/{cseID}/json` |
| CSE → AE response | `/oneM2M/resp/{originator}/{cseID}/json` |
| CSE → AE notification | `/oneM2M/req/{cseID}/{originator}/json` |

> Note: response topic is `{originator}/{cseID}` (originator first) — confirmed empirically.

- **Android client:** paho-mqtt library (to be implemented as `MqttProtocolClient`)
- **Dashboard client:** paho-mqtt Python

### HTTP

- **Port:** 8080
- **Resource paths:** `/cse-in/uxv/telemetry`, `/cse-in/uxv/commands`, `/cse-in/uxv/ack`
  (NOT `/id-in/...` — see `system-overview.md` → URL Structure)
- **Mandatory headers:** `X-M2M-RI`, `X-M2M-Origin`, `X-M2M-RVI: 3`
- **CIN body:** `cnf` field omitted (fails validation in v2025.11)
- **TLS:** not tested in initial benchmarks

### CoAP

- **Port:** 5683/udp (Docker must expose with `/udp` suffix)
- **Message type:** Confirmable (CON) for reliability comparison
- **DTLS:** not implemented in v2025.11 (`useDTLS=false`)
- **Android client:** aiocoap or californium (to be implemented as `CoApProtocolClient`)
- **Dashboard client:** aiocoap

---

## Raw Data Schema

### Per-message CSV columns (written by Streamlit orchestrator)

| Column | Type | Description | Source |
|---|---|---|---|
| `timestamp_ms` | int | Wall-clock at dashboard reception | Streamlit |
| `run_id` | str | `<protocol>_s<scenario>_<YYYYMMDD>_run<N>` | Streamlit |
| `protocol` | str | websocket / mqtt / http / coap | Streamlit |
| `scenario` | int | 1, 2, or 3 | Streamlit |
| `direction` | str | telemetry / command | Streamlit |
| `latency_ms` | float | End-to-end latency (null if lost) | Computed |
| `payload_bytes` | int | Size of JSON payload | Measured |
| `header_bytes` | int | Protocol header size | Measured |
| `delivered` | bool | Whether message was confirmed received | Derived |
| `seq` | int | Sequence number from Android `seq` field | From CIN |
| `t_send_ms` | int | Epoch ms at Android send time (telemetry only) | From CIN |
| `t_cmd_ms` | int | Epoch ms at Streamlit command send (Scenario 2) | From ACK CIN |
| `t_recv_ms` | int | Epoch ms at Android reception (Scenario 2) | From ACK CIN |
| `t_exec_ms` | int | Epoch ms after command dispatch (Scenario 2) | From ACK CIN |

### Metadata sidecar (per run)

JSON file with the same stem as the CSV:
```json
{
  "run_id": "websocket_s1_20260520_run001",
  "protocol": "websocket",
  "scenario": 1,
  "rate_msg_s": 5,
  "duration_s": 300,
  "cse_version": "2025.11",
  "android_app_version": "4.0",
  "drone_model": "Mavic 2 Enterprise Advanced",
  "operator_notes": "",
  "start_timestamp_ms": 1748000000000
}
```

---

## Benchmark Run Checklist

Before each run:
- [ ] ACME CSE container healthy: `curl http://localhost:8080/id-in -H "X-M2M-RI: t" -H "X-M2M-Origin: CAdmin" -H "X-M2M-RVI: 3"`
- [ ] Android app connected (check CSE web UI at `http://localhost:8080/webui`)
- [ ] Resource tree visible: `/cse-in/uxv/telemetry`, `/cse-in/uxv/commands`, `/cse-in/uxv/ack`
- [ ] Drone battery > 50%
- [ ] RC battery > 50%
- [ ] Streamlit dashboard running with correct protocol selected
- [ ] Scenario 3 only: tc netem NOT active before run start

After each run:
- [ ] CSV saved to `data/raw/`
- [ ] Metadata sidecar saved
- [ ] Scenario 3: reset netem (`docker exec acme-cse tc qdisc del dev eth0 root`)
