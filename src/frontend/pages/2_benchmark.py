"""Benchmark orchestrator page — single-run experiment runner.

Runs one benchmark run per click, writes CSV + JSON sidecar to data/raw/.

Scenarios (paper mapping):
  S1 — Telemetry 4 msg/s  (internal scenario=1, rate=4,  duration=60 s)
  S2 — Telemetry 16 msg/s (internal scenario=1, rate=16, duration=60 s)
  S3 — Command ping       (internal scenario=2, 60 cmds, 1 cmd/s)

Each scenario is run 10 times per protocol (10 runs × 3 scenarios × 4 protocols = 120 total).

See docs/benchmark-flow.md for the full experimental workflow.

Authors: João Parreira, Pedro Barbeiro
"""
import threading
import time
from pathlib import Path
from typing import Optional

import pandas as pd
import streamlit as st
from streamlit.runtime.scriptrunner import add_script_run_ctx

from core.config import load_config
from core.logger import next_run_id
from core.orchestrator import RunConfig, RunResult, run as orchestrator_run

st.set_page_config(page_title="Benchmark", layout="wide")
st.title("Benchmark Orchestrator")

cfg = load_config()

# ------------------------------------------------------------------
# Scenario reference cards
# ------------------------------------------------------------------

st.subheader("Scenarios")

col_s1, col_s2, col_s3 = st.columns(3)

with col_s1:
    st.info(
        "**S1 — Telemetry 4 msg/s**\n\n"
        "Drone hovers. Android pushes telemetry CINs at **4 msg/s** (250 ms) "
        "for **1 min**. Rate set via `setTelemetryRate` at run start.\n\n"
        "**Measures:** latency (`timestamp_ms − t_send_ms`), packet loss, overhead."
    )

with col_s2:
    st.info(
        "**S2 — Telemetry 16 msg/s**\n\n"
        "Same as S1 at **16 msg/s** (62 ms) for **1 min**. Simulates 4 simultaneous drones.\n\n"
        "**Measures:** same as S1, higher load.\n\n"
        "⚠️ **Expected:** ACME CSE v2025.11 TinyDB ceiling ≈ 4.9 msg/s → "
        "~64 % packet loss at 16 msg/s is the paper result, not a bug. "
        "Latency grows linearly as the CSE queue builds. Run completes the full 60 s."
    )

with col_s3:
    st.info(
        "**S3 — Command ping (1 cmd/s)**\n\n"
        "Dashboard sends **60 `ping`** commands at 1/s (1 min). Android ACKs each "
        "immediately (no DJI SDK action). Measures pure protocol latency.\n\n"
        "**Measures:** `cin_create_ms` (NTP-free), `t_recv_ms − t_cmd_ms` (NTP), "
        "delivery rate within 10 s timeout."
    )

st.divider()

# ------------------------------------------------------------------
# Run configuration form
# ------------------------------------------------------------------

st.subheader("Run Configuration")

# Paper scenario → internal (scenario, rate_msg_s) mapping.
_PAPER_SCENARIOS = {
    "S1 — Telemetry 4 msg/s":   (1, 4),
    "S2 — Telemetry 16 msg/s":  (1, 16),
    "S3 — Command ping (1/s)":  (2, None),
}

col1, col2, col3 = st.columns(3)

with col1:
    protocol = st.selectbox("Protocol", ["websocket", "mqtt", "http", "coap"])
    paper_scenario_label = st.selectbox("Paper scenario", list(_PAPER_SCENARIOS.keys()))
    scenario, rate_msg_s = _PAPER_SCENARIOS[paper_scenario_label]

with col2:
    if scenario == 1:
        duration_s = st.number_input("Duration (s)", min_value=10, max_value=600, value=60, step=10)
        n_commands = (rate_msg_s or 1) * duration_s
        ack_run_timeout_s = 600
        inter_command_delay_ms = 0
        st.caption(f"Rate: **{rate_msg_s} msg/s** ({1000 // rate_msg_s} ms) — set via protocol at run start.")
    else:
        rate_msg_s = None
        duration_s = 120
        n_commands = st.number_input(
            "Commands to send",
            min_value=1,
            max_value=500,
            value=60,
            help="60 cmds × 1 s delay ≈ 1 min per run.",
        )
        inter_command_delay_ms = st.number_input(
            "Inter-command delay (ms)",
            min_value=0,
            max_value=5000,
            value=1000,
            step=100,
            help="Sleep between commands (after ACK). 1000 ms → ~1 cmd/s.",
        )
        ack_run_timeout_s = st.number_input(
            "Run timeout (s)",
            min_value=10,
            max_value=1200,
            value=600,
            step=30,
            help="Wall-clock cap. 60 cmds × (15 s ACK timeout + 1 s delay) = 960 s worst case. "
                 "WS S3 uses a 10 s warmup drain, so add ~10 s to expected run time.",
        )

with col3:
    notes = st.text_area("Operator notes", placeholder="Battery %, network conditions, …", height=100)

_preview_rate = rate_msg_s if scenario == 1 else None
preview_run_id = next_run_id(protocol, scenario, cfg.data_raw_dir, _preview_rate)
st.caption(f"Run ID: **{preview_run_id}**")

