# CLAUDE.md — OneM2M-UxV-Project

## Project at a Glance

Research project benchmarking OneM2M middleware across MQTT, WebSocket, HTTP, and CoAP for
DJI UxV (unmanned vehicle) operations. Academic context: IPL Leiria, Mestrado em Engenharia
Informática.

**Authors:** João Parreira, Pedro Barbeiro

**Deliverable:** MDPI journal article, 8–12 pp., **English**, IMRaD structure.

**Deadlines:**
- **2026-06-06** — Paper MDPI (Mobilidade em Sistemas Computacionais, IPL Leiria) — hard deadline
- **After June 6** — Possível submissão a conferência (estrutura MDPI reutilizável)

See `docs/ai-context/project-context.md` for full system context before starting any task.

---

## Sub-project Map

| Directory        | Stack              | Entry Point                                      | Status |
|-----------------|--------------------|-------------------------------------------------|--------|
| `src/android/`  | Java, DJI SDK v4   | Android Studio project                          | ✅ All 4 protocols end-to-end verified (2026-05-31) |
| `src/frontend/` | Python, Streamlit  | `python -m streamlit run app.py`                | ✅ All 4 protocols complete |
| `src/cse/`      | Docker, ACME CSE   | `docker compose up`                             | ✅ Complete |
| `src/analysis/` | Python             | Scripts in `scripts/`, notebooks in `notebooks/` | 🔜 Scripts scaffolded; pending data |
| `docs/`         | Markdown           | Reference only, do not auto-generate            | — |

---

## Python Environments

Each Python sub-project has its own `.venv` — never install packages globally.

```
src/frontend/.venv   ← activate before running Streamlit
src/analysis/.venv   ← activate before running scripts/notebooks
```

Always use `python -m pip install` inside the relevant activated environment.

> **Note:** `src/cse/` does NOT use a `.venv` — ACME CSE runs in Docker. Use
> `docker compose up` from `src/cse/`. See `docs/adr/002-cse-deployment.md`.

---

## Dev Machine Networking (Windows 11 + Docker Desktop + WSL2)

- WSL2 is always configured in **mirrored networking mode** (`networkingMode=mirrored`).
  Docker container ports (1883, 8080, 8180, 5683) are accessible from WSL2 via the host
  LAN IP, but external devices (Android RC) require the Windows Firewall to allow inbound.
- The Windows Firewall Public profile has **GPO-managed rules only** (`LocalFirewallRules=N/A`).
  Local inbound rules are ignored when the Wi-Fi is classified as Public.
  **The Wi-Fi network must be set to Private** for the CSE ports to be reachable from the Android RC:
  ```powershell
  Set-NetConnectionProfile -Name "<wifi-ssid>" -NetworkCategory Private
  ```
- Firewall rules for CSE ports (1883, 8080, 8180) are already present as local rules and
  apply automatically once the network profile is Private.
- `Test-NetConnection <LAN-IP> <port>` from the same Windows machine is an unreliable test
  (routing can differ from external traffic). Use `nc -zv` from inside WSL2, or test from
  the Android device itself.

---

## Protocols and Default Ports

| Protocol  | Port | Transport |
|-----------|------|-----------|
| MQTT      | 1883 | TCP       |
| WebSocket | 8180 | TCP       |
| HTTP      | 8080 | TCP       |
| CoAP      | 5683 | UDP       |

---

## Data Conventions

- **Raw data** → `data/raw/` — never modify these files
- **Processed data** → `data/processed/`
- **Filename format:**
  - Scenario 1: `<protocol>_s1_r<rate>_<YYYYMMDD>_run<NNN>.csv` (rate in msg/s)
    - Example: `mqtt_s1_r5_20260601_run001.csv`
  - Scenario 2: `<protocol>_s2_<YYYYMMDD>_run<NNN>.csv`
    - Example: `http_s2_20260601_run001.csv`
- Files >10 MB must be in `.gitignore` — do not commit large datasets

---

## Code Conventions

### Python (analysis, frontend, CSE config)
- PEP 8, snake_case, type hints on all function signatures
- All configurable parameters (timeouts, repetitions, IP addresses, ports) go in config
  files or environment variables — never hardcoded
- Random seeds must be logged and documented in the script header
- Each script: file-level docstring, function docstrings (description + params + returns)

### Java (Android)
- Standard Android/Java conventions, camelCase methods
- DJI SDK v4 API calls must handle `DJISDKRegisteredCallback` before any operation
- Never hardcode DJI API key — use `local.properties` (git-ignored)

### Jupyter Notebooks
- First cell: markdown with purpose, dependencies, expected runtime
- Separate data loading from analysis (different cells)
- Preserve all output cells with results and plots
- Do not reorganise cells without confirming first

