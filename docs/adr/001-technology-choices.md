# ADR-001: Core Technology Choices

**Date:** 2026-05-19
**Status:** Accepted

## Context

We need to select a OneM2M CSE implementation, a frontend stack for test control, and a language
for data analysis. The project is research-only with a single developer and a short timeline
targeting a conference paper submission.

## Decisions

### OneM2M CSE: ACME CSE (Python)

**Chosen over:** Eclipse OM2M (Java), OpenMTC (Python)

**Reasons:**
- Actively maintained (2024+ releases), full oneM2M Release 4 compliance
- Python codebase is easier to inspect and instrument for research purposes
- Simpler local deployment than OM2M (no OSGi container needed)
- Supports all four target protocols natively

**Deployment:** Docker Compose (see ADR-002) — not bare Python on Windows host.

### Frontend: Python + Streamlit

**Chosen over:** React, Vue.js, Flask+HTML

**Reasons:**
- Research/testing use only — no production or commercial deployment
- Python-native: shares the same environment as analysis scripts
- Rapid prototyping; real-time metric charts with minimal boilerplate
- No need for a separate backend API layer

### Data Analysis: Python (pandas, matplotlib, seaborn, scipy)

**Chosen over:** R, MATLAB

**Reasons:**
- Same language as CSE and frontend → unified environment management
- pandas + scipy provides all statistics needed (mean, CI, t-tests)
- matplotlib/seaborn produce IEEE-quality publication plots
- Jupyter notebooks allow reproducible, narrative-style analysis

## Consequences

- All Python sub-projects use isolated `.venv` environments (no global installs)
- Frontend is intentionally minimal — do not add features beyond test orchestration
- Analysis scripts must be fully reproducible (no external state, logged seeds/versions)
