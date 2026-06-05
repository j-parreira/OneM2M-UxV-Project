"""Load and validate all raw benchmark CSVs.

Reads every CSV from data/raw/ that matches the canonical filename pattern,
validates each file against the expected schema, and writes a consolidated
Parquet file to data/processed/all_runs.parquet.

Anomalies (missing columns, bad types, unexpected values) are logged to stdout
and the offending file is excluded — never silently corrupted.

Run from repo root:
    python src/analysis/scripts/01_load_validate.py

Outputs:
    data/processed/all_runs.parquet   — all valid rows with added meta-columns
    (stdout)                          — per-file validation report

Dependencies: pandas (via requirements.txt in src/analysis/)

Authors: João Parreira, Pedro Barbeiro
"""
import json
import re
import sys
from pathlib import Path

import pandas as pd

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[3]
DATA_RAW = REPO_ROOT / "data" / "raw"
DATA_PROCESSED = REPO_ROOT / "data" / "processed"

# ---------------------------------------------------------------------------
# Schema
# ---------------------------------------------------------------------------

# Expected CSV columns and their nullable/required status.
REQUIRED_COLUMNS = {
    "timestamp_ms", "run_id", "protocol", "scenario", "direction",
    "latency_ms", "payload_bytes", "header_bytes", "delivered", "seq",
    "t_send_ms", "t_cmd_ms", "t_recv_ms", "t_exec_ms", "cin_create_ms",
}

DTYPE_MAP = {
    "timestamp_ms": "int64",
    "scenario": "int8",
    "payload_bytes": "int32",
    "header_bytes": "int32",
    "seq": "int32",
    "latency_ms": "float64",
    "cin_create_ms": "float64",
    "t_send_ms": "float64",   # nullable int — stored as float in CSV
    "t_cmd_ms": "float64",
    "t_recv_ms": "float64",
    "t_exec_ms": "float64",
    "delivered": "bool",
    "run_id": "str",
    "protocol": "str",
    "direction": "str",
}

VALID_PROTOCOLS = {"mqtt", "http", "websocket", "coap"}
VALID_SCENARIOS = {1, 2}
VALID_DIRECTIONS = {"telemetry", "command"}

