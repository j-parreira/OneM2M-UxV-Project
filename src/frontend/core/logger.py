"""CSV data logger for benchmark runs.

Writes one CSV per run to data/raw/ and a companion JSON sidecar with
software versions, RunConfig params, and operator notes.

Both files are required for paper reproducibility (see benchmark-flow.md §8).

CSV filename format: <protocol>_s<scenario>_<YYYYMMDD>_run<N>.csv
JSON sidecar:        <same_stem>.json
"""
import csv
import json
import sys
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Optional


@dataclass
class MetricRecord:
    """One measurement, written as a single CSV row.

    Columns follow the schema in docs/ai-context/frontend-dev.md and
    docs/ai-context/decisions.md.  Optional fields are None when not applicable
    (e.g. t_send_ms is only set for Scenario 1 telemetry).
    """
    timestamp_ms: int           # wall-clock at Streamlit receive time (time.time_ns()//1e6)
    run_id: str                 # matches CSV filename stem
    protocol: str               # mqtt | http | websocket | coap
    scenario: int               # 1 | 2 | 3
    direction: str              # command | telemetry
    latency_ms: Optional[float] # None if message lost / ACK not received
    payload_bytes: int          # len(JSON payload bytes)
    header_bytes: int           # protocol-specific overhead (see frontend-dev.md)
    delivered: bool             # True if ACK received within timeout
    seq: int                    # monotonic counter from Android (telemetry) or Streamlit (commands)
    # Timestamps for latency calculation (set from CIN.con JSON):
    t_send_ms: Optional[int]    # Android send time — Scenario 1 uplink (NTP-dependent vs receiver)
    t_cmd_ms: Optional[int]     # Streamlit send time — Scenario 2 downlink
    t_recv_ms: Optional[int]    # Android receive time — Scenario 2 ACK (different clock: NTP-dep.)
    t_exec_ms: Optional[int]    # Android execute time — Scenario 2 ACK


# Column order matches docs/ai-context/decisions.md "Raw telemetry CSV columns".
_CSV_COLUMNS = [
    "timestamp_ms", "run_id", "protocol", "scenario", "direction",
    "latency_ms", "payload_bytes", "header_bytes", "delivered", "seq",
    "t_send_ms", "t_cmd_ms", "t_recv_ms", "t_exec_ms",
]


def generate_run_id(protocol: str, scenario: int) -> str:
    """Generate a run_id from current date and existing files in data_raw_dir.

    Parameters
    ----------
    protocol : str
    scenario : int

    Returns
    -------
    str, e.g. 'websocket_s1_20260520_run001'
    """
    date_str = time.strftime("%Y%m%d")
    return f"{protocol}_s{scenario}_{date_str}_run{{n}}"


def next_run_id(protocol: str, scenario: int, data_raw_dir: Path) -> str:
    """Return the next unused run ID for a given (protocol, scenario) pair.

    Counts existing CSV files in data_raw_dir matching the pattern and
    increments the run counter.

    Parameters
    ----------
    protocol : str
    scenario : int
    data_raw_dir : Path

    Returns
    -------
    str, e.g. 'websocket_s1_20260520_run003'
    """
    date_str = time.strftime("%Y%m%d")
    stem_prefix = f"{protocol}_s{scenario}_{date_str}_run"
    existing = sorted(data_raw_dir.glob(f"{stem_prefix}*.csv")) if data_raw_dir.exists() else []
    n = len(existing) + 1
    return f"{stem_prefix}{n:03d}"


def save_run(
    records: list[MetricRecord],
    run_id: str,
    protocol: str,
    scenario: int,
    run_config_dict: dict,
    data_raw_dir: Path,
    notes: str = "",
) -> Path:
    """Write CSV + JSON sidecar to data_raw_dir.

    Parameters
    ----------
    records : list[MetricRecord]
    run_id : str          — used as filename stem
    protocol : str
    scenario : int
    run_config_dict : dict — serialisable RunConfig fields for the sidecar
    data_raw_dir : Path   — destination directory (created if absent)
    notes : str           — operator notes for the JSON sidecar

    Returns
    -------
    Path of the written CSV file.
    """
    data_raw_dir.mkdir(parents=True, exist_ok=True)
    csv_path = data_raw_dir / f"{run_id}.csv"
    json_path = data_raw_dir / f"{run_id}.json"

    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=_CSV_COLUMNS)
        writer.writeheader()
        for rec in records:
            writer.writerow(asdict(rec))

    sidecar = {
        "run_id": run_id,
        "protocol": protocol,
        "scenario": scenario,
        "run_config": run_config_dict,
        "notes": notes,
        "software_versions": {
            "python": sys.version,
            # Import streamlit lazily to avoid circular imports during testing.
            "streamlit": _get_streamlit_version(),
        },
        "created_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "n_records": len(records),
    }

    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(sidecar, f, indent=2)

    return csv_path


def _get_streamlit_version() -> str:
    try:
        import streamlit as st
        return st.__version__
    except ImportError:
        return "unknown"
