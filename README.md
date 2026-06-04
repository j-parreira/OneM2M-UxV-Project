# OneM2M for Real-Time UxV Operations: A Multi-Protocol Performance Analysis

Research project benchmarking the **OneM2M** middleware framework for unmanned vehicle (UxV)
operations across four communication protocols — **WebSocket, MQTT, HTTP, and CoAP** — using
a DJI Mavic 2 Enterprise Advanced (M2EA) drone controlled from an Android RC.

**Authors:** João Parreira, Pedro Barbeiro  
**Institution:** Instituto Politécnico de Leiria — Mestrado em Engenharia Informática  
**Deliverable:** MDPI journal article, 8–12 pp. — *Mobilidade em Sistemas Computacionais*  
**Deadline:** 2026-06-06

---

## System Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Streamlit Dashboard  (src/frontend/)                        │
│  Benchmark orchestrator · Live telemetry · Results viewer   │
└────────────────────────┬─────────────────────────────────────┘
              WS · MQTT · HTTP · CoAP
┌────────────────────────▼─────────────────────────────────────┐
│  ACME CSE v2025.11  (src/cse/ — Docker)                     │
│  OneM2M middleware · TinyDB · Mosquitto broker               │
└────────────────────────┬─────────────────────────────────────┘
              WS · MQTT · HTTP · CoAP
┌────────────────────────▼─────────────────────────────────────┐
│  Android App  (src/android/ — DJI RC)                       │
│  AE · Telemetry · Command ACK · 4 protocol transports       │
│  DJI Mavic 2 Enterprise Advanced (M2EA)                     │
└──────────────────────────────────────────────────────────────┘
```

The same OneM2M resource tree is used for all four protocols — only the transport binding
changes. Both Android and Streamlit must use the **same protocol** per benchmark run.

---

## Paper Scenarios

| Scenario | Description | Duration | Rate | Paper metric |
|---|---|---|---|---|
| **S1** | Telemetry uplink at **4 msg/s** | 60 s | 250 ms | `latency_ms`, packet loss, overhead |
| **S2** | Telemetry uplink at **16 msg/s** | 60 s | 62 ms | Same as S1 — higher load |
| **S3** | Command ping round-trip at **1/s** | 60 cmds | 1 s | `cin_create_ms` (NTP-free) |

**Target:** 10 runs × 3 scenarios × 4 protocols = **120 runs total**

### S1 — Telemetry 4 msg/s

Android pushes telemetry `m2m:cin` at 4 msg/s for 60 s via `setTelemetryRate(intervalMs=250)`.
Streamlit receives via its SUB on `/cse-in/uxv/telemetry`.

**Metric:** `latency_ms = timestamp_ms − t_send_ms` (NTP-dependent; two independent clocks).
Packet loss derived from gaps in the Android `seq` counter.

### S2 — Telemetry 16 msg/s

Same as S1 at 16 msg/s (62 ms interval). Simulates 4 simultaneous drones.

**Expected result — ACME CSE v2025.11 TinyDB throughput ceiling:**

ACME CSE v2025.11 uses TinyDB (a file-based Python store) for persistence. Notification
delivery is processed synchronously inside the CSE's single asyncio event loop. Under load,
TinyDB writes block the loop and cap effective notification throughput at **≈ 4.9 msg/s**
regardless of incoming rate.

At 16 msg/s this creates a sustained surplus of **11.1 notifications/s**:

| Quantity | Value |
|---|---|
| Input rate | 16.0 msg/s |
| CSE delivery ceiling | ~4.9 msg/s |
| Queue growth rate | ~11.1 entries/s |
| Notifications queued after 60 s run | ~666 |
| Time to clear queue at 4.9 msg/s | ~136 s |
| 3 s drain at run start clears | ~15 entries |

**Observed effects during S2 (60 s run):**
- Packet loss ≈ 69 % (notifications queued faster than delivered)
- `latency_ms` grows linearly: 180 ms → 84 s as queue depth increases
- This is the paper result, not a bug — it characterises the CSE throughput ceiling

**Cross-run contamination (critical for data quality):**

The ~666 queued notifications persist in TinyDB across runs. Without a CSE restart:
- **S2 → S1:** Stale telemetry arrives during the S1 measurement window with
  `t_send_ms` from 60–180 s ago → `latency_ms` inflated by 60,000–180,000 ms.
  Empirically measured: `latency_ms` mean = 90,090 ms on contaminated run.
- **S2 → S2:** CSE delivers stale run N notifications during run N+1 measurement
  (stale notifications are processed first, FIFO). Run N+1 may deliver zero of its
  own notifications within the 60 s window. Data is invalid.
- **S2 → S3:** Stale telemetry congests the CSE asyncio loop during S3 command window.
  First ~9 commands show `cin_create_ms` = 6–7 s (vs. 125–172 ms normal).
  Documented as a paper result showing CSE recovery time.

> **`docker compose restart` is required before every S2 run.**
> After an S2 run, wait for the CSE to be healthy before starting the next run.

### S3 — Command ping (1 cmd/s)

Streamlit sends 60 `ping` commands at 1 cmd/s. Android ACKs each immediately (no DJI SDK
action) and posts an ACK `m2m:cin` to `/cse-in/uxv/ack`.

**Primary metric:** `cin_create_ms` — Streamlit→CSE CIN round-trip measured with
`time.monotonic_ns()` on the same machine. **NTP-independent.** Values: WS ≈ 125–172 ms,
MQTT ≈ 93–125 ms.

**Do not use** `latency_ms` for S3 analysis — it equals `t_recv_ms − t_cmd_ms` across two
clocks; the Android RC clock is typically ~1880 ms behind the dev machine, so raw values are
negative. Use `cin_create_ms` for all S3 latency and jitter figures in the paper.

---

## Data Flow

### S1/S2 — Telemetry uplink

```
Android RC                    ACME CSE               Streamlit
    │  CIN create              │                         │
    │  (t_send_ms = now())     │                         │
    ├─────────────────────────►│                         │
    │  ◄── rsc=2001 ───────────┤                         │
    │                          │  SUB notification       │
    │                          ├────────────────────────►│  timestamp_ms = now()
    │                          │  ◄── rsc=2000 ──────────┤
