# Development Context — Streamlit Dashboard (`src/frontend/`)

> Read this before touching anything in `src/frontend/`. Complements `project-context.md`
> and `docs/protocols/test-scenarios.md`.

---

## Purpose

The Streamlit app has two distinct modes that should be kept as separate pages:

1. **Manual** — send individual commands to the drone, observe live telemetry. Used for
   integration testing and pre-benchmark validation.
2. **Benchmark** — automated orchestration of experiment runs; logs metrics to `data/raw/`.

It is the only component that writes to `data/raw/`. Analysis scripts read from there.

---

## Directory Structure

```
src/frontend/
├── app.py                  ← entry point: `streamlit run app.py`
├── pages/
│   ├── 1_manual.py         ← manual control and live telemetry
│   ├── 2_benchmark.py      ← benchmark orchestrator
│   └── 3_results.py        ← quick view of last run (optional, low priority)
├── core/
│   ├── config.py           ← reads CSE IP, ports from environment / .env
│   ├── logger.py           ← CSV writer for data/raw/
│   ├── protocols/
│   │   ├── http_client.py
│   │   ├── mqtt_client.py
│   │   ├── websocket_client.py
│   │   └── coap_client.py
│   └── orchestrator.py     ← runs a benchmark scenario end-to-end
├── requirements.txt
└── .env.example            ← template — .env is git-ignored
```

---

## Configuration

All connection parameters come from environment variables (or a `.env` file). Never hardcoded.

```env
# .env.example
CSE_HOST=192.168.1.100       # LAN IP of the dev machine running Docker
CSE_HTTP_PORT=8080
CSE_MQTT_PORT=1883
CSE_WS_PORT=8180
CSE_COAP_PORT=5683
DATA_RAW_DIR=../../data/raw

# HTTP/CoAP callback — this machine's LAN IP as reachable by the CSE Docker container.
# Do NOT use 127.0.0.1: Docker containers cannot reach the host loopback.
CALLBACK_HOST=192.168.1.100  # same LAN IP as CSE_HOST (dev machine WiFi IP)
CALLBACK_HTTP_PORT=8090      # embedded HTTP server port (NanoHTTPD equivalent in Python)
CALLBACK_COAP_PORT=5684      # embedded CoAP server port (aiocoap)
```

`core/config.py` loads these with `python-dotenv` and exposes a single `Config` dataclass.

---

## Python Dependencies

```
streamlit==1.45.1
paho-mqtt==2.1.0
websockets==13.1
aiocoap==0.4.8
requests==2.32.3
python-dotenv==1.0.1
```

Pin exact versions for reproducibility. Create `src/frontend/.venv` — never install globally.

---

## Protocol Clients (`core/protocols/`)

Each client module exposes the same interface so the orchestrator is protocol-agnostic:

```python
class ProtocolClient:
    def connect(self) -> None: ...
    def send_command(self, payload: dict) -> tuple[float, bool]:
        """Send command, return (latency_ms, delivered)."""
    def subscribe_telemetry(self, callback: Callable[[dict], None]) -> None: ...
    def disconnect(self) -> None: ...
```

Timestamps for latency (`t_command`, `t_ack`) must be taken with `time.monotonic_ns()`
converted to milliseconds — not `time.time()`, which can jump due to NTP.

### HTTP client
- POST to `http://{CSE_HOST}:{CSE_HTTP_PORT}/cse-in/uxv/commands`
  (NOT `/onem2m/...` or `/id-in/...` — see `docs/ai-context/cse-dev.md` → URL Structure)
- Headers: `X-M2M-RI`, `X-M2M-Origin: CAdmin`, `X-M2M-RVI: 3`, `Content-Type: application/json;ty=4`
- Body: `{"m2m:cin": {"con": "{\"command\":\"takeoff\",\"seq_cmd\":1,\"t_cmd_ms\":...}"}}`
  (no `cnf` field — fails validation in ACME CSE v2025.11)
- Latency: Streamlit records `t_cmd_ms`; Android sends back `t_recv_ms` in ACK CIN

### MQTT client
- Originator: `CStreamlit` (AE registered with `poa=[mqtt://host:1883]`)
- Publish commands to `/oneM2M/req/CStreamlit/id-in/json` (request topic)
- Subscribe to `/oneM2M/resp/CStreamlit/id-in/json` (response topic for CSE → Streamlit)
- Subscribe to `/oneM2M/req/id-in/CStreamlit/json` (notification topic for CSE push)
- Subscribe to ack CINs via separate subscription on `/cse-in/uxv/ack`
- Telemetry: subscribe to `/cse-in/uxv/telemetry` via separate oneM2M subscription
- Note: request/response topics have `{originator}/{cseID}` order; notification topics reverse to `{cseID}/{originator}` — confirmed empirically

### WebSocket client
- Connect to `ws://{CSE_HOST}:{CSE_WS_PORT}/`
- Subprotocol: `oneM2M.json` (required — server returns 400 without it)
- Header: `X-M2M-Origin: CAdmin` in upgrade request
- Format: flat JSON (no `m2m:rqp` wrapper), `rvi="3"` mandatory
- Subscribe to `/cse-in/uxv/telemetry` and `/cse-in/uxv/ack` via oneM2M SUB resources

