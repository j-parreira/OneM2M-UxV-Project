# ADR-002: ACME CSE Deployment — Docker Compose

**Date:** 2026-05-20
**Status:** Accepted

## Context

The dev machine runs Windows 11. ACME CSE is a Python application that benefits from a Linux
environment — particularly for Scenario 3, which requires `tc netem` for network emulation.
Options considered:

1. Bare Python on Windows (original plan)
2. WSL2 with Python
3. Docker Compose on Windows 11 (Docker Desktop)

## Decision

**Docker Compose** on Windows 11 via Docker Desktop.

`src/cse/` will contain:
- `Dockerfile` — builds ACME CSE image from official Python base
- `docker-compose.yml` — defines the CSE service and port mappings
- `config/` — ACME CSE config files (mounted as a volume)

## Reasons

- Gives a full Linux environment without maintaining a separate WSL2 setup
- `tc netem` runs inside the container for Scenario 3 (no host Windows dependency)
- Reproducible: the image pins the exact ACME CSE version
- Ports are mapped to the host, so the Android app and Streamlit connect as if CSE were local
- Docker Desktop is well-supported on Windows 11

## Consequences

- `src/cse/` does NOT use a Python `.venv` — managed by Docker
- CSE is started with `docker compose up` from `src/cse/`
- Config changes require editing files in `src/cse/config/` (volume-mounted, no image rebuild)
- For Scenario 3, the Streamlit orchestrator will exec `tc netem` commands into the container
  before each degraded-network run and reset them after
- CoAP uses UDP (:5683) — Docker must expose it as `5683/udp` in `docker-compose.yml`
