# Project Context — OneM2M UxV Multi-Protocol Study

> This file is intended for AI assistants and developers starting work on this project.
> Read this before touching any sub-project.
> Last updated: 2026-05-21.

## What This Project Does

This is an academic benchmarking study. We connect a **DJI Mavic 2 Enterprise Advanced (M2EA)**
drone to an **ACME CSE (OneM2M)** server using four different communication protocols, then
measure performance metrics to compare them. The goal is a peer-reviewed paper at an IEEE/ACM
conference.

The system has three layers:

1. **UxV Layer** — The drone (M2EA) + DJI RC controller running the Android benchmark app. The
   Android app registers as an Application Entity (AE) on the OneM2M CSE, pushes telemetry
   (GPS, battery, altitude, speed, gimbal, camera), and receives commands (takeoff, land,
   waypoints, setZoom, etc.) via subscription notifications.

2. **OneM2M Middleware Layer** — ACME CSE v2025.11 running in Docker Compose. It manages
   the resource tree (`/cse-in/uxv/telemetry`, `/cse-in/uxv/commands`, `/cse-in/uxv/ack`)
   and routes messages between the drone app and the dashboard via subscriptions.

3. **Test Control Layer** — A Streamlit dashboard (to be built) that dispatches commands to
   the drone and logs telemetry + ACKs. It is the orchestrator for benchmark runs.

---

## Protocols Under Test

Each protocol is tested independently against the same CSE:

| Protocol | Port | OneM2M binding | Status |
|---|---|---|---|
| WebSocket | 8180 | Persistent connection, flat JSON, `oneM2M.json` subprotocol | ✅ Implemented + verified |
| MQTT | 1883 | AE client → Mosquitto broker → CSE MQTT client | 🔜 Next |
| HTTP | 8080 | REST POST/GET, OneM2M content types | 🔜 After MQTT |
| CoAP | 5683/udp | UDP, Confirmable messages | 🔜 After HTTP |

---

## Metrics Collected

For each protocol × scenario combination:

- **Latency** (ms): `t_recv_ms - t_cmd_ms` (Scenario 2, device-to-device via ACK CIN)
  or `t_receive - t_send_ms` (Scenario 1, requires NTP sync)
- **Throughput** (msg/s): messages delivered per second
- **Packet loss** (%): gaps in the `seq` counter from Android app
- **Protocol overhead** (%): `header_bytes / (header_bytes + payload_bytes) × 100`
- **Jitter** (ms): standard deviation of per-message latency within a run

---

## Directory Roles

```
src/android/   ⭐ COMPLETE — Android app on DJI RC. Full OneM2M AE (WebSocket).
               Kotlin, DJI SDK v4. Tested against ACME CSE v2025.11.

src/cse/       ⭐ COMPLETE — ACME CSE v2025.11 in Docker Compose.
               HTTP :8080, WebSocket :8180, CoAP :5683/udp + Mosquitto :1883.
               Verified working (9/9 end-to-end test steps passing).

src/frontend/  🔜 NOT BUILT — Streamlit benchmark orchestrator + data logging.
               Needs: protocol clients, CSV logger, benchmark runner, UI.

src/analysis/  🔜 NOT BUILT — Analysis scripts and Jupyter notebooks.
               Needs: data loading, statistics, publication-quality figures.

data/raw/      Empty — populated during benchmark runs.
data/processed/ Empty — populated by analysis scripts.

docs/          Reference documentation. Read ai-context/ before starting any sub-project.
```

---

## Key Constraints

- **DJI SDK v4** is Android-only and requires a registered DJI developer key (never committed)
- The Android app runs on the DJI RC controller, not on a phone
- ACME CSE must be reachable from the RC controller (same LAN — WiFi hotspot works)
- CoAP uses UDP — DTLS not supported in ACME CSE v2025.11; use without TLS
- All parameters must be reproducible: log timestamps, software versions, config state
- **Deadline:** 2026-06-06 (course paper); minimum 2 protocols + preliminary results

---

## Deadlines

| Deadline | Artefact | Status |
|---|---|---|
| **2026-06-06** | Course paper — Mobilidade em Sistemas Computacionais | 🔴 Hard |
| After June 6 | IEEE/ACM conference paper (extended version) | — |

Course paper minimum: system architecture, methodology, ≥ 2 protocols with results.

---

## Infrastructure

