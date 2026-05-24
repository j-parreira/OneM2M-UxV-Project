# OneM2M for Real-Time UxV Operations: A Multi-Protocol Performance Analysis

## Overview

This project benchmarks the **OneM2M** middleware framework for unmanned vehicle (UxV) operations
across four communication protocols: **MQTT, WebSocket, HTTP, and CoAP**. The system runs on a
DJI Mavic 2 Enterprise Advanced (M2EA) controlled by an Android-based DJI RC. Deliverable:
relatório académico para a cadeira **Mobilidade em Sistemas Computacionais** (IPL Leiria).

## System Architecture

```
┌─────────────────────────────────────────────────────┐
│              Test Control Layer                      │
│         Streamlit Dashboard  (src/frontend/)         │
└────────────────────┬────────────────────────────────┘
                     │ MQTT / WebSocket / HTTP / CoAP
┌────────────────────▼────────────────────────────────┐
│              OneM2M Middleware Layer                 │
│              ACME CSE  (src/cse/)                   │
└────────────────────┬────────────────────────────────┘
                     │ DJI SDK v4 / Binding Protocol
┌────────────────────▼────────────────────────────────┐
│              UxV Layer                               │
│   ACMECSE Android App (src/android/)                │
│   DJI Mavic 2 Enterprise Advanced (M2EA)            │
└─────────────────────────────────────────────────────┘
```

## Sub-projects

| Directory        | Stack          | Purpose                                        |
|-----------------|----------------|------------------------------------------------|
| `src/android/`  | Java, DJI SDK v4   | Android app running on the DJI RC controller  |
| `src/frontend/` | Python, Streamlit  | Test dashboard — command dispatch & telemetry |
| `src/cse/`      | Python, ACME CSE   | OneM2M CSE server configuration & deployment  |
| `src/analysis/` | Python             | Metric collection, statistical analysis, plots |
| `docs/`         | Markdown           | Architecture, protocol specs, ADRs, AI context |

## Protocols Under Test

| Protocol | Port | Transport | OneM2M Binding | Status |
|---|---|---|---|---|
| WebSocket | 8180 | TCP | Persistent; flat JSON; `oneM2M.json` subprotocol | ✅ Both endpoints |
| MQTT | 1883 | TCP | AE client → Mosquitto broker → CSE MQTT client | ✅ Both endpoints |
| HTTP | 8080 | TCP | RESTful POST; `/cse-in/...` paths; embedded callback | ✅ Both endpoints |
| CoAP | 5683 | UDP | CON POST; flat JSON body; embedded callback server | ✅ Both endpoints |

## Performance Metrics

| Metric              | Unit      | Description                                  |
|--------------------|-----------|----------------------------------------------|
| End-to-end latency | ms        | Time from command issued to drone ACK        |
| Throughput         | msg/s, KB/s | Sustained message rate under load           |
| Packet loss        | %         | Messages lost under degraded network         |
| Protocol overhead  | %         | Header bytes vs payload bytes                |
| Jitter             | ms        | Variance in latency over time                |

## Hardware

- **Drone:** DJI Mavic 2 Enterprise Advanced (M2EA)
- **Controller:** DJI RC with integrated Android screen
- **SDK:** DJI Mobile SDK v4 (Android)

## Project Structure

```
/
├── docs/
│   ├── architecture/      # System design and OneM2M resource tree
│   ├── protocols/         # Protocol configuration and test scenarios
│   ├── ai-context/        # Context files for AI-assisted development
│   └── adr/               # Architecture Decision Records
├── src/
│   ├── android/           # ACMECSE Android app (existing project)
│   ├── frontend/          # Streamlit test dashboard
│   ├── cse/               # ACME CSE server + config
│   └── analysis/          # Metric scripts, notebooks, plots
├── data/
│   ├── raw/               # Unmodified experiment outputs (CSV/JSON)
│   └── processed/         # Cleaned and aggregated datasets
├── CLAUDE.md              # AI assistant instructions for this project
└── README.md
```

## Setup

### Requirements

- Python 3.11+
- Android Studio (Hedgehog+) + DJI Mobile SDK v4
- Git

Each Python sub-project uses its own isolated virtual environment.

### ACME CSE (OneM2M Server)

```bash
cd src/cse
docker compose up --build
# Wait ~45s for containers to be healthy

# Smoke test (CSE ready when this returns HTTP 200 with "ty":5):
curl http://localhost:8080/id-in \
  -H "X-M2M-RI: t" -H "X-M2M-Origin: CAdmin" -H "X-M2M-RVI: 3"

# Web UI (resource tree browser):
# http://localhost:8080/webui
```

Requires Docker Desktop. See `src/cse/README.md` for full details.

### Streamlit Dashboard

```bash
cd src/frontend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
streamlit run app.py
```

### Analysis Scripts

```bash
cd src/analysis
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
# Run a notebook: jupyter lab notebooks/
# Run a script: python scripts/<script>.py
```

### Android App

Open `src/android/` in Android Studio. Requires DJI SDK v4 and a valid DJI developer API key
(set in `local.properties`, never committed).

## Current State

| Component | Status | Notes |
|---|---|---|
| `src/android/` | ✅ Complete | All 4 transports (WS/MQTT/HTTP/CoAP); protocol selector spinner; compile + build passing |
| `src/cse/` | ✅ Complete | All 4 protocols active; ACME CSE v2025.11 integration verified |
| `src/frontend/` | ✅ Complete | All 4 protocol clients; Scenario 1 & 2 runners; CSV logger; results viewer |
| `src/analysis/` | 🔜 Not built | Analysis pipeline — after benchmark data collection |

**Next step:** end-to-end integration test (all 4 protocols vs real CSE), then benchmark runs (≥30 runs × 4 protocols × 2 scenarios).

**Deadline:** 2026-06-06 (relatório académico, Mobilidade em Sistemas Computacionais)

## Academic Context

- **Institution:** Instituto Politécnico de Leiria (IPL)
- **Programme:** Mestrado em Engenharia Informática
- **Deliverable:** Relatório académico — cadeira Mobilidade em Sistemas Computacionais
- **Language:** Portuguese (report) / English (possible future conference paper)

## License

Academic research project. All rights reserved pending publication.