---

## Data Flow and Measurement Points

Both Android and Streamlit must use the **same protocol** per benchmark run. The CSE resource
tree is identical regardless of protocol — only the transport binding changes.

### Scenario 1 — Telemetry stream (Android → CSE → Streamlit)

```
Android RC (DJI RC)                  ACME CSE                  Dev machine (Streamlit)
     │                                   │                               │
     │  CIN create (op=1, to=cse-in/     │                               │
     │  uxv/telemetry, t_send_ms=T)      │                               │
     ├──────────────────────────────────►│                               │
     │  ◄─── rsc=2001 ───────────────────┤                               │
     │                                   │  SUB notification (m2m:sgn)   │
     │                                   ├──────────────────────────────►│ timestamp_ms = T_recv
     │                                   │  ◄── ACK (rsc=2000) ──────────┤
```

**Metric captured per CIN:**
- `latency_ms = timestamp_ms − t_send_ms` (NTP-dependent: two clocks)
- `payload_bytes` = JSON payload size; `header_bytes` = protocol framing
- `delivered = True` when CIN received; gaps in `seq` = packet loss
- `cin_create_ms = None` (no command from Streamlit in S1)

### Scenario 2 — Command burst (Streamlit → CSE → Android → ACK → Streamlit)

```
Dev machine (Streamlit)              ACME CSE                  Android RC (DJI RC)
     │                                   │                               │
     │  t_cmd_ms = now()                 │                               │
     │  CIN create (to=cse-in/uxv/       │                               │
     │  commands)                        │                               │
     ├──────────────────────────────────►│                               │
     │  ◄─── rsc=2001 ───────────────────┤  cin_create_ms = RTT          │
     │                                   │  SUB notification             │
     │                                   ├──────────────────────────────►│ t_recv_ms = now()
     │                                   │  ◄── ACK ─────────────────────┤ (dispatch command)
     │                                   │  ACK CIN (to=cse-in/uxv/ack)  │ t_exec_ms = now()
     │                                   │◄──────────────────────────────┤
     │  SUB notification (ACK content)   │                               │
     │◄──────────────────────────────────┤                               │
```

**Metrics captured per command:**
- `cin_create_ms` = Streamlit→CSE round-trip (monotonic, single-device, no NTP dependency)
- `latency_ms = t_recv_ms − t_cmd_ms` (NTP-dependent: two clocks)
- `t_exec_ms` = `System.currentTimeMillis()` after Android dispatches command (dispatch overhead)
- `delivered = True` when ACK arrives within 10 s timeout

---

## Metrics Reference

All metrics are captured in `MetricRecord` (see `src/frontend/core/logger.py`). One row per
message (telemetry CIN in S1, command+ACK pair in S2).

| Field | Type | Description | NTP-dep? |
|---|---|---|---|
| `timestamp_ms` | int | Streamlit wall-clock at receive time | — |
| `latency_ms` | float\|None | S1: `timestamp_ms−t_send_ms`; S2: `t_recv_ms−t_cmd_ms` | **Yes** |
| `cin_create_ms` | float\|None | S2 only: Streamlit→CSE CIN round-trip (monotonic) | No |
| `t_cmd_ms` | int\|None | S2: Streamlit wall-clock when command was sent | — |
| `t_recv_ms` | int\|None | S2: Android wall-clock when command was received | — |
| `t_exec_ms` | int\|None | S2: Android wall-clock after command dispatched | — |
| `t_send_ms` | int\|None | S1: Android wall-clock when telemetry CIN was sent | — |
| `seq` | int | Monotonic counter from Android; gaps → packet loss | — |
| `payload_bytes` | int | Bytes in the OneM2M `con` field | — |
| `header_bytes` | int | Protocol framing overhead (WS frame / MQTT topic+header / HTTP headers / CoAP header) | — |
| `delivered` | bool | True if message received within timeout | — |

**Derived paper metrics:**
- **Latency** = mean/median/p95 of `latency_ms` per protocol (note NTP caveat in paper)
- **Throughput** = `n_delivered / duration_s` (S1) or `n_commands / total_time_s` (S2)
- **Packet loss** = gaps in `seq` counter (S1); `1 − n_delivered/n_commands` (S2)
- **Protocol overhead** = `header_bytes / (header_bytes + payload_bytes) × 100`
- **Jitter** = std dev of `latency_ms` within a run

---

## CSE Resource Tree

The same OneM2M resource tree is used for **all 4 protocols** — no per-protocol changes:

