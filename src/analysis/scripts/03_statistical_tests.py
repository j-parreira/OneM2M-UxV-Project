"""Statistical tests for protocol comparison.

Reads per-run stats from data/processed/stats_paper_s{1,2,3}.parquet
(output of 02_compute_stats.py) and performs:

  1. Kruskal-Wallis H test — non-parametric test for >=1 protocol difference
  2. Dunn's post-hoc test — pairwise comparisons with Bonferroni correction
     (scikit_posthocs.posthoc_dunn)
  3. Cliff's delta effect sizes — for each significant pairwise comparison

Paper scenario mapping:
    S1 — telemetry 4 msg/s   (stats_paper_s1.parquet)
    S2 — telemetry 16 msg/s  (stats_paper_s2.parquet)
    S3 — command ping         (stats_paper_s3.parquet)

For S3, the primary latency metric is cin_mean (NTP-independent Streamlit->CSE
CIN round-trip). lat_mean is NTP-dependent and typically negative (~-1880 ms)
so it is excluded from S3 statistical tests.

Outputs:
    data/processed/statistical_tests.json — all test results (H, p, pairwise)

Run from repo root (after 02_compute_stats.py):
    python src/analysis/scripts/03_statistical_tests.py

Why non-parametric? Latency distributions under load are typically right-skewed
and non-Gaussian. Kruskal-Wallis does not assume normality; it ranks values
across all groups and tests whether the rank distributions differ.

Dependencies: pandas, numpy, scipy, scikit-posthocs (requirements.txt)

Authors: Joao Parreira, Pedro Barbeiro
"""
import json
import sys
from pathlib import Path
from itertools import combinations

import numpy as np
import pandas as pd
from scipy import stats as sp_stats
import scikit_posthocs as sp

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[3]
DATA_PROCESSED = REPO_ROOT / "data" / "processed"

# ---------------------------------------------------------------------------
# Metrics to test per scenario
# ---------------------------------------------------------------------------

# S1 and S2 share the same metric set (telemetry, NTP-dependent latency).
METRICS_S1_S2 = [
    ("lat_mean", "mean_latency_ms"),
    ("lat_std", "jitter_ms"),
    ("packet_loss_frac", "packet_loss_frac"),
    ("tput_msg_per_s", "throughput_msg_per_s"),
    ("overhead_pct_mean", "overhead_pct"),
]

# S3: cin_mean is the primary latency (NTP-free). lat_mean is excluded (negative, NTP-dep).
METRICS_S3 = [
    ("cin_mean", "cin_create_ms_mean"),       # primary S3 latency metric
    ("cin_std", "cin_create_ms_jitter"),      # jitter of CIN RTT
    ("cin_p95", "cin_create_ms_p95"),
    ("packet_loss_frac", "packet_loss_frac"),
    ("tput_msg_per_s", "throughput_msg_per_s"),
    ("overhead_pct_mean", "overhead_pct"),
]

# ---------------------------------------------------------------------------
# Effect size helpers
# ---------------------------------------------------------------------------


def cliffs_delta(x: np.ndarray, y: np.ndarray) -> float:
    """Compute Cliff's delta non-parametric effect size.

    Cliff's delta = (P(X > Y) - P(X < Y)) = (n_greater - n_less) / (n_x * n_y).

    Range: -1 to +1.
    Interpretation thresholds (Romano et al.):
        |d| < 0.147: negligible
        |d| < 0.330: small
        |d| < 0.474: medium
        |d| >= 0.474: large

    Parameters
    ----------
    x, y : 1-D arrays of observations for the two groups

    Returns
    -------
    float in [-1, 1]
    """
    n_x, n_y = len(x), len(y)
    if n_x == 0 or n_y == 0:
        return np.nan
    n_greater = sum(1 for xi in x for yj in y if xi > yj)
    n_less = sum(1 for xi in x for yj in y if xi < yj)
    return (n_greater - n_less) / (n_x * n_y)


def effect_size_label(d: float) -> str:
    """Return the qualitative label for a Cliff's delta value."""
    a = abs(d)
    if a < 0.147:
        return "negligible"
    if a < 0.330:
        return "small"
    if a < 0.474:
        return "medium"
    return "large"


# ---------------------------------------------------------------------------
# Test runner
# ---------------------------------------------------------------------------