```

### S3 — Command ping

```
Streamlit                     ACME CSE               Android RC
    │  t_cmd_ms = now()        │                         │
    │  CIN create (ping)       │                         │
    ├─────────────────────────►│                         │
    │  ◄── rsc=2001 ───────────┤  cin_create_ms = RTT   │
    │                          │  SUB notification       │
    │                          ├────────────────────────►│  t_recv_ms = now()
    │                          │◄────────────────────────┤  ACK CIN, t_exec_ms = now()
    │  SUB notification (ACK)  │                         │
    │◄─────────────────────────┤                         │
```

---

## Metrics Reference

All measurements are stored in `MetricRecord` — one CSV row per message
(`src/frontend/core/logger.py`).

| Field | Type | NTP? | Description |
|---|---|---|---|
| `timestamp_ms` | int | — | Streamlit wall-clock at receive time |
| `seq` | int | — | Monotonic counter from Android; gaps → packet loss |
| `latency_ms` | float\|None | **Yes** | S1/S2: `timestamp_ms − t_send_ms` (positive, NTP-inflated); S3: `t_recv_ms − t_cmd_ms` (typically negative — Android clock behind) |
| `cin_create_ms` | float\|None | No | S3 only: Streamlit→CSE CIN round-trip (monotonic, same machine) — **primary S3 metric** |
| `t_send_ms` | int\|None | Yes | S1/S2: Android wall-clock at CIN send |
| `t_cmd_ms` | int\|None | — | S3: Streamlit wall-clock at command send |
| `t_recv_ms` | int\|None | Yes | S3: Android wall-clock at command receipt |
| `t_exec_ms` | int\|None | Yes | S3: Android wall-clock after command dispatch |
| `payload_bytes` | int | — | Bytes in the OneM2M `con` field |
| `header_bytes` | int | — | Protocol framing overhead |
| `delivered` | bool | — | True if message received within timeout |

**Derived paper metrics:**

| Metric | Formula | Notes |
|---|---|---|
| S1/S2 latency | mean/median/p95 of `latency_ms` | NTP-inflated; offset δ ≈ `mean(latency_ms_S3) − cin_create_ms_S3/2` |
| S3 latency | mean/median/p95 of `cin_create_ms` | NTP-free; do not use `latency_ms` |
| Throughput | `n_delivered / duration_s` (S1/S2) | or `n_cmds / total_time_s` (S3) |
| Packet loss | gaps in `seq` (S1/S2) | or `1 − n_delivered/n_cmds` (S3) |
| Overhead | `header_bytes / (header_bytes + payload_bytes) × 100` | |
| Jitter | std dev of `cin_create_ms` (S3) | or `latency_ms` (S1/S2) |

---

## Protocols Under Test

| Protocol | Port | Transport | Notification delivery | Status |
|---|---|---|---|---|
| WebSocket | 8180 | TCP | CSE reuses active WS connection (`associatedConnections`) | ✅ |
| MQTT | 1883 | TCP | CSE publishes to `/oneM2M/req/id-in/{originator}/json` via Mosquitto | ✅ |
| HTTP | 8080 | TCP | CSE POSTs to embedded callback server (Streamlit: 8090; Android: 8181) | ✅ |
| CoAP | 5683 | UDP | HTTP/TCP workaround (Docker Desktop blocks UDP from containers) | ✅ |

> **CoAP notification constraint:** Docker Desktop on Windows does not route UDP from
> containers to LAN devices. CoAP notification delivery uses HTTP/TCP (embedded HTTPServer
> on both sides) as a lab-environment workaround. Outgoing CoAP/UDP requests are unaffected.
> Documented as a methodological constraint in the paper.

---

## Sub-project Status

| Directory | Stack | Status |
|---|---|---|
| `src/android/` | Java, DJI SDK v4 | ✅ All 4 protocols verified end-to-end (2026-05-31) |
| `src/cse/` | Docker, ACME CSE v2025.11 | ✅ Complete — WS keepalive + CoAP NOTIFY patches applied |
| `src/frontend/` | Python, Streamlit | ✅ Complete — S1/S2/S3 orchestration, logging, results viewer |
| `src/analysis/` | Python, Jupyter | 🔜 Scripts scaffolded — pending data collection |

**Current phase:** benchmark data collection (120 runs target).

---

## OneM2M Resource Tree

The same tree is used for all 4 protocols. Android creates it on startup; Streamlit subscribes.

```
/id-in                              ← CSE-Base (ty=5)
└── /cse-in/uxv                     ← AE (ty=2) — registered by Android
      ├── /cse-in/uxv/telemetry     ← CNT (mni=10) — Android pushes telemetry CINs
      │     └── sub-streamlit-tel   ← SUB — Streamlit subscription (S1/S2 only)
      ├── /cse-in/uxv/commands      ← CNT (mni=5)  — Streamlit pushes command CINs
      │     └── sub-commands        ← SUB — Android subscription
      └── /cse-in/uxv/ack           ← CNT (mni=200) — Android pushes ACK CINs
            └── sub-streamlit-ack   ← SUB — Streamlit subscription (S3 only)