ACME CSE runs in **Docker Compose** (`src/cse/`) on Windows 11 — not as a bare Python process.
This gives a Linux environment for `tc netem` (Scenario 3) without WSL or a separate VM.

The DJI RC and dev machine are on the **same WiFi network**. The Android app discovers
the CSE IP via a field in the main screen.

---

## Current State (as of 2026-05-21)

### Completed and verified

- **Android app** (`src/android/`) — full OneM2M AE for WebSocket:
  - AE registration, 4 containers, subscription, ACK container
  - Telemetry: 22 fields at configurable rate (250ms default), `seq` + `t_send_ms`
  - Commands: 17 flight commands + `setTelemetryRate` benchmark control
  - Command ACK: `{command, seq_cmd, t_cmd_ms, t_recv_ms, t_exec_ms}`
  - Reconnect backoff (1s→30s), request timeout (10s)
  - Tested: 9/9 flow test steps passing against real ACME CSE

- **CSE** (`src/cse/`) — Docker Compose with ACME CSE v2025.11:
  - All 4 protocols active (HTTP, WebSocket, MQTT, CoAP)
  - Configuration verified and documented (10 v2025.11 specific gotchas)
  - `enableACPChecks=false` (no ACP auto-created for AEs in v2025.11)
  - Test scripts in `src/cse/test_*.py`

### Remaining (priority order for June 6)

1. `src/frontend/` — Streamlit orchestrator (BLOCKING for data collection)
2. MQTT protocol client in Android (`MqttProtocolClient.java`)
3. Benchmark runs: WebSocket + MQTT, Scenarios 1 & 2, 30 runs each
4. `src/analysis/` — statistics + figures
5. HTTP + CoAP clients (stretch goal for June 6)

---

## Master Benchmark Flow

Full end-to-end workflow for one complete benchmark run:

```
1. SETUP
   cd src/cse && docker compose up          # wait ~45s for healthy
   # Launch Android app on RC, enter CSE IP, tap "Connect CSE"
   # App auto-registers: /cse-in/uxv/* containers created
   # Verify: http://localhost:8080/webui → /cse-in/uxv tree visible

2. VALIDATE (pre-benchmark)
   # Android status field shows "OneM2M ready — C<serial>"
   # Streamlit: test command → verify drone responds + ACK arrives

3. SCENARIO 1 — Telemetry stream
   # For each rate (1/5/10 msg/s), for each protocol (WS/MQTT/HTTP/CoAP):
   Streamlit sends: {"command": "setTelemetryRate", "intervalMs": 200}
   Streamlit subscribes to /cse-in/uxv/telemetry → logs seq, t_send_ms, t_receive
   Run 5 minutes → save data/raw/websocket_s1_<date>_run<N>.csv

4. SCENARIO 2 — Command burst
   # For each protocol:
   Streamlit sends 50 × {"command": "takeoff", "seq_cmd": N, "t_cmd_ms": T}
   Android ACKs each → Streamlit logs t_recv_ms - t_cmd_ms per command
   Run until 50 ACKs or 60s timeout → save data/raw/websocket_s2_<date>_run<N>.csv

5. SCENARIO 3 — Degraded network (after June 6)
   docker exec acme-cse tc qdisc add dev eth0 root netem loss 5%
   Repeat Scenarios 1 & 2
   docker exec acme-cse tc qdisc del dev eth0 root

6. ANALYSIS
   cd src/analysis
   python scripts/01_load_validate.py    # validate CSVs
   python scripts/02_compute_stats.py   # mean, median, percentiles, CI
   python scripts/03_statistical_tests.py  # Kruskal-Wallis + Dunn
   jupyter lab notebooks/04_paper_figures.ipynb  # publication figures
```

---

## ACME CSE v2025.11 — Key Integration Notes

After extensive testing, 10 incompatibilities were found between standard oneM2M
documentation and ACME CSE v2025.11 WebSocket behaviour. Full details in
`docs/ai-context/cse-dev.md`. Summary of the most critical:

1. **Flat JSON** — No `m2m:rqp`/`m2m:rsp` wrappers; `rvi="3"` mandatory
2. **URL structure** — Resources at `/cse-in/...` not `/id-in/...`
3. **`poa` required** — Without it, subscription notifications are silently discarded
4. **No `aei` in AE body** — Non-provision attribute; CSE assigns from `fr` field
5. **No `cnf` in CIN** — `"application/json"` fails validation
6. **No ACP auto-created** — `enableACPChecks=false` needed for lab use
