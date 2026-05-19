# Design Decisions Log

> Short rationale for choices made during development. For formal decisions, see `docs/adr/`.

## Technology Stack

| Decision              | Choice              | Reason                                                   |
|----------------------|---------------------|----------------------------------------------------------|
| OneM2M CSE           | ACME CSE (Python)   | Open-source, actively maintained, easy local setup       |
| Frontend             | Streamlit (Python)  | Research-only use; integrates natively with Python analysis stack |
| Analysis             | Python (pandas, matplotlib, seaborn) | Standard for academic data analysis |
| Android language     | Kotlin              | DJI SDK v4 supports Kotlin, modern Android standard      |

## Experimental Design

- Each protocol tested in identical network conditions (same LAN, controlled environment)
- Minimum 30 runs per protocol × scenario for statistical validity
- Scenarios: (1) idle telemetry stream, (2) command burst, (3) degraded network (packet loss emulation)
- Random seeds logged for any stochastic elements

## Data Format

- Raw data: CSV with columns `timestamp_ms, protocol, scenario, latency_ms, payload_bytes, header_bytes, delivered`
- One file per run, named `<protocol>_<scenario>_<YYYYMMDD>_run<N>.csv`
- Metadata sidecar: `<same_name>.json` with software versions, config params, operator notes