```

---

## Setup

### Requirements

- Docker Desktop (CSE)
- Python 3.11+ (frontend, analysis)
- Android Studio + DJI Mobile SDK v4 (Android app)
- DJI Mavic 2 Enterprise Advanced + DJI RC on the same LAN as the dev machine

### Startup Order

**This order is mandatory** — Streamlit's subscription creation requires Android's containers
to already exist in the CSE.

**1. ACME CSE**

```bash
cd src/cse
docker compose up --build   # first time
docker compose up           # subsequent starts
```

Health check (ready when this returns HTTP 200 with `"ty":5`):

```bash
curl http://localhost:8080/id-in \
  -H "X-M2M-RI: t" -H "X-M2M-Origin: CAdmin" -H "X-M2M-RVI: 3"
```

Web UI (resource tree browser): `http://localhost:8080/webui`

**2. Android App** (on DJI RC)

1. Power on M2EA drone and connect to RC via OcuSync
2. Open **UxV Benchmark** on the RC
3. Tap **Registar App** (DJI SDK registers; requires internet on first run)
4. Tap **Open Benchmark**
5. Enter the CSE host LAN IP → **Connect CSE**

The app registers as AE and creates `/cse-in/uxv/telemetry`, `/cse-in/uxv/commands`,
`/cse-in/uxv/ack`.

**3. Streamlit Dashboard** (dev machine)

```bash
cd src/frontend
# Windows PowerShell
.venv\Scripts\Activate.ps1
# macOS / Linux / WSL
source .venv/bin/activate

streamlit run app.py
```

First-time setup:

```bash
cd src/frontend
python -m venv .venv
source .venv/bin/activate   # or .venv\Scripts\Activate.ps1
pip install -r requirements.txt
cp .env.example .env        # fill in CSE_HOST and other IPs
```

### Analysis Environment

```bash
cd src/analysis
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
jupyter lab notebooks/
```

### Windows / LAN Networking Note

The dev machine Wi-Fi network profile must be set to **Private** for CSE ports to be
reachable from the Android RC:

```powershell
Set-NetConnectionProfile -Name "<wifi-ssid>" -NetworkCategory Private
```

Docker Desktop uses WSL2 in **mirrored networking mode** (`networkingMode=mirrored`).

---

## Benchmark Procedure

### Running a benchmark