def run_tests_for_group(
    df: pd.DataFrame,
    metric_col: str,
    group_label: str,
    protocols: list[str],
) -> dict:
    """Run Kruskal-Wallis + Dunn + Cliff's delta for one (metric, scenario) group.

    Parameters
    ----------
    df           : DataFrame with at least columns [metric_col, 'protocol']
    metric_col   : str — column to test
    group_label  : str — e.g. "S1", "S2", "S3" for the JSON key
    protocols    : list of protocol names present in df

    Returns
    -------
    dict with full test results
    """
    groups = {p: df.loc[df["protocol"] == p, metric_col].dropna().values for p in protocols}
    groups = {p: v for p, v in groups.items() if len(v) > 0}

    if len(groups) < 2:
        return {
            "metric": metric_col,
            "group": group_label,
            "error": "fewer than 2 groups with data",
            "protocols_found": list(groups.keys()),
        }

    # ── Kruskal-Wallis ──
    kw_result = sp_stats.kruskal(*groups.values())
    h_stat = float(kw_result.statistic)
    p_kw = float(kw_result.pvalue)

    result: dict = {
        "metric": metric_col,
        "group": group_label,
        "n_protocols": len(groups),
        "n_per_protocol": {p: int(len(v)) for p, v in groups.items()},
        "kruskal_wallis": {"H": h_stat, "p": p_kw, "significant": p_kw < 0.05},
    }

    if p_kw >= 0.05:
        result["note"] = "Kruskal-Wallis not significant -- no post-hoc performed"
        return result

    # ── Dunn's post-hoc (Bonferroni correction) ──
    # posthoc_dunn expects a long-format DataFrame.
    long = df[["protocol", metric_col]].dropna()
    dunn_matrix = sp.posthoc_dunn(
        long, val_col=metric_col, group_col="protocol", p_adjust="bonferroni"
    )
    dunn_dict = {
        f"{a}_vs_{b}": float(dunn_matrix.loc[a, b])
        for a, b in combinations(dunn_matrix.index, 2)
    }

    # ── Cliff's delta for each pair ──
    cliffs: dict = {}
    for a, b in combinations(list(groups.keys()), 2):
        d = cliffs_delta(groups[a], groups[b])
        cliffs[f"{a}_vs_{b}"] = {
            "delta": float(d) if not np.isnan(d) else None,
            "magnitude": effect_size_label(d) if not np.isnan(d) else None,
        }

    result["dunn_pvalues"] = dunn_dict
    result["cliffs_delta"] = cliffs
    return result


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    """Run all statistical tests and write results to JSON."""
    s1_path = DATA_PROCESSED / "stats_paper_s1.parquet"
    s2_path = DATA_PROCESSED / "stats_paper_s2.parquet"
    s3_path = DATA_PROCESSED / "stats_paper_s3.parquet"

    missing = [p for p in (s1_path, s2_path, s3_path) if not p.exists()]
    if missing:
        print(f"[ERROR] Missing files: {[str(p) for p in missing]}")
        print("Run 02_compute_stats.py first")
        sys.exit(1)

    s1 = pd.read_parquet(s1_path)
    s2 = pd.read_parquet(s2_path)
    s3 = pd.read_parquet(s3_path)
    print(f"Loaded S1: {len(s1)} runs, S2: {len(s2)} runs, S3: {len(s3)} runs")

    # All protocols seen across any scenario (union).
    all_protocols = sorted(
        set(s1["protocol"].unique()) | set(s2["protocol"].unique()) | set(s3["protocol"].unique())
    )
    print(f"Protocols: {all_protocols}")

    results: list[dict] = []

    # ── S1 tests ──
    if not s1.empty:
        for col, label in METRICS_S1_S2:
            if col not in s1.columns:
                continue
            r = run_tests_for_group(s1, col, "S1", all_protocols)
            r["metric_label"] = label
            results.append(r)

    # ── S2 tests ──
    if not s2.empty:
        for col, label in METRICS_S1_S2:
            if col not in s2.columns:
                continue
            r = run_tests_for_group(s2, col, "S2", all_protocols)
            r["metric_label"] = label
            results.append(r)

    # ── S3 tests (cin_* metrics, not lat_mean) ──
    if not s3.empty:
        for col, label in METRICS_S3:
            if col not in s3.columns:
                continue
            r = run_tests_for_group(s3, col, "S3", all_protocols)
            r["metric_label"] = label
            results.append(r)

    # ── Write output ──
    out_path = DATA_PROCESSED / "statistical_tests.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, default=str)

    print(f"\nWrote {len(results)} test groups -> {out_path}")

    # Quick summary: which metrics are significant?
    sig = [r for r in results if r.get("kruskal_wallis", {}).get("significant", False)]
    insuff = [r for r in results if "error" in r]
    print(f"\nSignificant Kruskal-Wallis results ({len(sig)}/{len(results)}):")
    for r in sig:
        h = r["kruskal_wallis"]["H"]
        p = r["kruskal_wallis"]["p"]
        print(f"  {r['group']:4s} {r['metric']:25s}  H={h:.2f}  p={p:.4f}")

    if insuff:
        print(f"\nInsufficient data (fewer than 2 protocols) ({len(insuff)}/{len(results)}):")
        for r in insuff:
            print(f"  {r['group']:4s} {r['metric']:25s}  found={r.get('protocols_found', [])}")


if __name__ == "__main__":
    main()
