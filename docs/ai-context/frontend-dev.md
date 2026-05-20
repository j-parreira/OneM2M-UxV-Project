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
CSE_HOST=192.168.1.100     # LAN IP of the dev machine running Docker
CSE_HTTP_PORT=8080
CSE_MQTT_PORT=1883
CSE_WS_PORT=8180
CSE_COAP_PORT=5683
DATA_RAW_DIR=../../data/raw
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
- POST to `http://{CSE_HOST}:{CSE_HTTP_PORT}/onem2m/uxv/commands`
- Content-Type: `application/json;ty=4` (OneM2M contentInstance)
- Latency: time from POST call to receiving HTTP 201 response

### MQTT client
- Publish to `onem2m/uxv/commands` at QoS 1
- Subscribe to `onem2m/uxv/telemetry/#` for ACK/telemetry
- Latency: time from publish to receiving the subscription notification

### WebSocket client
- Connect to `ws://{CSE_HOST}:{CSE_WS_PORT}/`
- Send JSON frame; listen for notification frame
- Keep-alive: 30 s ping interval

### CoAP client
- POST to `coap://{CSE_HOST}:{CSE_COAP_PORT}/onem2m/uxv/commands`
- Use Confirmable (CON) messages
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
    rate_msg_s: int | None  # Scenario 1 only (1, 5, 10)
    n_commands: int         # Scenario 2: 50; Scenario 1: derived from rate × duration
    duration_s: int         # Scenario 1 & 3: 300 (5 min)
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

## What Depends on the Android App

Once the Android app is reviewed:
- Confirm the exact OneM2M resource paths the app registers (AE name, container names)
- Confirm subscription mechanism: does the app use a permanent subscription created at
  startup, or does it poll? This affects how the dashboard sends commands.
- Align the JSON command payload schema with what the DJI SDK expects
