"""Compute per-group aggregate statistics from validated benchmark data.

Reads data/processed/all_runs.parquet (output of 01_load_validate.py) and
computes latency, throughput, packet loss, protocol overhead, and jitter
for every (protocol, scenario, direction, rate_msg_s) combination.

Run from repo root (after 01_load_validate.py):
    python src/analysis/scripts/02_compute_stats.py

Outputs:
    data/processed/stats_s1.parquet      — per-run S1 stats
    data/processed/stats_s2.parquet      — per-run S2 stats
    data/processed/summary.csv           — aggregate table (mean ± std across runs)

Dependencies: pandas, numpy, scipy (via requirements.txt in src/analysis/)

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


def _latency_stats(series: pd.Series) -> dict:
    """Compute descriptive latency statistics for one run's delivered messages.

    Parameters
    ----------
    series : pd.Series of float — latency_ms values, already filtered to delivered only

    Returns
    -------
    dict with keys: mean, median, std, p5, p25, p75, p95, p99, ci95_low, ci95_high, n
    """
    arr = series.dropna().values
    n = len(arr)
    if n == 0:
        return {k: np.nan for k in
                ["mean", "median", "std", "p5", "p25", "p75", "p95", "p99",
                 "ci95_low", "ci95_high", "n"]}

    mean = float(np.mean(arr))
    std = float(np.std(arr, ddof=1)) if n > 1 else np.nan
    # 95% CI of the mean using the t-distribution (ddof=1).
    if n > 1:
        ci = sp_stats.t.interval(0.95, df=n - 1, loc=mean, scale=sp_stats.sem(arr))
    else:
        ci = (np.nan, np.nan)

    return {
        "mean": mean,
        "median": float(np.median(arr)),
        "std": std,
        "p5": float(np.percentile(arr, 5)),
        "p25": float(np.percentile(arr, 25)),
        "p75": float(np.percentile(arr, 75)),
        "p95": float(np.percentile(arr, 95)),
        "p99": float(np.percentile(arr, 99)),
        "ci95_low": float(ci[0]),
        "ci95_high": float(ci[1]),
        "n": n,
    }


def _throughput_s1(df_run: pd.DataFrame, elapsed_s: float) -> dict:
    """S1 throughput: n_delivered / observed_elapsed_s.

    Uses the observed first-to-last timestamp span, consistent with S2.
    This captures the real receive window rather than the configured duration
    (which can differ if the run was stopped early or the first message arrived
    late).

    Parameters
    ----------
    df_run : DataFrame for a single run (scenario=1, direction='telemetry')
    elapsed_s : float — observed elapsed seconds (ts.max − ts.min) / 1000

    Returns
    -------
    dict with msg_per_s and kbps
    """
    delivered = df_run[df_run["delivered"]]
    n = len(delivered)
    payload_kb = delivered["payload_bytes"].sum() / 1024
    return {
        "msg_per_s": n / elapsed_s if elapsed_s > 0 else np.nan,
        "kbps": payload_kb / elapsed_s if elapsed_s > 0 else np.nan,
    }


def _throughput_s2(df_run: pd.DataFrame) -> dict:
    """S2 throughput: n_delivered / actual_elapsed_s.

    Uses observed first-to-last timestamp so the rate reflects real conditions
    (not the configured timeout, which overestimates if the run ended early).

    Parameters
    ----------
    df_run : DataFrame for a single run (scenario=2)

    Returns
    -------
    dict with msg_per_s and kbps
    """
    delivered = df_run[df_run["delivered"]]
    n = len(delivered)
    ts = df_run["timestamp_ms"]
    if len(ts) > 1:
        elapsed_s = (ts.max() - ts.min()) / 1000.0
    else:
        elapsed_s = np.nan
    payload_kb = delivered["payload_bytes"].sum() / 1024
    return {
        "msg_per_s": n / elapsed_s if elapsed_s and elapsed_s > 0 else np.nan,
        "kbps": payload_kb / elapsed_s if elapsed_s and elapsed_s > 0 else np.nan,
    }


def _packet_loss(df_run: pd.DataFrame) -> float:
    """Packet loss as a fraction (0–1).

    Uses seq counter gaps for S1; delivered flag for S2.
    """
    scenario = df_run["scenario"].iloc[0]
    if scenario == 1:
        # Gap-based: seq should increment by 1 each message.
        seqs = df_run["seq"].sort_values().values
        if len(seqs) < 2:
            return 0.0
        expected = seqs[-1] - seqs[0] + 1
        return max(0.0, 1.0 - len(seqs) / expected)
    else:
        n_total = len(df_run)
        n_delivered = df_run["delivered"].sum()
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


def _jitter(delivered: pd.Series) -> float:
    """Jitter = std dev of latency_ms within one run (delivered messages only)."""
    arr = delivered.dropna().values
    return float(np.std(arr, ddof=1)) if len(arr) > 1 else np.nan


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
    row: dict = {
        "run_id": run_id,
        "protocol": df_run["protocol"].iloc[0],
        "scenario": int(df_run["scenario"].iloc[0]),
        "rate_msg_s": df_run["rate_msg_s"].iloc[0] if "rate_msg_s" in df_run.columns else None,
        "n_total": len(df_run),
        "n_delivered": int(df_run["delivered"].sum()),
    }

    delivered_lat = df_run.loc[df_run["delivered"], "latency_ms"]
    row.update({f"lat_{k}": v for k, v in _latency_stats(delivered_lat).items()})
    row["jitter_ms"] = _jitter(delivered_lat)
    row["packet_loss_frac"] = _packet_loss(df_run)
    row.update(_overhead(df_run))

    scenario = row["scenario"]
    if scenario == 1:
        # duration_s is embedded in the run_id stem via RunConfig but easier to
        # approximate from the actual timestamp window (safe because S1 records
        # all messages, not just delivered ones).
        ts = df_run["timestamp_ms"]
        duration_s = (ts.max() - ts.min()) / 1000.0 if len(ts) > 1 else np.nan
        row.update({f"tput_{k}": v for k, v in _throughput_s1(df_run, duration_s).items()})
        row["duration_s_observed"] = duration_s
    elif scenario == 2:
        row.update({f"tput_{k}": v for k, v in _throughput_s2(df_run).items()})
        # S2: also compute cin_create_ms stats (Streamlit→CSE RTT, NTP-free).
        cin = df_run["cin_create_ms"].dropna()
        row["cin_ms_mean"] = float(cin.mean()) if len(cin) > 0 else np.nan
        row["cin_ms_p95"] = float(np.percentile(cin.values, 95)) if len(cin) > 0 else np.nan

    return row


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    """Compute per-run stats and write Parquet + summary CSV."""
    parquet_in = DATA_PROCESSED / "all_runs.parquet"
    if not parquet_in.exists():
        print(f"[ERROR] {parquet_in} not found — run 01_load_validate.py first")
        sys.exit(1)

    df = pd.read_parquet(parquet_in)
    print(f"Loaded {len(df)} rows from {parquet_in}")

    rows: list[dict] = []
    for run_id, df_run in df.groupby("run_id"):
        rows.append(compute_run_stats(df_run, str(run_id)))

    stats = pd.DataFrame(rows)
    stats.sort_values(["protocol", "scenario", "rate_msg_s", "run_id"], inplace=True)
    stats.reset_index(drop=True, inplace=True)

    # Split by scenario for easier downstream consumption.
    s1 = stats[stats["scenario"] == 1].copy()
    s2 = stats[stats["scenario"] == 2].copy()

    DATA_PROCESSED.mkdir(parents=True, exist_ok=True)
    s1.to_parquet(DATA_PROCESSED / "stats_s1.parquet", index=False)
    s2.to_parquet(DATA_PROCESSED / "stats_s2.parquet", index=False)

    # Summary: mean ± std across runs, grouped by (protocol, scenario, rate_msg_s).
    numeric_cols = [c for c in stats.columns if stats[c].dtype in (float, "float64")]
    summary = (
        stats.groupby(["protocol", "scenario", "rate_msg_s"])[numeric_cols]
        .agg(["mean", "std"])
        .reset_index()
    )
    summary.columns = ["_".join(c).strip("_") for c in summary.columns.to_flat_index()]
    summary.to_csv(DATA_PROCESSED / "summary.csv", index=False)

    print(f"\nWrote stats_s1.parquet ({len(s1)} runs), stats_s2.parquet ({len(s2)} runs)")
    print(f"Wrote summary.csv ({len(summary)} groups)")

    # Quick sanity report.
    for (proto, sc, rate), grp in stats.groupby(["protocol", "scenario", "rate_msg_s"]):
        n = len(grp)
        mean_lat = grp["lat_mean"].mean()
        loss = grp["packet_loss_frac"].mean() * 100
        label = f"{proto:10s} S{sc}" + (f" r{rate}" if pd.notna(rate) else "   ")
        print(f"  {label}  n={n:3d}  latency={mean_lat:7.1f} ms  loss={loss:.1f}%")


if __name__ == "__main__":
    main()
