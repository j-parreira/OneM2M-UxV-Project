"""Compute per-group aggregate statistics from validated benchmark data.

Reads data/processed/all_runs.parquet (output of 01_load_validate.py) and
computes latency, throughput, packet loss, protocol overhead, and jitter
for every (protocol, paper_scenario, rate_msg_s) combination.

Paper scenario mapping:
    S1 — telemetry 4 msg/s   (paper_scenario=1, internal scenario=1, rate=4)
    S2 — telemetry 16 msg/s  (paper_scenario=2, internal scenario=1, rate=16)
    S3 — command ping         (paper_scenario=3, internal scenario=2, rate=None)

For S3, the primary latency metric is cin_create_ms (Streamlit→CSE CIN round-trip,
monotonic clock, NTP-independent). lat_mean is also computed but is NTP-dependent
and typically negative (~-1880 ms) due to Android clock being behind Streamlit.
Use cin_* columns for any S3 latency analysis.

Run from repo root (after 01_load_validate.py):
    python src/analysis/scripts/02_compute_stats.py

Outputs:
    data/processed/stats_paper_s1.parquet — per-run S1 stats (telemetry 4 msg/s)
    data/processed/stats_paper_s2.parquet — per-run S2 stats (telemetry 16 msg/s)
    data/processed/stats_paper_s3.parquet — per-run S3 stats (command ping)
    data/processed/summary.csv            — aggregate table (mean ± std across runs)

Dependencies: pandas, numpy, scipy, pyarrow (via requirements.txt in src/analysis/)

Authors: João Parreira, Pedro Barbeiro
"""
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats as sp_stats

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[3]
DATA_PROCESSED = REPO_ROOT / "data" / "processed"

# ---------------------------------------------------------------------------
# Per-run statistics helpers
# ---------------------------------------------------------------------------


def _latency_stats(series: pd.Series, prefix: str = "lat") -> dict:
    """Compute descriptive statistics for one run's latency-like series.

    Parameters
    ----------
    series : pd.Series of float — values (already filtered if needed; NaNs dropped internally)
    prefix : str — column name prefix in the returned dict (e.g. 'lat' or 'cin')

    Returns
    -------
    dict with keys: {prefix}_mean, {prefix}_median, {prefix}_std, {prefix}_p5,
                    {prefix}_p25, {prefix}_p75, {prefix}_p95, {prefix}_p99,
                    {prefix}_ci95_low, {prefix}_ci95_high, {prefix}_n
    """
    arr = series.dropna().values
    n = len(arr)
    nan_dict = {f"{prefix}_{k}": np.nan for k in
                ["mean", "median", "std", "p5", "p25", "p75", "p95", "p99",
                 "ci95_low", "ci95_high", "n"]}
    if n == 0:
        return nan_dict

    mean = float(np.mean(arr))
    std = float(np.std(arr, ddof=1)) if n > 1 else np.nan
    # 95% CI of the mean using the t-distribution (ddof=1).
    if n > 1:
        ci = sp_stats.t.interval(0.95, df=n - 1, loc=mean, scale=sp_stats.sem(arr))
    else:
        ci = (np.nan, np.nan)

    return {
        f"{prefix}_mean": mean,
        f"{prefix}_median": float(np.median(arr)),
        f"{prefix}_std": std,
        f"{prefix}_p5": float(np.percentile(arr, 5)),
        f"{prefix}_p25": float(np.percentile(arr, 25)),
        f"{prefix}_p75": float(np.percentile(arr, 75)),
        f"{prefix}_p95": float(np.percentile(arr, 95)),
        f"{prefix}_p99": float(np.percentile(arr, 99)),
        f"{prefix}_ci95_low": float(ci[0]),
        f"{prefix}_ci95_high": float(ci[1]),
        f"{prefix}_n": n,
    }


def _throughput_s1(df_run: pd.DataFrame, elapsed_s: float) -> dict:
    """S1/S2 throughput: n_delivered / observed_elapsed_s.

    Uses the observed first-to-last timestamp span so the rate reflects the
    actual receive window, not the configured duration.

    Parameters
    ----------
    df_run    : DataFrame for a single run (paper_scenario 1 or 2, direction='telemetry')
    elapsed_s : float — observed elapsed seconds (ts.max − ts.min) / 1000

    Returns
    -------
    dict with tput_msg_per_s and tput_kbps
    """
    delivered = df_run[df_run["delivered"]]
    n = len(delivered)
    payload_kb = delivered["payload_bytes"].sum() / 1024
    return {
        "tput_msg_per_s": n / elapsed_s if elapsed_s > 0 else np.nan,
        "tput_kbps": payload_kb / elapsed_s if elapsed_s > 0 else np.nan,
    }


