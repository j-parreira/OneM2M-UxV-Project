# Development Context — Analysis (`src/analysis/`)

> Read this before touching anything in `src/analysis/`. Complements `project-context.md`
> and `docs/protocols/test-scenarios.md`. This sub-project is fully independent of the
> Android app.

---

## Purpose

Takes raw CSV files from `data/raw/`, computes statistics, and produces:
- Processed datasets in `data/processed/`
- Publication-quality figures for the paper
- Statistical test results

All scripts must be fully reproducible: given the same `data/raw/`, they must produce
identical output. No external state, no manual steps.

---

## Directory Structure

```
src/analysis/
├── scripts/
│   ├── 01_load_validate.py     ← parse raw CSVs, check schema, report anomalies
│   ├── 02_compute_stats.py     ← per-protocol × scenario statistics → data/processed/
│   └── 03_statistical_tests.py ← Kruskal-Wallis + post-hoc pairwise comparisons
├── notebooks/
│   ├── 01_exploratory.ipynb    ← first look at a new dataset, not for paper
│   ├── 02_latency.ipynb        ← latency analysis and figures
│   ├── 03_throughput.ipynb     ← throughput and overhead figures
│   └── 04_paper_figures.ipynb  ← final publication figures, export to docs/figures/
├── requirements.txt
└── figures/                    ← generated figures (git-ignored if >1 MB each)
```

Scripts in `scripts/` are run in order (01 → 02 → 03). Notebooks in `notebooks/` are
standalone but depend on `data/processed/` being populated by the scripts.

---

## Python Dependencies

```
pandas==2.2.2
numpy==1.26.4
scipy==1.13.1
matplotlib==3.9.0
seaborn==0.13.2
jupyterlab==4.2.2
notebook==7.2.0
ipykernel==6.29.4
statsmodels==0.14.2
```

`statsmodels` is added for post-hoc Dunn tests (pairwise comparisons after Kruskal-Wallis).
Create `src/analysis/.venv` — never install globally.

---

## Data Loading Convention

All scripts load data with a single helper that enforces the schema:

```python
SCHEMA = {
    "timestamp_ms": "int64",
    "run_id": "str",
    "protocol": "str",
    "scenario": "int8",
    "direction": "str",
    "latency_ms": "float64",    # nullable
    "payload_bytes": "int32",
    "header_bytes": "int32",
    "delivered": "bool",
    "seq": "int32",
}

VALID_PROTOCOLS = {"mqtt", "http", "websocket", "coap"}
VALID_SCENARIOS = {1, 2, 3}
```

Any file that fails schema validation is logged and excluded — never silently corrupted.

---

## Statistics to Compute

For each `(protocol, scenario, direction)` group, `02_compute_stats.py` outputs:

### Latency (ms) — delivered messages only

| Statistic | Notes |
|---|---|
| mean, median | central tendency |
| std | spread |
| p5, p25, p75, p95, p99 | distribution shape |
| 95% CI of mean | `scipy.stats.t.interval` with `ddof=1` |
| n_samples | for reporting |

### Throughput

- **msg/s**: `n_delivered / duration_s` per run, then mean ± std across runs
- **KB/s**: `sum(payload_bytes, delivered only) / 1024 / duration_s`

### Packet loss (%)

- `(n_sent - n_delivered) / n_sent × 100` per run, then mean ± std

### Protocol overhead (%)

- `header_bytes / (header_bytes + payload_bytes) × 100` per message, then mean ± std

### Jitter (ms)

- Standard deviation of `latency_ms` within a single run, then mean across runs

Output: one Parquet file per `(scenario, direction)` in `data/processed/`, plus a summary
CSV with all aggregate statistics for quick inspection.

---

## Statistical Tests (`03_statistical_tests.py`)

### Comparing protocols

For each metric (latency, throughput, packet loss):

1. **Kruskal-Wallis H test** — non-parametric, tests whether ≥1 protocol differs
   - `scipy.stats.kruskal(*groups)`
   - Report H statistic and p-value
   - If p < 0.05: proceed to post-hoc

2. **Dunn's test** (post-hoc pairwise) — `scikit_posthocs.posthoc_dunn` with Bonferroni
   correction
   - Produces a 4×4 p-value matrix (protocol pairs)

3. **Effect size** — Cliff's delta for each pair (`pairwise` from `statsmodels` or manual)
   - |d| < 0.147: negligible; 0.147–0.33: small; 0.33–0.474: medium; ≥0.474: large

All test results are saved to `data/processed/statistical_tests.json`.

> **Why Kruskal-Wallis?** Latency distributions are typically right-skewed and not normally
> distributed. Parametric ANOVA would require normality. Kruskal-Wallis is the standard
> non-parametric alternative for comparing ≥3 groups.

---

## Figures for the Paper

All figures go to `src/analysis/figures/` and are referenced in the paper. Use IEEE column
width: **3.5 inches** (single column) or **7.16 inches** (double column). DPI: 300 minimum.

Use `matplotlib` with a consistent style:

```python
plt.rcParams.update({
    "figure.dpi": 300,
    "font.size": 9,
    "axes.labelsize": 9,
    "legend.fontsize": 8,
    "font.family": "serif",    # matches IEEE paper font
})
```

### Required figures (minimum for June 6 paper)

| Figure | Type | Data | Priority |
|---|---|---|---|
| Latency distribution | Box plot (4 protocols) | Scenario 1, all rates | **Must have** |
| Latency CDF | Line (4 protocols) | Scenario 1, 5 msg/s | **Must have** |
| Protocol overhead | Bar chart (4 protocols) | All scenarios | **Must have** |
| Throughput vs rate | Line (4 protocols × 3 rates) | Scenario 1 | **Must have** |
| Packet loss | Bar chart (4 protocols) | Scenario 2 | **Must have** |
| Degraded network | Line (loss% vs injected loss) | Scenario 3 | After June 6 |

### Colour palette

Use a colourblind-safe palette consistent across all figures:

```python
PROTOCOL_COLORS = {
    "http":      "#0072B2",   # blue
    "mqtt":      "#E69F00",   # orange
    "websocket": "#009E73",   # green
    "coap":      "#D55E00",   # vermillion
}
```

---

## Notebook Structure

Each notebook must begin with a markdown cell:

```markdown
## [Notebook name]
**Purpose:** ...
**Dependencies:** data/processed/ populated by scripts/01 and scripts/02
**Expected runtime:** < X minutes
**Last run:** YYYY-MM-DD
```

Separate data loading from analysis — different cells. Do not reorganise cells without
confirming. Preserve all output cells.

---

## Reproducibility Checklist

Before committing any notebook or script:
- [ ] No hardcoded file paths — use `pathlib.Path` relative to repo root or env var
- [ ] Random seed set and logged if any stochastic step is used
- [ ] All version numbers recorded in the sidecar JSON or notebook header
- [ ] `data/raw/` files untouched — scripts only read, never write to raw
- [ ] Output is identical on a clean re-run from `data/raw/`
