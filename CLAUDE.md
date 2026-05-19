# CLAUDE.md — OneM2M-UxV-Project

## Project at a Glance

Research project benchmarking OneM2M middleware across MQTT, WebSocket, HTTP, and CoAP for
DJI UxV (unmanned vehicle) operations. Target: IEEE/ACM conference paper. Academic context:
IPL Leiria, Mestrado em Engenharia Informática.

See `docs/ai-context/project-context.md` for full system context before starting any task.

---

## Sub-project Map

| Directory        | Stack              | Entry Point                        |
|-----------------|--------------------|------------------------------------|
| `src/android/`  | Kotlin, DJI SDK v4 | Android Studio project             |
| `src/frontend/` | Python, Streamlit  | `python -m streamlit run app.py`   |
| `src/cse/`      | Python, ACME CSE   | `python -m acmecse`                |
| `src/analysis/` | Python             | Scripts in `scripts/`, notebooks in `notebooks/` |
| `docs/`         | Markdown           | Reference only, do not auto-generate |

---

## Python Environments

Each Python sub-project has its own `.venv` — never install packages globally.

```
src/cse/.venv        ← activate before running CSE
src/frontend/.venv   ← activate before running Streamlit
src/analysis/.venv   ← activate before running scripts/notebooks
```

Always use `python -m pip install` inside the relevant activated environment.

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
- **CSE:** ACME CSE (Python) — see `docs/adr/001-technology-choices.md`
- **Frontend:** Streamlit — test/research only, not production
- **Android app:** existing project adapted (not created from scratch)
- **Drone:** DJI M2EA with Android RC (SDK v4)

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

## Running Tests

Test infrastructure to be defined. When added, document here with exact commands.

---

## Relevant Docs

- `docs/ai-context/project-context.md` — full system context and architecture
- `docs/ai-context/decisions.md` — design decisions and rationale
- `docs/architecture/system-overview.md` — OneM2M resource tree and data flow
- `docs/protocols/test-scenarios.md` — how each protocol is tested
- `docs/adr/` — Architecture Decision Records