def _throughput_s3(df_run: pd.DataFrame) -> dict:
    """S3 throughput: n_delivered / actual_elapsed_s (from timestamp_ms span).

    Parameters
    ----------
    df_run : DataFrame for a single run (paper_scenario=3)

    Returns
    -------
    dict with tput_msg_per_s and tput_kbps
    """
    delivered = df_run[df_run["delivered"]]
    n = len(delivered)
    ts = df_run["timestamp_ms"]
    elapsed_s = (ts.max() - ts.min()) / 1000.0 if len(ts) > 1 else np.nan
    payload_kb = delivered["payload_bytes"].sum() / 1024
    return {
        "tput_msg_per_s": n / elapsed_s if elapsed_s and elapsed_s > 0 else np.nan,
        "tput_kbps": payload_kb / elapsed_s if elapsed_s and elapsed_s > 0 else np.nan,
    }


def _packet_loss(df_run: pd.DataFrame) -> float:
    """Packet loss as a fraction (0–1).

    S1/S2: gap-based using seq counter (seq should increment by 1).
    S3: flag-based using the delivered boolean.
    """
    paper_scenario = int(df_run["paper_scenario"].iloc[0])
    if paper_scenario in (1, 2):
        seqs = df_run["seq"].sort_values().values
        if len(seqs) < 2:
            return 0.0
        expected = seqs[-1] - seqs[0] + 1
        return max(0.0, 1.0 - len(seqs) / expected)
    else:
        # S3: 'delivered' is set by ACK receipt within timeout
        n_total = len(df_run)
        n_delivered = int(df_run["delivered"].sum())
        return (n_total - n_delivered) / n_total if n_total > 0 else np.nan


def _overhead(df_run: pd.DataFrame) -> dict:
    """Protocol overhead statistics across all messages in the run."""
    total = df_run["header_bytes"] + df_run["payload_bytes"]
    overhead_pct = df_run["header_bytes"] / total.replace(0, np.nan) * 100
    return {
        "overhead_pct_mean": float(overhead_pct.mean()),
        "overhead_pct_std": float(overhead_pct.std(ddof=1)) if len(overhead_pct) > 1 else np.nan,
        "header_bytes_mean": float(df_run["header_bytes"].mean()),
        "payload_bytes_mean": float(df_run["payload_bytes"].mean()),
    }


# ---------------------------------------------------------------------------
# Per-run aggregation
# ---------------------------------------------------------------------------