st.warning(
    "**NTP sync required** for `latency_ms` (two independent clocks). "
    "On Android: Settings → General Management → Date and Time → automatic.",
    icon="⚠️",
)

st.divider()

# ------------------------------------------------------------------
# Session state
# ------------------------------------------------------------------

_SS_DEFAULTS = {
    "bench_running": False,
    "bench_stop_event": threading.Event(),
    "bench_result": None,
    "bench_progress": (0, 0, []),
    "bench_thread": None,
    "bench_error": None,
}
for _k, _v in _SS_DEFAULTS.items():
    if _k not in st.session_state:
        st.session_state[_k] = _v

# ------------------------------------------------------------------
# Run / Stop controls
# ------------------------------------------------------------------

run_col, stop_col = st.columns([1, 1])

with run_col:
    if st.button("▶ Run", disabled=st.session_state.bench_running,
                 use_container_width=True, type="primary"):
        rc = RunConfig(
            protocol=protocol,
            scenario=scenario,
            rate_msg_s=rate_msg_s,
            n_commands=int(n_commands),
            duration_s=int(duration_s),
            ack_run_timeout_s=int(ack_run_timeout_s),
            inter_command_delay_ms=int(inter_command_delay_ms),
            notes=notes,
        )
        st.session_state.bench_stop_event = threading.Event()
        st.session_state.bench_running = True
        st.session_state.bench_result = None
        st.session_state.bench_error = None
        st.session_state.bench_progress = (0, 0, [])

        def _progress_cb(n_del: int, n_tot: int, records: list) -> None:
            st.session_state.bench_progress = (n_del, n_tot, records)

        def _run_thread() -> None:
            try:
                result = orchestrator_run(
                    run_cfg=rc,
                    config=cfg,
                    stop_event=st.session_state.bench_stop_event,
                    progress_cb=_progress_cb,
                )
                st.session_state.bench_result = result
            except Exception as exc:
                st.session_state.bench_error = str(exc)
            finally:
                st.session_state.bench_running = False

        t = threading.Thread(target=_run_thread, daemon=True, name="bench-run")
        add_script_run_ctx(t)
        st.session_state.bench_thread = t
        t.start()
        st.rerun()

with stop_col:
    if st.button("■ Stop", disabled=not st.session_state.bench_running,
                 use_container_width=True):
        st.session_state.bench_stop_event.set()
        st.info("Stop signal sent — waiting for current operation to finish…")

st.divider()

# ------------------------------------------------------------------
# Live progress / results
# ------------------------------------------------------------------

n_del, n_tot, records_so_far = st.session_state.bench_progress
result: Optional[RunResult] = st.session_state.bench_result
error: Optional[str] = st.session_state.bench_error

if st.session_state.bench_running:
    st.subheader("Running…")

    if scenario == 1:
        expected_total = (rate_msg_s or 1) * duration_s
        # n_tot from progress_cb = time-based expected so far (rate × elapsed_s),
        # so loss = n_tot - n_del is meaningful during the run.
        st.progress(min(1.0, n_del / max(expected_total, 1)))
        ca, cb, cc = st.columns(3)
        ca.metric("Received", n_del)
        cb.metric("Expected (run total)", expected_total)
        cc.metric("Loss so far", max(0, n_tot - n_del))
    else:
        st.progress(min(1.0, n_tot / max(n_commands, 1)))
        ca, cb, cc = st.columns(3)
        ca.metric("Commands sent", n_tot)
        cb.metric("ACKs received", n_del)
        cc.metric("Loss", n_tot - n_del)

    if records_so_far:
        df = pd.DataFrame([{
            "seq": r.seq,
            "latency_ms": f"{r.latency_ms:.1f}" if r.latency_ms is not None else "—",
            "delivered": r.delivered,
            "payload_bytes": r.payload_bytes,
        } for r in records_so_far[-10:]])
        st.dataframe(df, use_container_width=True)

    time.sleep(1.0)
    st.rerun()

elif error:
    st.error(f"Run failed: {error}")

elif result is not None:
    st.subheader("Run Complete")

    c1, c2, c3, c4 = st.columns(4)
    c1.metric("Run ID", result.run_id)
    c2.metric("Delivered", f"{result.n_delivered} / {result.n_total}")
    loss_pct = max(0.0, (1 - result.n_delivered / max(result.n_total, 1)) * 100)
    c3.metric("Packet loss", f"{loss_pct:.1f}%")
    if result.mean_latency_ms is not None:
        c4.metric("Mean latency", f"{result.mean_latency_ms:.1f} ms")
    else:
        c4.metric("Mean latency", "—")

    st.caption(f"CSV: `{result.csv_path}`")

    csv_path = Path(result.csv_path)
    if csv_path.exists():
        with open(csv_path, "rb") as f:
            st.download_button("Download CSV", f.read(), csv_path.name, "text/csv")

    if result.records:
        df = pd.DataFrame([{
            "seq": r.seq,
            "latency_ms": r.latency_ms,
            "cin_create_ms": r.cin_create_ms,
            "delivered": r.delivered,
            "payload_bytes": r.payload_bytes,
            "header_bytes": r.header_bytes,
        } for r in result.records])
        st.dataframe(df, use_container_width=True)

else:
    st.info("Configure a run above and click **▶ Run** to start.")
