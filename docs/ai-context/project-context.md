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

## Deadlines

| Deadline | Artefact |
|---|---|
| **2026-06-06** | Course paper — Mobilidade em Sistemas Computacionais (hard) |
| After June 6 | IEEE/ACM conference paper (extended version) |

The course paper requires: system architecture description, at least preliminary experimental
results for ≥ 2 protocols, methodology. Full 4-protocol comparison is the stretch goal.

---

## Infrastructure

ACME CSE runs in **Docker Compose** (`src/cse/docker-compose.yml`) on the Windows 11 dev
machine — not as a bare Python process. This gives a Linux environment for `tc netem` (used
in Scenario 3) without requiring WSL or a separate VM.

The DJI RC and the dev machine are on the **same WiFi network**. The Android app discovers
the CSE IP via a settings screen (no hardcoded IP).

---

## What Does NOT Exist Yet

The Android app (Kotlin, DJI SDK v4) exists and is being adapted from an existing project.
The following still need to be built or scaffolded:

- `src/cse/` — Docker Compose setup and ACME CSE config files for the resource tree
- `src/frontend/` — Streamlit benchmark orchestrator and data logging
- `src/analysis/` — Analysis scripts and Jupyter notebooks
- Protocol-specific bindings in the Android app (MQTT, WebSocket, CoAP — HTTP may already exist)
- Multi-drone / load simulation strategy (unresolved — revisit after Android app is reviewed)

Check `docs/adr/` and git log for what has been added since.