def compute_run_stats(df_run: pd.DataFrame, run_id: str) -> dict:
    """Compute all statistics for a single run.

    Parameters
    ----------
    df_run : DataFrame — all rows for one run_id
    run_id : str

    Returns
    -------
    dict — one row for the stats table
    """
    paper_scenario = int(df_run["paper_scenario"].iloc[0])

    row: dict = {
        "run_id": run_id,
        "protocol": df_run["protocol"].iloc[0],
        "paper_scenario": paper_scenario,
        "rate_msg_s": df_run["rate_msg_s"].iloc[0] if "rate_msg_s" in df_run.columns else None,
        "n_total": len(df_run),
        "n_delivered": int(df_run["delivered"].sum()),
    }

    # ── Latency (lat_*) — NTP-dependent for all scenarios ──
    # For S3, lat_mean is typically negative (Android clock behind Streamlit).
    # Do NOT use lat_* for S3 analysis; use cin_* instead.
    delivered_lat = df_run.loc[df_run["delivered"], "latency_ms"]
    row.update(_latency_stats(delivered_lat, prefix="lat"))
    # Jitter = std dev of latency within run (delivered only).
    row["jitter_ms"] = row["lat_std"]

    # ── Corrected latency (lat_corr_*) — S1 only, NTP offset removed ──
    # Populated by 04_ntp_correction.py. NaN for S2/S3.
    if "latency_ms_corrected" in df_run.columns:
        delivered_corr = df_run.loc[df_run["delivered"], "latency_ms_corrected"]
        row.update(_latency_stats(delivered_corr, prefix="lat_corr"))

    row["packet_loss_frac"] = _packet_loss(df_run)
    row.update(_overhead(df_run))

    if paper_scenario in (1, 2):
        # Duration from actual timestamp window (includes all rows, not just delivered).
        ts = df_run["timestamp_ms"]
        duration_s = (ts.max() - ts.min()) / 1000.0 if len(ts) > 1 else np.nan
        row.update(_throughput_s1(df_run, duration_s))
        row["duration_s_observed"] = duration_s

    elif paper_scenario == 3:
        row.update(_throughput_s3(df_run))
        # S3 primary latency: cin_create_ms (Streamlit→CSE CIN RTT, monotonic, NTP-free).
        cin = df_run["cin_create_ms"].dropna()
        row.update(_latency_stats(cin, prefix="cin"))
        # cin jitter = std dev of cin_create_ms within run.
        row["cin_jitter_ms"] = row["cin_std"]

    return row


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    """Compute per-run stats and write Parquet + summary CSV."""
    parquet_in = DATA_PROCESSED / "all_runs.parquet"
    if not parquet_in.exists():
        print(f"[ERROR] {parquet_in} not found -- run 01_load_validate.py first")
        sys.exit(1)

    df = pd.read_parquet(parquet_in)
    print(f"Loaded {len(df)} rows from {parquet_in}")
    print(f"Paper scenarios present: {sorted(df['paper_scenario'].unique())}")

    rows: list[dict] = []
    for run_id, df_run in df.groupby("run_id"):
        rows.append(compute_run_stats(df_run, str(run_id)))

    stats = pd.DataFrame(rows)
    stats.sort_values(["protocol", "paper_scenario", "rate_msg_s", "run_id"], inplace=True)
    stats.reset_index(drop=True, inplace=True)

    # Split by paper scenario for downstream consumption.
    s1 = stats[stats["paper_scenario"] == 1].copy()
    s2 = stats[stats["paper_scenario"] == 2].copy()
    s3 = stats[stats["paper_scenario"] == 3].copy()

    DATA_PROCESSED.mkdir(parents=True, exist_ok=True)
    s1.to_parquet(DATA_PROCESSED / "stats_paper_s1.parquet", index=False)
    s2.to_parquet(DATA_PROCESSED / "stats_paper_s2.parquet", index=False)
    s3.to_parquet(DATA_PROCESSED / "stats_paper_s3.parquet", index=False)

    # Summary: mean ± std across runs, grouped by (protocol, paper_scenario, rate_msg_s).
    # dropna=False preserves S3 rows where rate_msg_s is NaN.
    numeric_cols = [c for c in stats.columns if stats[c].dtype in (float, "float64")]
    summary = (
        stats.groupby(["protocol", "paper_scenario", "rate_msg_s"], dropna=False)[numeric_cols]
        .agg(["mean", "std"])
        .reset_index()
    )
    summary.columns = ["_".join(c).strip("_") for c in summary.columns.to_flat_index()]
    summary.to_csv(DATA_PROCESSED / "summary.csv", index=False)

    print(f"\nWrote stats_paper_s1.parquet ({len(s1)} runs)")
    print(f"     stats_paper_s2.parquet ({len(s2)} runs)")
    print(f"     stats_paper_s3.parquet ({len(s3)} runs)")
    print(f"     summary.csv ({len(summary)} groups)")

    # Sanity report per group (dropna=False so S3 with rate=NaN appears).
    for (proto, ps, rate), grp in stats.groupby(["protocol", "paper_scenario", "rate_msg_s"], dropna=False):
        n = len(grp)
        if ps == 3:
            # S3: use cin_mean as primary latency metric
            mean_lat = grp["cin_mean"].mean()
            lat_label = "cin_create"
        else:
            mean_lat = grp["lat_mean"].mean()
            lat_label = "latency"
        loss = grp["packet_loss_frac"].mean() * 100
        rate_label = f" r{int(rate)}" if pd.notna(rate) else ""
        print(f"  {proto:10s} S{ps}{rate_label:4s}  n={n:3d}  {lat_label}={mean_lat:8.1f} ms  loss={loss:.1f}%")


if __name__ == "__main__":
    main()