### CoAP client
- POST to `coap://{CSE_HOST}:{CSE_COAP_PORT}/cse-in/uxv/commands`
- Use Confirmable (CON) messages; no DTLS (not implemented in ACME CSE v2025.11)
- Latency: time from POST to receiving ACK
- `aiocoap` is async — wrap with `asyncio.run()` or run in a thread

---

## Benchmark Orchestrator (`core/orchestrator.py`)

Runs one complete experiment run and returns a list of metric records.

```python
@dataclass
class RunConfig:
    protocol: str           # mqtt | http | websocket | coap
    scenario: int           # 1 | 2 | 3
    rate_msg_s: int | None  # Scenario 1 only (4 or 16 msg/s)
    n_commands: int         # Scenario 2: 60; Scenario 1: derived from rate × duration
    duration_s: int         # Scenario 1: 120 s (2 min)
    run_id: str             # generated: <protocol>_s<scenario>_<YYYYMMDD>_run<N>
```

The orchestrator:
1. Instantiates the correct `ProtocolClient` from `RunConfig.protocol`
2. Connects and subscribes
3. Sends commands / telemetry triggers at the configured rate
4. Collects `MetricRecord` per message (see Data Logging below)
5. Disconnects
6. Calls `logger.save_run(records, run_config)` → writes CSV

---

## Data Logging (`core/logger.py`)

Writes one CSV per run to `data/raw/` with the filename convention:

```
<protocol>_<scenario>_<YYYYMMDD>_run<N>.csv
```

CSV columns (from `docs/protocols/test-scenarios.md`):

| Column | Type | Notes |
|---|---|---|
| `timestamp_ms` | int | `time.time_ns() // 1_000_000` — wall clock for CSV |
| `run_id` | str | matches filename stem |
| `protocol` | str | mqtt / http / websocket / coap |
| `scenario` | int | 1, 2, or 3 |
| `direction` | str | command / telemetry |
| `latency_ms` | float | null if message lost |
| `payload_bytes` | int | size of JSON payload |
| `header_bytes` | int | protocol header size (see below) |
| `delivered` | bool | whether ACK received within timeout |
| `seq` | int | sequence number within run |

A companion `.json` sidecar is written with: software versions, `RunConfig` params,
operator notes, random seed (if any). This is required for paper reproducibility.

### Protocol overhead (`header_bytes`) measurement

| Protocol | What counts as header |
|---|---|
| HTTP | All headers before the body (measured from raw response via `response.raw`) |
| MQTT | Fixed 2-byte header + variable-length remaining length field + topic length |
| WebSocket | 2–10 byte frame header (opcode + mask + payload length) |
| CoAP | 4-byte fixed header + token + options |

---

## Streamlit UI — Key Pages

### Manual page (`pages/1_manual.py`)
- Protocol selector (dropdown)
- CSE connection status indicator (green/red)
- Command buttons: Takeoff, Land, RTH, Hover
- Live telemetry display: GPS, altitude, battery, speed (auto-refresh with `st.empty()`)

### Benchmark page (`pages/2_benchmark.py`)
- Protocol selector + scenario selector
- Parameter inputs (rate, repetitions, duration)
- "Run" button → starts orchestrator in a background thread
- Progress bar + live metric counters (latency, delivered/total)
- "Stop" button (sets a threading.Event)
- Download button for the last CSV when run completes

---

## Android App Integration — Confirmed Facts

The Android app has been reviewed and tested. Key facts for the Streamlit frontend:

**Resource paths (HTTP):**
- Telemetry CINs: `GET /cse-in/uxv/telemetry/la` (latest) or subscribe
- Command CINs: `POST /cse-in/uxv/commands` (Streamlit sends commands here)
- ACK CINs: `GET /cse-in/uxv/ack` or subscribe (Streamlit reads ACKs here)

**Command payload** (what Android expects in CIN.con):
```json
{"command": "takeoff", "seq_cmd": 1, "t_cmd_ms": 1748000000000}
```
- `seq_cmd`: sequence number assigned by Streamlit (for loss detection)
- `t_cmd_ms`: `int(time.time() * 1000)` at send time

**ACK payload** (what Android puts in ack CIN.con):
```json
{"command": "takeoff", "seq_cmd": 1, "t_cmd_ms": 1748000000000,
 "t_recv_ms": 1748000000087, "t_exec_ms": 1748000000092}
```
- Latency = `t_recv_ms - t_cmd_ms` (**NTP-dependent**: `t_cmd_ms` is dev machine clock; `t_recv_ms` is Android RC clock — two separate devices)

**Telemetry rate control** (Scenario 1):
```json
{"command": "setTelemetryRate", "intervalMs": 200}
```
Rates: 1000ms (1/s), 200ms (5/s), 100ms (10/s)

**Subscription mechanism:** The Android app creates a permanent oneM2M SUB resource
(`/cse-in/uxv/commands/sub-commands`) on startup. Streamlit sends commands by POSTing
CINs to `/cse-in/uxv/commands` — the CSE delivers notifications automatically.

**Full command reference:** `src/android/docs/command-reference.md`
**Full telemetry reference:** `src/android/docs/telemetry-reference.md`
