# OneM2M for Real-Time UxV Operations: A Multi-Protocol Performance Analysis

## Overview

This project benchmarks the **OneM2M** middleware framework for unmanned vehicle (UxV) operations
across four communication protocols: **MQTT, WebSocket, HTTP, and CoAP**. The system runs on a
DJI Mavic 2 Enterprise Advanced (M2EA) controlled by an Android-based DJI RC with the ACMECSE
application. Results will be submitted as an IEEE/ACM conference paper.

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
| `src/android/`  | Kotlin, DJI SDK v4 | ACMECSE app running on the DJI RC controller |
| `src/frontend/` | Python, Streamlit  | Test dashboard — command dispatch & telemetry |
| `src/cse/`      | Python, ACME CSE   | OneM2M CSE server configuration & deployment  |
| `src/analysis/` | Python             | Metric collection, statistical analysis, plots |
| `docs/`         | Markdown           | Architecture, protocol specs, ADRs, AI context |

## Protocols Under Test

| Protocol  | Port      | Transport | OneM2M Binding |
|-----------|-----------|-----------|----------------|
| MQTT      | 1883      | TCP       | AE ↔ CSE pub/sub |
| WebSocket | 80 / 443  | TCP       | Bidirectional stream |
| HTTP      | 8080      | TCP       | RESTful resources |
| CoAP      | 5683      | UDP       | Constrained messaging |

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
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python -m acmecse
# CSE starts at http://localhost:8080/
```

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

## Academic Context

- **Institution:** Instituto Politécnico de Leiria (IPL)
- **Programme:** Mestrado em Engenharia Informática
- **Target venue:** IEEE/ACM international conference (TBD)
- **Paper language:** English

## License

Academic research project. All rights reserved pending publication.
