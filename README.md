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

| Protocol | Port | Transport | Outgoing requests | Notification delivery | Status |
|---|---|---|---|---|---|
| WebSocket | 8180 | TCP | Flat JSON on persistent WS (`oneM2M.json`) | CSE reuses active WS connection | ✅ Both endpoints |
| MQTT | 1883 | TCP | Publish to `/oneM2M/req/{orig}/id-in/json` (QoS 1) | CSE publishes to `/oneM2M/req/id-in/{orig}/json` | ✅ Both endpoints |
| HTTP | 8080 | TCP | REST POST with `X-M2M-Origin/RI/RVI` headers | CSE POSTs to embedded callback server (port 8090/8181) | ✅ Both endpoints |
| CoAP | 5683 | UDP | CON POST with oneM2M options (267/271/279/283) | HTTP/TCP workaround (Docker Desktop blocks UDP) | ✅ Both endpoints |

> **CoAP notification constraint:** Docker Desktop on Windows does not route UDP from containers to LAN devices. CoAP notification delivery uses HTTP/TCP (embedded HTTPServer) as a lab-environment workaround. Outgoing CoAP/UDP requests are not affected. See `docs/architecture/system-overview.md §10`.

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
| `src/android/` | ✅ Complete | All 4 transports; end-to-end tested against ACME CSE v2025.11 (2026-05-31) |
| `src/cse/` | ✅ Complete | All 4 protocols; WS keepalive + CoAP NOTIFY patches applied |
| `src/frontend/` | ✅ Complete | All 4 protocol clients; S1 & S2 orchestrators; CSV logger; results viewer |
| `src/analysis/` | 🔜 Not started | Pending benchmark data collection |

**Next step:** benchmark runs — Scenarios 1 & 2, ≥30 runs × 4 protocols.  
**Deadline:** 2026-06-06 (MDPI paper submission)

**Deadline:** 2026-06-06 (relatório académico, Mobilidade em Sistemas Computacionais)

## Key Technical Documents

| Document | Content |
|---|---|
| `docs/architecture/system-overview.md` | Full protocol flows, resource tree, message formats, lab constraints |
| `docs/protocols/test-scenarios.md` | Per-protocol configuration, CSV schema, benchmark checklist |
| `src/cse/README.md` | CSE setup, patches, resource tree |
| `src/frontend/CLAUDE.md` | Streamlit architecture, protocol client details |
| `src/android/CLAUDE.md` | Android architecture, OneM2MSession lifecycle |

## Academic Context

- **Institution:** Instituto Politécnico de Leiria (IPL)
- **Programme:** Mestrado em Engenharia Informática
- **Deliverable:** MDPI journal article (format confirmed 2026-05-31)
- **Course:** Mobilidade em Sistemas Computacionais — deadline 2026-06-06

## License

Academic research project. All rights reserved pending publication.
