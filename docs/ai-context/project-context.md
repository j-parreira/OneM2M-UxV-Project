# Project Context — OneM2M UxV Multi-Protocol Study

> This file is intended for AI assistants and developers starting work on this project.
> Read this before touching any sub-project.

## What This Project Does

This is an academic benchmarking study. We connect a **DJI Mavic 2 Enterprise Advanced (M2EA)**
drone to an **ACME CSE (OneM2M)** server using four different communication protocols, then
measure performance metrics to compare them. The goal is a peer-reviewed paper at an IEEE/ACM
conference.

The system has three layers:

1. **UxV Layer** — The drone (M2EA) + DJI RC controller running the ACMECSE Android app. The
   Android app registers as an Application Entity (AE) on the OneM2M CSE and pushes telemetry
   (GPS, battery, altitude, speed) and receives commands (takeoff, land, waypoints).

2. **OneM2M Middleware Layer** — ACME CSE (Python) running locally or on a server. It manages
   the resource tree (`/onem2m/uxv/telemetry`, `/onem2m/uxv/commands`, etc.) and routes
   messages between the drone app and the dashboard.

3. **Test Control Layer** — A Streamlit dashboard that dispatches commands to the drone and
   displays live telemetry. It is also the orchestrator for benchmark runs (choosing protocol,
   setting parameters, triggering data collection).

---

## Protocols

Each protocol is tested independently against the same CSE. The benchmark compares:

| Protocol  | OneM2M Binding                | Key characteristic          |
|-----------|-------------------------------|-----------------------------|
| MQTT      | AE subscribes to CSE topics   | Pub/sub, low overhead       |
| WebSocket | Persistent TCP, CSE push      | Bidirectional, low latency  |
| HTTP      | REST (CREATE/RETRIEVE on AE)  | Stateless, high overhead    |
| CoAP      | UDP, confirmable messages     | Designed for constrained devices |

---

## Metrics Collected

For each protocol × scenario combination:

- **Latency** (ms): `t_ACK - t_command` measured on the dashboard side
- **Throughput** (msg/s and KB/s): messages delivered per second at max rate
- **Packet loss** (%) : messages sent vs messages confirmed received
- **Protocol overhead** (%): `header_bytes / (header_bytes + payload_bytes) × 100`
- **Jitter** (ms): standard deviation of per-message latency over a run

---

## Directory Roles

```
src/android/      Kotlin app for the DJI RC. Implements AE registration and
                  protocol-specific bindings. Existing project being adapted.

src/cse/          ACME CSE server. Config files define the resource tree and
                  which protocols/ports are enabled. Runs on localhost during dev.

src/frontend/     Streamlit app. Two modes:
                    (a) manual: send individual commands, see live telemetry
                    (b) benchmark: automated runs, logs metrics to data/raw/

src/analysis/     Python scripts and Jupyter notebooks that:
                    - parse raw CSV/JSON from data/raw/
                    - compute statistics (mean, median, percentiles, CI)
                    - generate publication-quality plots (matplotlib/seaborn)
                    - export to data/processed/

data/raw/         Never modified after collection. Treated as append-only.
data/processed/   Derived from raw. Safe to regenerate by re-running analysis.

docs/             Reference documentation. Not auto-generated.
```

---

## Key Constraints

- **DJI SDK v4** is Android-only and requires a registered DJI developer key (never committed)
- The Android app runs on the DJI RC controller (Android), not on a phone
- ACME CSE must be reachable from the RC controller (same LAN or hotspot)
- CoAP uses UDP — packet loss experiments are particularly relevant here
- All experiment parameters must be reproducible: log timestamps, software versions, configs

---

## What Does NOT Exist Yet

As of project start, the repository contains only scaffolding. The following are to be built:
- ACMECSE Android app bindings for each protocol
- Streamlit benchmark orchestrator
- Data collection and logging in the frontend
- Analysis scripts and notebooks
- CSE resource tree configuration

Check `docs/adr/` and git log for what has been added since.
