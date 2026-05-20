# Design Decisions Log

> Short rationale for choices made during development. For formal decisions, see `docs/adr/`.

## Technology Stack

| Decision              | Choice              | Reason                                                   |
|----------------------|---------------------|----------------------------------------------------------|
| OneM2M CSE           | ACME CSE (Python)   | Open-source, actively maintained, easy local setup       |
| Frontend             | Streamlit (Python)  | Research-only use; integrates natively with Python analysis stack |
| Analysis             | Python (pandas, matplotlib, seaborn) | Standard for academic data analysis |
| Android language     | Kotlin              | DJI SDK v4 supports Kotlin, modern Android standard      |

## Infrastructure

| Decision | Choice | Reason |
|---|---|---|
| ACME CSE runtime | Docker Compose | Avoids Windows/Python friction; gives Linux for `tc netem`; portable |
| tc netem (Scenario 3) | Inside CSE container | Host Windows does not support netem; container has full Linux network stack |
| CSE IP discovery (Android) | Settings screen in Android app | No hardcoded IPs; RC and dev machine on same WiFi |

## Phased Delivery (June 6 constraint)

Priority order given the 2026-06-06 hard deadline:

1. **CSE Docker setup** — ACME CSE running with HTTP + MQTT enabled
2. **Android app adaptation** — integrate protocol bindings, test AE registration
3. **Streamlit orchestrator** — trigger benchmark runs, log to `data/raw/`
4. **HTTP + MQTT benchmarks** — Scenarios 1 & 2 — minimum for course paper
5. **Analysis + plots** — latency, throughput, overhead for ≥ 2 protocols
6. **WebSocket + CoAP** — Scenarios 1 & 2 — stretch goal for June 6, definite for conference paper
7. **Scenario 3 (degraded network)** — after June 6

## Experimental Design

- Each protocol tested in identical network conditions (same LAN, controlled environment)
- Minimum 30 runs per protocol × scenario for statistical validity
- Scenarios: (1) idle telemetry stream, (2) command burst, (3) degraded network (packet loss emulation)
- Random seeds logged for any stochastic elements

## Data Format

- Raw data: CSV with columns `timestamp_ms, protocol, scenario, latency_ms, payload_bytes, header_bytes, delivered`
- One file per run, named `<protocol>_<scenario>_<YYYYMMDD>_run<N>.csv`
- Metadata sidecar: `<same_name>.json` with software versions, config params, operator notes
