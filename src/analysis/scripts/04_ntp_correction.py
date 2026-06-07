"""Apply per-run NTP offset correction to S1 latency values.

Estimates the inter-device clock offset (Android vs Streamlit) for each
protocol using S3 cin_create_ms data, then corrects S1 latency_ms values.

Method
------
For each S3 run of a protocol, compute:
    offset_run = mean(cin_create_ms) / 2 - mean(latency_ms)

This gives the NTP offset (Android behind Streamlit, in ms) at the time of
that S3 run. A linear model is then fitted over time per protocol, and the
predicted offset at each S1 row's start_timestamp_ms is used to correct:
    latency_ms_corrected = latency_ms - predicted_offset

S2 is NOT corrected (TinyDB contamination makes latency_ms unreliable).
S3 already uses cin_create_ms (NTP-free); no correction needed.

The corrected values are approximations — see paper §Methodology for caveats.

Run from repo root (after 02_compute_stats.py):
    python src/analysis/scripts/04_ntp_correction.py

Outputs
-------
data/processed/all_runs.parquet  — updated in-place with latency_ms_corrected
data/processed/ntp_offsets.json  — estimated per-protocol offset table

Authors: João Parreira, Pedro Barbeiro
"""

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.interpolate import interp1d

REPO_ROOT = Path(__file__).resolve().parents[3]
DATA_PROCESSED = REPO_ROOT / "data" / "processed"


def _fit_offset_model(s3_proto: pd.DataFrame):
    """Fit linear offset(t) model from S3 per-run offset points.

    Parameters
    ----------
    s3_proto : DataFrame — S3 rows for one protocol

    Returns
    -------
    interp1d — callable that predicts offset given start_timestamp_ms (extrapolates)
    list[dict] — per-run anchor points for reporting
    """
    run_points = (
        s3_proto.groupby("run_num")
        .agg(
            t_ms=("start_timestamp_ms", "first"),
            lat_mean=("latency_ms", "mean"),
            cin_mean=("cin_create_ms", "mean"),
        )
        .dropna()
        .reset_index()
    )
    run_points["offset"] = run_points["cin_mean"] / 2 - run_points["lat_mean"]

    f = interp1d(
        run_points["t_ms"],
        run_points["offset"],
        kind="linear",
        fill_value="extrapolate",
    )
    anchors = run_points[["run_num", "t_ms", "offset"]].to_dict(orient="records")
    return f, anchors


def main() -> None:
    """Compute NTP-corrected latency and update all_runs.parquet."""
    parquet_path = DATA_PROCESSED / "all_runs.parquet"
    if not parquet_path.exists():
        print(f"[ERROR] {parquet_path} not found — run 01_load_validate.py first")
        sys.exit(1)

    df = pd.read_parquet(parquet_path)
    print(f"Loaded {len(df):,} rows")

    s3 = df[df["paper_scenario"] == 3]
    s1_mask = df["paper_scenario"] == 1

    # Initialise corrected column as NaN (only S1 will be filled).
    df["latency_ms_corrected"] = np.nan

    offset_report: dict = {}

    for proto in sorted(df["protocol"].unique()):
        s3_proto = s3[s3["protocol"] == proto]
        s1_proto_mask = s1_mask & (df["protocol"] == proto)

        f_offset, anchors = _fit_offset_model(s3_proto)

        # Predict offset for each S1 row using its run's start_timestamp_ms.
        t_vals = df.loc[s1_proto_mask, "start_timestamp_ms"].values
        predicted_offsets = f_offset(t_vals)
        df.loc[s1_proto_mask, "latency_ms_corrected"] = (
            df.loc[s1_proto_mask, "latency_ms"] - predicted_offsets
        )

        # Summary stats for the report.
        corr = df.loc[s1_proto_mask, "latency_ms_corrected"].dropna()
        neg_pct = (corr < 0).mean() * 100
        offset_report[proto] = {
            "s3_anchors": [
                {k: (int(v) if k in ("run_num", "t_ms") else round(float(v), 2))
                 for k, v in a.items()}
                for a in anchors
            ],
            "s1_corrected_mean_ms": round(float(corr.mean()), 2),
            "s1_corrected_median_ms": round(float(corr.median()), 2),
            "s1_corrected_std_ms": round(float(corr.std()), 2),
            "s1_corrected_p95_ms": round(float(corr.quantile(0.95)), 2),
            "s1_negative_pct": round(neg_pct, 2),
        }

        print(
            f"  {proto:10s}  corr_mean={corr.mean():.1f}ms  "
            f"corr_std={corr.std():.1f}ms  neg={neg_pct:.1f}%"
        )

    df.to_parquet(parquet_path, index=False)
    print(f"\nUpdated {parquet_path}")

    out_json = DATA_PROCESSED / "ntp_offsets.json"
    out_json.write_text(json.dumps(offset_report, indent=2))
    print(f"Wrote {out_json}")


if __name__ == "__main__":
    main()