1. Open the Benchmark page (page 2) in the Streamlit dashboard
2. Select **Protocol** and **Paper scenario** (S1 / S2 / S3)
3. Click **▶ Run** — the orchestrator runs one complete run and writes
   `data/raw/<protocol>_s<N>_<YYYYMMDD>_run<NNN>.csv` + companion `.json` sidecar
4. Repeat until 10 runs per (protocol × scenario)

### Inter-run isolation

After every S2 run (16 msg/s), **restart the CSE before the next run** regardless of scenario
or protocol. The ~666 notifications queued in TinyDB take ~136 s to clear at the CSE's 4.9/s
ceiling — far longer than the 3 s drain the orchestrator performs at run start.

```bash
cd src/cse
docker compose restart
# wait for health check before opening Streamlit
curl http://localhost:8080/id-in -H "X-M2M-RI: t" -H "X-M2M-Origin: CAdmin" -H "X-M2M-RVI: 3"
```

After S1 or S3 runs no restart is needed — neither generates a backlog (4 msg/s ≤ 4.9/s
ceiling for S1; 1 cmd/s for S3).

**Contamination risk by transition:**

| Transition | Risk | Action |
|---|---|---|
| S2 → S1 | Stale telemetry inflates `latency_ms` by 60–180 s | Restart CSE |
| S2 → S2 | Stale run N notifications delivered during run N+1 (data invalid) | Restart CSE |
| S2 → S3 | First ~9 commands slow (6–7 s) from asyncio congestion | Restart CSE (or document as paper result) |
| S1 → any | No backlog (4 msg/s ≤ ceiling) | No restart needed |
| S3 → any | No backlog (1 cmd/s) | No restart needed |

**Recommended data collection order** to minimise restarts:

1. All **S3** runs — 4 protocols × 10 runs (no restart between)
2. All **S1** runs — 4 protocols × 10 runs (no restart between)
3. **S2** runs — restart CSE before each individual run

Within a session, the orchestrator automatically sends `setTelemetryRate(60000)` at the end
of each S1/S2 run to stop the Android from pushing telemetry between runs.

### Data files

```
data/
├── raw/                  ← never modify; one CSV + one JSON per run
│   ├── websocket_s1_20260601_run001.csv
│   ├── websocket_s1_20260601_run001.json   ← sidecar: versions, config, notes
│   └── …
└── processed/            ← cleaned/aggregated datasets from analysis pipeline
```

**Filename format:** `<protocol>_s<paper_scenario>_<YYYYMMDD>_run<NNN>.csv`

Paper scenario mapping:
- `s1` — telemetry 4 msg/s (rate stored in sidecar, not filename)
- `s2` — telemetry 16 msg/s
- `s3` — command ping (1/s)

---

## Project Structure

```
/
├── data/
│   ├── raw/               ← immutable experiment outputs (CSV + JSON, git-ignored if >10 MB)
│   └── processed/         ← analysis outputs
├── docs/
│   ├── architecture/      ← system design, resource tree, lab constraints
│   ├── protocols/         ← per-protocol configuration and test scenarios
│   ├── adr/               ← Architecture Decision Records
│   └── ai-context/        ← developer context for AI-assisted development
├── src/
│   ├── android/           ← Android app (DJI SDK v4, 4 protocol transports)
│   ├── cse/               ← ACME CSE Docker setup + patches
│   ├── frontend/          ← Streamlit dashboard (benchmark + manual + results)
│   └── analysis/          ← metric pipeline, statistics, figures
├── CLAUDE.md              ← project-level AI assistant instructions
└── README.md
```

---

## Key Documents

| Document | Content |
|---|---|
| `CLAUDE.md` | Project conventions, data pipeline, metrics reference, resource tree |
| `src/frontend/CLAUDE.md` | Streamlit architecture, protocol clients, known issues log |
| `src/cse/README.md` | CSE Docker setup, patches, resource tree, useful commands |
| `src/android/README.md` | Android architecture, protocol transports, telemetry/command schemas |
| `docs/architecture/system-overview.md` | Full protocol flows, lab constraints, message formats |
| `docs/ai-context/frontend-dev.md` | Streamlit development context |
| `docs/ai-context/cse-dev.md` | ACME CSE integration notes |

---

## Academic Context

- **Institution:** Instituto Politécnico de Leiria (IPL)
- **Programme:** Mestrado em Engenharia Informática
- **Course:** Mobilidade em Sistemas Computacionais
- **Deliverable:** MDPI journal article (format MDPI, 8–12 pp., English, IMRaD)
- **Deadline:** 2026-06-06 (hard)

---

## License

Academic research project — all rights reserved pending publication.