```
/id-in               ← CSE-Base identifier (used only in AE registration `to` field)
/cse-in/uxv          ← AE resource (registered by Android on connect)
/cse-in/uxv/telemetry   ← CNT mni=10  — Android pushes telemetry CINs here
/cse-in/uxv/commands    ← CNT mni=5   — Streamlit pushes command CINs here
/cse-in/uxv/commands/sub-commands  ← SUB — notifies Android when new command arrives
/cse-in/uxv/ack         ← CNT mni=200 — Android pushes ACK CINs here (Scenario 2)
```

Streamlit subscribes to `telemetry` (S1) and `ack` (S2); Android subscribes to `commands`.
Subscription notification target (`nu`) = AE originator (`C<serial>` for Android,
`CStreamlit` for Streamlit).

### Per-protocol notification delivery

| Protocol | Notification mechanism | Android `poa` in AE registration |
|---|---|---|
| WebSocket | CSE reuses active WS connection (same originator in `associatedConnections`) | `["ws://cse_ip:8180"]` |
| MQTT | CSE publishes to `/oneM2M/req/id-in/{originator}/json`; AE subscribes | `["mqtt://cse_ip:1883"]` |
| HTTP | CSE POSTs to Android's callback URL; **Android must embed HTTP server** | `["http://rc_ip:callback_port"]` |
| CoAP | CSE sends CoAP PUT to Android's callback; **Android must embed CoAP server** | `["coap://rc_ip:callback_port/notify"]` |

> **HTTP/CoAP reachability:** The CSE Docker container must be able to reach the Android RC's
> LAN IP. Both must be on the same WiFi network. The `rc_ip` and `callback_port` are
> configured at runtime (not hardcoded).

---

## Architecture Decisions

See `docs/adr/` for full records. Key decisions:
- **CSE runtime:** Docker Compose — see `docs/adr/002-cse-deployment.md`
- **CSE implementation:** ACME CSE (Python) — see `docs/adr/001-technology-choices.md`
- **Frontend:** Streamlit — test/research only, not production
- **Android app:** existing project adapted (not from scratch) — DJI SDK v4 already working
- **Drone:** DJI M2EA with Android RC (SDK v4)
- **tc netem (Scenario 3):** runs inside the CSE Docker container, not on host

---

## What NOT to Do

- Do not install Python packages globally
- Do not hardcode IP addresses, ports, or API keys
- Do not mix `data/raw/` and `data/processed/`
- Do not commit `local.properties`, `.env`, or any credential file
- Do not commit raw data files larger than 10 MB
- Do not reorganise notebook cells without confirming
- Do not modify bibliography/references in the paper without confirming
- Do not create new top-level directories without discussing first

---

## Service Startup Order

When running the full system:

1. `docker compose up` from `src/cse/` — wait for CSE to be healthy on :8080
2. Launch Android app on DJI RC — it registers as AE and creates the resource containers
   (`cse-in/uxv/telemetry`, `cse-in/uxv/commands`, `cse-in/uxv/ack`)
3. `streamlit run app.py` from `src/frontend/` (with venv active) — must start AFTER Android,
   because Streamlit's `_ensure_subscription` needs the containers to already exist
4. Drone must be powered on before DJI SDK commands are issued

The Android app is configured with the CSE IP via a settings screen — no hardcoded IP.

### Benchmark Design

- **10 runs per scenario per protocol** — 10 × 3 scenarios × 4 protocols = 120 total runs
- **1 minute per run** — S1/S2: 60 s telemetry; S3: 60 ping commands at 1/s
- Protocol and scenario are selected in the UI; run ID is auto-incremented

### Benchmark Run Procedure (inter-run isolation)

After a S2 (16 msg/s) session, the ACME CSE TinyDB may have residual state that degrades
subsequent runs. Between benchmark sessions (not individual runs within a session):

```powershell
# From src/cse/
docker compose restart
```

This clears the CSE's in-memory notification queue and TinyDB state.
Within a session, runs are isolated by `setTelemetryRate(60000)` sent automatically at end of
each S1/S2 run (in `orchestrator._run_scenario_1`) — no manual restart needed between runs.

---

## Running Tests

Test infrastructure to be defined. When added, document here with exact commands.

---

## Relevant Docs

- `docs/ai-context/project-context.md` — full system context and architecture
- `docs/ai-context/decisions.md` — design decisions and rationale
- `docs/ai-context/cse-dev.md` — development context for `src/cse/` (Docker, config, resource tree)
- `docs/ai-context/frontend-dev.md` — development context for `src/frontend/` (Streamlit, protocol clients, logging)
- `docs/ai-context/analysis-dev.md` — development context for `src/analysis/` (pipeline, statistics, figures)
- `docs/architecture/system-overview.md` — OneM2M resource tree and data flow
- `docs/protocols/test-scenarios.md` — how each protocol is tested
- `docs/adr/` — Architecture Decision Records