# Filename pattern — extracts (protocol, paper_scenario, date, run_num).
# Paper scenarios: s1=telemetry 4 msg/s, s2=telemetry 16 msg/s, s3=command ping.
# Rate is stored in the JSON sidecar, not in the filename.
_FILENAME_RE = re.compile(
    r"^(websocket|mqtt|http|coap)_s([123])_(\d{8})_run(\d+)\.csv$"
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _parse_sidecar(csv_path: Path) -> dict:
    """Load the companion .json sidecar; return {} if absent or invalid."""
    json_path = csv_path.with_suffix(".json")
    if not json_path.exists():
        return {}
    try:
        with open(json_path, encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return {}


def _validate_file(path: Path) -> pd.DataFrame | None:
    """Load one CSV, validate schema and value ranges.

    Returns a DataFrame with added meta-columns on success, or None on failure.
    Logs all issues to stdout.
    """
    m = _FILENAME_RE.match(path.name)
    if not m:
        print(f"[SKIP] {path.name}: filename does not match canonical pattern")
        return None

    proto_fname, paper_s_str, date_str, run_num = m.groups()

    # ── Load ──
    try:
        df = pd.read_csv(path, dtype=str)  # load as str first for validation
    except Exception as exc:
        print(f"[ERROR] {path.name}: cannot read CSV — {exc}")
        return None

    # ── Column presence ──
    missing = REQUIRED_COLUMNS - set(df.columns)
    if missing:
        print(f"[ERROR] {path.name}: missing columns {missing}")
        return None

    # ── Type coercion ──
    errors = []
    for col, dtype in DTYPE_MAP.items():
        if col not in df.columns:
            continue
        try:
            if dtype == "bool":
                df[col] = df[col].map({"True": True, "False": False, "true": True, "false": False})
                if df[col].isna().any():
                    errors.append(f"{col}: unexpected bool values")
            elif dtype.startswith("float"):
                df[col] = pd.to_numeric(df[col], errors="coerce").astype("float64")
            else:
                df[col] = df[col].astype(dtype)
        except Exception as exc:
            errors.append(f"{col}: {exc}")

    if errors:
        print(f"[ERROR] {path.name}: type coercion failed — {errors}")
        return None

    # ── Value validation ──
    bad = []
    if not set(df["protocol"].unique()).issubset(VALID_PROTOCOLS):
        bad.append(f"protocol: {set(df['protocol'].unique()) - VALID_PROTOCOLS}")
    if not set(df["scenario"].unique()).issubset(VALID_SCENARIOS):
        bad.append(f"scenario: unexpected values")
    if not set(df["direction"].unique()).issubset(VALID_DIRECTIONS):
        bad.append(f"direction: {set(df['direction'].unique()) - VALID_DIRECTIONS}")
    if (df["payload_bytes"] < 0).any():
        bad.append("payload_bytes: negative values")
    if (df["header_bytes"] < 0).any():
        bad.append("header_bytes: negative values")

    # Warn on implausible latency (> 60 s is almost certainly a clock issue).
    implausible = df["latency_ms"].dropna()
    if (implausible > 60_000).any():
        n = (implausible > 60_000).sum()
        print(f"[WARN] {path.name}: {n} rows with latency_ms > 60 s (NTP issue?)")

    if bad:
        print(f"[ERROR] {path.name}: value validation failed — {bad}")
        return None

    # ── Attach meta-columns from filename + sidecar ──
    sidecar = _parse_sidecar(path)
    # paper_scenario from sidecar if present, else from filename (s1→1, s2→2, s3→3).
    df["paper_scenario"] = int(sidecar.get("paper_scenario", int(paper_s_str)))
    # rate_msg_s only in sidecar (S3 has no rate; S1=4, S2=16).
    df["rate_msg_s"] = sidecar.get("rate_msg_s")
    df["cse_version"] = sidecar.get("cse_version", "unknown")
    df["android_app_version"] = sidecar.get("android_app_version", "unknown")
    df["drone_model"] = sidecar.get("drone_model", "unknown")
    df["start_timestamp_ms"] = sidecar.get("start_timestamp_ms", pd.NA)
    df["run_date"] = date_str
    df["run_num"] = int(run_num)

    return df


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    """Load all raw CSVs, validate, and write consolidated Parquet."""
    if not DATA_RAW.exists():
        print(f"[ERROR] data/raw/ directory not found: {DATA_RAW}")
        sys.exit(1)

    csv_files = sorted(DATA_RAW.glob("*.csv"))
    print(f"Found {len(csv_files)} CSV files in {DATA_RAW}")

    frames: list[pd.DataFrame] = []
    n_ok = 0
    n_skip = 0

    for path in csv_files:
        df = _validate_file(path)
        if df is None:
            n_skip += 1
        else:
            frames.append(df)
            n_ok += 1

    print(f"\nLoaded: {n_ok} files | Skipped: {n_skip} files")

    if not frames:
        print("[ERROR] No valid files — nothing to write.")
        sys.exit(1)

    all_runs = pd.concat(frames, ignore_index=True)

    # Sort for deterministic Parquet layout.
    all_runs.sort_values(["protocol", "paper_scenario", "rate_msg_s", "run_id", "seq"], inplace=True)
    all_runs.reset_index(drop=True, inplace=True)

    DATA_PROCESSED.mkdir(parents=True, exist_ok=True)
    out_path = DATA_PROCESSED / "all_runs.parquet"
    all_runs.to_parquet(out_path, index=False)

    print(f"\nWrote {len(all_runs)} rows -> {out_path}")
    print(f"Protocols: {sorted(all_runs['protocol'].unique())}")
    print(f"Paper scenarios: {sorted(all_runs['paper_scenario'].unique())}")
    print(f"Rates: {sorted(all_runs['rate_msg_s'].dropna().unique())}")
    print(f"Run IDs: {all_runs['run_id'].nunique()}")


if __name__ == "__main__":
    main()
