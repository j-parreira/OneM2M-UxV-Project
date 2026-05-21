# Design Decisions Log

> Short rationale for choices made during development. For formal decisions, see `docs/adr/`.
> Last updated: 2026-05-21.

---

## Technology Stack

| Decision | Choice | Reason |
|---|---|---|
| OneM2M CSE | ACME CSE v2025.11 | Open-source, actively maintained, supports all 4 protocols |
| Frontend | Streamlit (Python) | Research-only; integrates natively with Python analysis stack |
| Analysis | Python (pandas, scipy, matplotlib, seaborn) | Standard for academic data analysis |
| Android language | Java (Java files adapted from production Kotlin project) | DJI SDK v4 Java API |
| CSE transport (Android) | WebSocket first, then MQTT/HTTP/CoAP | WS is bidirectional, persistent; most natural for push notifications |

---

## Infrastructure

| Decision | Choice | Reason |
|---|---|---|
| ACME CSE runtime | Docker Compose | Avoids Windows/Python friction; gives Linux for `tc netem`; portable |
| tc netem (Scenario 3) | Inside CSE container | Windows host does not support netem; container has full Linux network stack |
| CSE IP discovery (Android) | UI field in main screen | No hardcoded IPs; RC and dev machine on same WiFi |
| Mosquitto broker | Separate Docker service | ACME CSE is MQTT *client*, not broker — confirmed from source code |
| ACP checks | Disabled (`enableACPChecks=false`) | ACME CSE v2025.11 does not auto-create ACP for AEs; lab environment |

---

## ACME CSE v2025.11 — Protocol Integration Decisions

After testing against the real CSE, the following choices were made:

| Decision | Choice | Reason |
|---|---|---|
| WebSocket format | Flat JSON (no `m2m:rqp` wrapper) | v2025.11 requires flat format; wrapper causes `rsc=4000` |
| `poa` in AE registration | `['ws://host:8180']` | Required for notification delivery; without it, CSE silently discards |
| `aei` in AE body | Omitted | Non-provision attribute in v2025.11; CSE assigns from originator |
| `cnf` in CIN body | Omitted | `"application/json"` fails validation in v2025.11 |
| `nct` in subscription | Omitted | `nct=2` + `net=[3]` combination invalid in v2025.11 |
| HTTP resource paths | `/cse-in/...` prefix | Resources are children of CSE-Base (rn="cse-in"), not CSE-ID (/id-in) |

---

## Android App Architecture

| Decision | Choice | Reason |
|---|---|---|
| Protocol abstraction | `ProtocolClient` interface | 4 protocols need same interface; decouples transport from flight/telemetry logic |
| OneM2M session layer | `OneM2MSession` (wraps transport) | Protocol-agnostic; all 4 transports share the same AE registration sequence |
| Telemetry rate | Configurable via command `setTelemetryRate` | Scenario 1 requires 1/5/10 msg/s; hardcoded 250ms insufficient |
| Command ACK | CIN in `/cse-in/uxv/ack` with timestamps | Enables device-to-device latency measurement (Scenario 2) without NTP |
| Packet loss detection | `seq` counter in telemetry JSON | Monotonic counter; gaps = lost messages; no server-side tracking needed |

---

## Phased Delivery (June 6 constraint)

Priority order given the 2026-06-06 hard deadline:

1. ✅ **CSE Docker setup** — ACME CSE v2025.11 running with all 4 protocols
2. ✅ **Android app** — full OneM2M AE, WebSocket verified, ACK + seq implemented
3. 🔜 **Streamlit orchestrator** — trigger benchmark runs, log to `data/raw/`
4. 🔜 **WebSocket + MQTT benchmarks** — Scenarios 1 & 2 — minimum for course paper
5. 🔜 **Analysis + plots** — latency, throughput, overhead, Kruskal-Wallis tests
6. ⏳ **HTTP + CoAP** — stretch goal for June 6; definite for conference paper
7. ⏳ **Scenario 3 (degraded network)** — after June 6

---

## Experimental Design

- Each protocol tested in identical network conditions (same LAN, controlled environment)
- Minimum 30 runs per protocol × scenario for statistical validity
- Scenarios: (1) idle telemetry stream (1/5/10 msg/s), (2) command burst (50 cmds),
  (3) degraded network (2%, 5%, 10% packet loss via tc netem)
- Random seeds logged for any stochastic elements
- Statistical test: Kruskal-Wallis H (non-parametric) + Dunn's post-hoc + Cliff's delta

---

## Data Format

### Raw telemetry CSV columns

`timestamp_ms, run_id, protocol, scenario, direction, latency_ms, payload_bytes,
 header_bytes, delivered, seq, t_send_ms, t_cmd_ms, t_recv_ms, t_exec_ms`

Key columns:
- `seq` — from Android `TelemetryManager.seqCounter` (monotonic per session)
- `t_send_ms` — `System.currentTimeMillis()` at Android send time (Scenario 1 uplink)
- `t_cmd_ms` / `t_recv_ms` / `t_exec_ms` — from ACK CIN (Scenario 2 downlink)

### File naming

One CSV per run: `<protocol>_s<scenario>_<YYYYMMDD>_run<N>.csv`

Companion JSON sidecar: `<same_stem>.json` with software versions, config params, notes.
