"""CSV data logger for benchmark runs.

Writes one CSV per run to data/raw/ and a companion JSON sidecar with
software versions, RunConfig params, and operator notes.

Both files are required for paper reproducibility (see benchmark-flow.md §8).

CSV filename format (paper scenario numbers):
  <protocol>_s<paper_scenario>_<YYYYMMDD>_run<NNN>.csv

  Paper scenario mapping:
    S1 — telemetry 4 msg/s   (internal: scenario=1, rate=4)
    S2 — telemetry 16 msg/s  (internal: scenario=1, rate=16)
    S3 — command ping (1/s)  (internal: scenario=2)

JSON sidecar: <same_stem>.json

Authors: João Parreira, Pedro Barbeiro
"""
import csv
import json
import sys
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Optional

# ── Reproducibility constants ────────────────────────────────────────────────
# These are fixed for the entirety of the experiment campaign. Change only if
# the actual hardware/software changes mid-campaign.
CSE_VERSION = "2025.11"                         # ACME CSE version (Dockerfile: acmecse==2025.11)
ANDROID_APP_VERSION = "4.0"                     # App version (DJI SDK v4, src/android/)
DRONE_MODEL = "DJI Mavic 2 Enterprise Advanced" # Hardware under test


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
    # CIN creation round-trip (Streamlit → CSE → response); excludes end-to-end Android delivery.
    # Set for every command attempt (sent or not), None for Scenario 1 telemetry rows.
    cin_create_ms: Optional[float]


# Column order matches docs/ai-context/decisions.md "Raw telemetry CSV columns".
_CSV_COLUMNS = [
    "timestamp_ms", "run_id", "protocol", "scenario", "direction",
    "latency_ms", "payload_bytes", "header_bytes", "delivered", "seq",
    "t_send_ms", "t_cmd_ms", "t_recv_ms", "t_exec_ms", "cin_create_ms",
]


def _paper_scenario_num(scenario: int, rate_msg_s: Optional[int]) -> int:
    """Map internal (scenario, rate_msg_s) to paper scenario number (1/2/3).

    Paper mapping:
      S1 — telemetry 4 msg/s   (internal: scenario=1, rate=4)
      S2 — telemetry 16 msg/s  (internal: scenario=1, rate=16)
      S3 — command ping (1/s)  (internal: scenario=2)

    Parameters
    ----------
    scenario    : int — internal scenario number (1=telemetry, 2=ping)
    rate_msg_s  : int or None — msg/s for Scenario 1; None for Scenario 2

    Returns
    -------
    int: 1, 2, or 3
    """
    if scenario == 2:
        return 3
    # scenario == 1: distinguish by rate
    if rate_msg_s is not None and rate_msg_s >= 16:
        return 2
    return 1


def next_run_id(
    protocol: str,
    scenario: int,
    data_raw_dir: Path,
    rate_msg_s: Optional[int] = None,
) -> str:
    """Return the next unused run ID for a given (protocol, paper_scenario) tuple.

    Uses paper scenario numbers (S1/S2/S3) in the filename — no rate suffix.
    Counts existing CSV files in data_raw_dir matching the pattern and
    increments the run counter.

    Parameters
    ----------
    protocol    : str
    scenario    : int — internal scenario number (1=telemetry, 2=ping)
    data_raw_dir: Path
    rate_msg_s  : int or None — required for Scenario 1 to select S1 vs S2

    Returns
    -------
    str, e.g. 'websocket_s1_20260601_run003' or 'http_s3_20260601_run001'
    """
    paper_s = _paper_scenario_num(scenario, rate_msg_s)
    date_str = time.strftime("%Y%m%d")
    stem_prefix = f"{protocol}_s{paper_s}_{date_str}_run"
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
    start_timestamp_ms: Optional[int] = None,
) -> Path:
    """Write CSV + JSON sidecar to data_raw_dir.

    The JSON sidecar satisfies the reproducibility requirements for the paper:
    it records all RunConfig parameters, software versions, hardware metadata,
    and the run start time so that any run can be independently reproduced.

    Parameters
    ----------
    records : list[MetricRecord]
    run_id : str          — used as filename stem
    protocol : str
    scenario : int
    run_config_dict : dict — serialisable RunConfig fields for the sidecar
    data_raw_dir : Path   — destination directory (created if absent)
    notes : str           — operator notes (battery %, NTP status, network conditions…)
    start_timestamp_ms : int or None
        Unix wall-clock in ms when the run started.  Defaults to now() if not
        provided (only a fallback — callers should capture the true start time).

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

    # Flat top-level fields for the paper methodology table.  The full RunConfig
    # is also stored under "run_config" for complete reproducibility.
    rc = run_config_dict
    sidecar = {
        "run_id": run_id,
        "protocol": protocol,
        # Paper scenario number (1/2/3); internal scenario preserved in run_config.
        "paper_scenario": _paper_scenario_num(scenario, rc.get("rate_msg_s")),
        "scenario": scenario,
        # S1: rate in msg/s; S2/S3: None.  Directly accessible without parsing run_config.
        "rate_msg_s": rc.get("rate_msg_s"),
        "duration_s": rc.get("duration_s"),
        # Hardware / software metadata required for the paper Methods section.
        "cse_version": CSE_VERSION,
        "android_app_version": ANDROID_APP_VERSION,
        "drone_model": DRONE_MODEL,
        # Timing
        "start_timestamp_ms": start_timestamp_ms if start_timestamp_ms is not None
                               else time.time_ns() // 1_000_000,
        "created_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        # Full config for reproducibility (includes ack_run_timeout_s, n_commands, etc.)
        "run_config": rc,
        "notes": notes,
        "software_versions": {
            "python": sys.version,
            "streamlit": _get_streamlit_version(),
        },
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
