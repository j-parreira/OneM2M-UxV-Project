# Protocol Test Scenarios

## Scenarios

Each of the four protocols (MQTT, WebSocket, HTTP, CoAP) is evaluated under these scenarios:

### Scenario 1 — Idle Telemetry Stream

Drone hovers at fixed position. Android app pushes telemetry at a fixed rate (1 msg/s, 5 msg/s,
10 msg/s). Dashboard logs arrival timestamps.

**Metrics:** latency, throughput, overhead
**Duration:** 5 minutes per rate per protocol
**Repetitions:** 30 runs minimum

### Scenario 2 — Command Burst

Dashboard sends 50 commands in rapid succession. Drone acknowledges each. Measures how the
protocol handles burst traffic.

**Metrics:** latency (per-message and total burst), packet loss
**Duration:** until 50 ACKs received or 60s timeout
**Repetitions:** 30 runs per protocol

### Scenario 3 — Degraded Network

Network emulation adds packet loss (2%, 5%, 10%) using `tc netem` **inside the ACME CSE Docker
container** (Linux network stack). This avoids Windows host limitations. The container's network
interface is shaped before each run and reset after.

Tests protocol resilience and retransmission behaviour.

**Metrics:** packet loss (actual vs injected), latency increase, throughput degradation
**Duration:** 5 minutes per loss level per protocol
**Repetitions:** 10 runs per combination (loss level × protocol)
**Priority:** lower — scheduled after June 6 course paper deadline

---

## Protocol Configuration

### MQTT
- Broker: Mosquitto (embedded in ACME CSE or standalone on :1883)
- QoS: 1 (at least once) — baseline; also test QoS 0 and 2 in Scenario 3
- Topic structure: `onem2m/uxv/telemetry/#`, `onem2m/uxv/commands`
- Client: Paho-MQTT (Android) + paho-mqtt (Python dashboard)

### WebSocket
- Port: 80 (dev), 443 (if TLS tested)
- ACME CSE WebSocket binding enabled in config
- Message format: JSON (same schema as HTTP)
- Keep-alive: 30s ping interval

### HTTP
- Port: 8080
- Format: OneM2M REST (JSON bodies, `Content-Type: application/json;ty=4`)
- Method: POST (CREATE contentInstance) + GET (RETRIEVE)
- TLS: not tested in initial benchmarks (future work)

### CoAP
- Port: 5683 (UDP)
- Confirmable messages (CON) for reliability comparison
- Library: californium (Android) + aiocoap (Python dashboard)
- Block-wise transfer not expected (payloads < 1 KB)

---

## Raw Data Schema

CSV columns written by the dashboard during each run:

| Column          | Type    | Description                              |
|----------------|---------|------------------------------------------|
| `timestamp_ms` | int     | Unix timestamp in milliseconds           |
| `run_id`       | str     | Unique run identifier                    |
| `protocol`     | str     | mqtt / websocket / http / coap           |
| `scenario`     | int     | 1, 2, or 3                               |
| `direction`    | str     | command / telemetry                      |
| `latency_ms`   | float   | End-to-end latency (null if lost)        |
| `payload_bytes`| int     | Payload size in bytes                    |
| `header_bytes` | int     | Protocol header size in bytes            |
| `delivered`    | bool    | Whether message was confirmed received   |
| `seq`          | int     | Sequence number within the run           |
