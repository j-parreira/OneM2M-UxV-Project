# CLAUDE.md — OneM2M-UxV-Project

## Project at a Glance

Research project benchmarking OneM2M middleware across MQTT, WebSocket, HTTP, and CoAP for
DJI UxV (unmanned vehicle) operations. Academic context: IPL Leiria, Mestrado em Engenharia
Informática.

**Deadlines:**
- **2026-06-06** — Course paper submission (Mobilidade em Sistemas Computacionais) — hard deadline
- **After June 6** — IEEE/ACM conference paper submission (extended version)

See `docs/ai-context/project-context.md` for full system context before starting any task.

---

## Sub-project Map

| Directory        | Stack              | Entry Point                        |
|-----------------|--------------------|------------------------------------|
| `src/android/`  | Kotlin, DJI SDK v4 | Android Studio project             |
| `src/frontend/` | Python, Streamlit  | `python -m streamlit run app.py`   |
| `src/cse/`      | Docker, ACME CSE   | `docker compose up`                |
| `src/analysis/` | Python             | Scripts in `scripts/`, notebooks in `notebooks/` |
| `docs/`         | Markdown           | Reference only, do not auto-generate |

---

## Python Environments

Each Python sub-project has its own `.venv` — never install packages globally.

```
src/frontend/.venv   ← activate before running Streamlit
src/analysis/.venv   ← activate before running scripts/notebooks
```

Always use `python -m pip install` inside the relevant activated environment.

> **Note:** `src/cse/` does NOT use a `.venv` — ACME CSE runs in Docker. Use
> `docker compose up` from `src/cse/`. See `docs/adr/002-cse-deployment.md`.

---

## Protocols and Default Ports

| Protocol  | Port | Transport |
|-----------|------|-----------|
| MQTT      | 1883 | TCP       |
| WebSocket | 80   | TCP       |
| HTTP      | 8080 | TCP       |
| CoAP      | 5683 | UDP       |

---

## Data Conventions

- **Raw data** → `data/raw/` — never modify these files
- **Processed data** → `data/processed/`
- **Filename format:** `<protocol>_<scenario>_<YYYYMMDD>_run<N>.csv`
  - Example: `mqtt_latency_20260520_run1.csv`
- Files >10 MB must be in `.gitignore` — do not commit large datasets

---

## Code Conventions

### Python (analysis, frontend, CSE config)
- PEP 8, snake_case, type hints on all function signatures
- All configurable parameters (timeouts, repetitions, IP addresses, ports) go in config
  files or environment variables — never hardcoded
- Random seeds must be logged and documented in the script header
- Each script: file-level docstring, function docstrings (description + params + returns)

### Kotlin (Android)
- Standard Android/Kotlin conventions, camelCase methods
- DJI SDK v4 API calls must handle `DJISDKRegisteredCallback` before any operation
- Never hardcode DJI API key — use `local.properties` (git-ignored)

### Jupyter Notebooks
- First cell: markdown with purpose, dependencies, expected runtime
- Separate data loading from analysis (different cells)
- Preserve all output cells with results and plots
- Do not reorganise cells without confirming first

---

## Architecture Decisions

See `docs/adr/` for full records. Key decisions:
- **CSE runtime:** Docker Compose — see `docs/adr/002-cse-deployment.md`
- **CSE implementation:** ACME CSE (Python) — see `docs/adr/001-technology-choices.md`
- **Frontend:** Streamlit — test/research only, not production
- **Android app:** existing project adapted (not from scratch) — DJI SDK v4 already working
- **Drone:** DJI M2EA with Android RC (SDK v4)
- **tc netem (Scenario 3):** runs inside the CSE Docker container, not on host

---

## What NOT to Do

- Do not install Python packages globally
- Do not hardcode IP addresses, ports, or API keys
- Do not mix `data/raw/` and `data/processed/`
- Do not commit `local.properties`, `.env`, or any credential file
- Do not commit raw data files larger than 10 MB
- Do not reorganise notebook cells without confirming
- Do not modify bibliography/references in the paper without confirming
- Do not create new top-level directories without discussing first

---

## Service Startup Order

When running the full system:

1. `docker compose up` from `src/cse/` — wait for CSE to be healthy on :8080
2. Launch Android app on DJI RC — it registers as AE and creates the resource containers
   (`cse-in/uxv/telemetry`, `cse-in/uxv/commands`, `cse-in/uxv/ack`)
3. `streamlit run app.py` from `src/frontend/` (with venv active) — must start AFTER Android,
   because Streamlit's `_ensure_subscription` needs the containers to already exist
4. Drone must be powered on before DJI SDK commands are issued

The Android app is configured with the CSE IP via a settings screen — no hardcoded IP.

---

## Running Tests

Test infrastructure to be defined. When added, document here with exact commands.

---

## Relevant Docs

- `docs/ai-context/project-context.md` — full system context and architecture
- `docs/ai-context/decisions.md` — design decisions and rationale
- `docs/ai-context/cse-dev.md` — development context for `src/cse/` (Docker, config, resource tree)
- `docs/ai-context/frontend-dev.md` — development context for `src/frontend/` (Streamlit, protocol clients, logging)
- `docs/ai-context/analysis-dev.md` — development context for `src/analysis/` (pipeline, statistics, figures)
- `docs/architecture/system-overview.md` — OneM2M resource tree and data flow
- `docs/protocols/test-scenarios.md` — how each protocol is tested
- `docs/adr/` — Architecture Decision Records
