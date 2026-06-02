"""Benchmark orchestrator page — automated multi-run experiment runner.

Runs one or more benchmark runs automatically, writing one CSV + JSON
sidecar per run to data/raw/. Supports:
  - Scenario 1 (telemetry): 4 or 16 msg/s, 120 s
  - Scenario 2 (commands): standard flight cycle or ping-only diagnostic
  - Multi-run loop (default 10 runs/combination for 95% CI)

See docs/benchmark-flow.md for the full experimental workflow.
"""
import threading
import time
from dataclasses import replace
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
        "Drone hovers. Android pushes telemetry CINs at **4 msg/s** (250 ms interval) "
        "for 2 min. Dashboard receives each via subscription notification.\n\n"
        "**Simulates:** single drone uplink.\n"
        "**Measures:** latency (`timestamp_ms − t_send_ms`), packet loss, overhead."
    )

with col_s2:
    st.info(
        "**S2 — Telemetry 16 msg/s**\n\n"
        "Same as S1 but at **16 msg/s** (62 ms interval). Rate set via "
        "`setTelemetryRate` command at run start — no app changes needed.\n\n"
        "**Simulates:** 4 simultaneous drones.\n"
        "**Measures:** same as S1, higher load."
    )

with col_s3:
    st.info(
        "**S3 — Command Round-Trip**\n\n"
        "Dashboard sends 120 commands at 1/s. Cycle: "
        "**takeoff → lights on → land → lights off**. Android ACKs each command.\n\n"
        "Ping-only mode available for baseline (no DJI action).\n"
        "**Measures:** `cin_create_ms` (NTP-free), `t_recv_ms − t_cmd_ms` (NTP), "
        "delivery rate."
    )

st.divider()

# ------------------------------------------------------------------
# Run configuration form
# ------------------------------------------------------------------

st.subheader("Run Configuration")

col1, col2, col3 = st.columns(3)

# Paper scenario → internal (scenario, rate_msg_s) mapping.
# S1/S2 map to internal scenario=1 (telemetry) with different rates.
# S3 maps to internal scenario=2 (commands).
_PAPER_SCENARIOS = {
    "S1 — Telemetry 4 msg/s":   (1, 4),
    "S2 — Telemetry 16 msg/s":  (1, 16),
    "S3 — Commands (1 cmd/s)":  (2, None),
}

with col1:
    protocol = st.selectbox("Protocol", ["websocket", "mqtt", "http", "coap"])
    paper_scenario_label = st.selectbox("Paper scenario", list(_PAPER_SCENARIOS.keys()))
    scenario, rate_msg_s = _PAPER_SCENARIOS[paper_scenario_label]
    n_runs = st.number_input(
        "Number of runs",
        min_value=1,
        max_value=20,
        value=10,
        step=1,
        help="Runs per combination. 10 runs → suitable for 95% CI in the paper.",
    )

with col2:
    if scenario == 1:
        duration_s = st.number_input("Duration (s)", min_value=10, max_value=600, value=120, step=10)
        n_commands = (rate_msg_s or 1) * duration_s
        ack_run_timeout_s = None
        inter_command_delay_ms = 0
        ping_only = False
        st.caption(f"Rate: **{rate_msg_s} msg/s** ({1000 // rate_msg_s} ms interval) — set via protocol at run start.")
    else:
        rate_msg_s = None
        n_commands = st.number_input(
            "Commands to send",
            min_value=1,
            max_value=500,
            value=120,
            help="120 cmds × 1 s delay ≈ 2 min per run.",
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
            max_value=900,
            value=600,
            step=10,
            help="Wall-clock cap per run. 600 s is safe for normal conditions.",
        )
        duration_s = int(ack_run_timeout_s)
        ping_only = st.checkbox(
            "Ping only (no DJI commands)",
            value=False,
            help="Send only 'ping' — no takeoff/land/identify. Baseline for pure protocol latency. Produces _s2p_ files.",
        )

with col3:
    notes = st.text_area("Operator notes", placeholder="Battery %, network conditions, …", height=120)

# Run-ID preview for the first run in the batch.
_preview_rate = rate_msg_s if scenario == 1 else None
preview_run_id = next_run_id(
    protocol, scenario, cfg.data_raw_dir, _preview_rate,
    ping_only=ping_only,
)
_last_id = f"{preview_run_id[:-3]}{int(preview_run_id[-3:]) + int(n_runs) - 1:03d}"
st.caption(
    f"Run ID preview: **{preview_run_id}** → **{_last_id}** ({int(n_runs)} run{'s' if int(n_runs) > 1 else ''})"
)

# NTP advisory.
st.warning(
    "**NTP sync required:** `latency_ms` is computed across two independent clocks "
    "(dev machine and Android RC). Ensure both are NTP-synchronised before starting. "
    "On Android: Settings → General Management → Date and Time → automatic.",
    icon="⚠️",
)

st.divider()

# ------------------------------------------------------------------
# Session state for the active run session
# ------------------------------------------------------------------

_SS_DEFAULTS = {
    "bench_running": False,
    "bench_stop_event": threading.Event(),
    "bench_results": [],          # list[RunResult] — all completed runs in current session
    "bench_current_run": 0,       # 1-based; 0 = not started
    "bench_n_runs_total": 0,
    "bench_progress": (0, 0, []), # (n_delivered, n_total, records) for the current run
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
    run_disabled = st.session_state.bench_running
    if st.button("▶ Run", disabled=run_disabled, use_container_width=True, type="primary"):
        # Base config — run_id is left empty so orchestrator auto-generates one per run.
        rc_template = RunConfig(
            protocol=protocol,
            scenario=scenario,
            rate_msg_s=rate_msg_s,
            n_commands=int(n_commands),
            duration_s=int(duration_s),
            ack_run_timeout_s=int(ack_run_timeout_s) if ack_run_timeout_s is not None else 600,
            inter_command_delay_ms=int(inter_command_delay_ms),
            ping_only=ping_only,
            notes=("[ping-only] " if ping_only else "") + notes,
        )
        n_runs_int = int(n_runs)

        st.session_state.bench_stop_event = threading.Event()
        st.session_state.bench_running = True
        st.session_state.bench_results = []
        st.session_state.bench_current_run = 0
        st.session_state.bench_n_runs_total = n_runs_int
        st.session_state.bench_error = None
        st.session_state.bench_progress = (0, 0, [])

        def _run_thread(rc_base: RunConfig, n: int) -> None:
            stop_ev = st.session_state.bench_stop_event
            for run_i in range(n):
                if stop_ev.is_set():
                    break

                st.session_state.bench_current_run = run_i + 1
                st.session_state.bench_progress = (0, 0, [])

                # Fresh RunConfig each iteration — empty run_id → auto-generated.
                rc_i = replace(rc_base, run_id="")

                def _progress_cb(n_del: int, n_tot: int, records: list) -> None:
                    st.session_state.bench_progress = (n_del, n_tot, records)

                try:
                    result = orchestrator_run(
                        run_cfg=rc_i,
                        config=cfg,
                        stop_event=stop_ev,
                        progress_cb=_progress_cb,
                    )
                    st.session_state.bench_results.append(result)
                except Exception as exc:
                    st.session_state.bench_error = f"Run {run_i + 1}: {exc}"
                    break

            st.session_state.bench_running = False

        t = threading.Thread(
            target=_run_thread,
            args=(rc_template, n_runs_int),
            daemon=True,
            name="bench-run",
        )
        add_script_run_ctx(t)  # allow session_state writes from background thread
        st.session_state.bench_thread = t
        t.start()
        st.rerun()

with stop_col:
    stop_disabled = not st.session_state.bench_running
    if st.button("■ Stop", disabled=stop_disabled, use_container_width=True):
        st.session_state.bench_stop_event.set()
        st.info("Stop signal sent — waiting for current operation to finish…")

st.divider()

# ------------------------------------------------------------------
# Live progress display
# ------------------------------------------------------------------

n_del, n_tot, records_so_far = st.session_state.bench_progress
current_run = st.session_state.bench_current_run
n_runs_total = st.session_state.bench_n_runs_total
error: Optional[str] = st.session_state.bench_error
completed_results: list[RunResult] = st.session_state.bench_results

if st.session_state.bench_running:
    st.subheader(f"Running… run {current_run}/{n_runs_total}")

    if scenario == 1:
        expected = (rate_msg_s or 1) * duration_s
        st.progress(min(1.0, n_del / max(expected, 1)))
        ca, cb, cc = st.columns(3)
        ca.metric("Received", n_del)
        cb.metric("Expected", expected)
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

    # Completed runs so far (in this session).
    if completed_results:
        st.write(f"**Completed: {len(completed_results)}/{n_runs_total}**")
        _df = pd.DataFrame([{
            "run_id": r.run_id,
            "delivered": f"{r.n_delivered}/{r.n_total}",
            "loss_%": f"{(1 - r.n_delivered / max(r.n_total, 1)) * 100:.1f}",
            "mean_latency_ms": f"{r.mean_latency_ms:.1f}" if r.mean_latency_ms else "—",
        } for r in completed_results])
        st.dataframe(_df, use_container_width=True)

    time.sleep(1.0)
    st.rerun()

elif error:
    st.error(f"Run failed: {error}")
    if completed_results:
        st.warning(f"Partial results: {len(completed_results)} run(s) completed before the error.")

elif completed_results:
    # ------------------------------------------------------------------
    # Final results — all runs complete
    # ------------------------------------------------------------------
    n_complete = len(completed_results)
    st.subheader(f"All {n_complete} run(s) complete")

    # Aggregate statistics across all runs.
    total_del = sum(r.n_delivered for r in completed_results)
    total_msgs = sum(r.n_total for r in completed_results)
    all_latencies = [
        r.latency_ms for run in completed_results
        for r in run.records
        if r.delivered and r.latency_ms is not None
    ]
    mean_lat = sum(all_latencies) / len(all_latencies) if all_latencies else None
    p95_lat = sorted(all_latencies)[int(len(all_latencies) * 0.95)] if all_latencies else None

    ca, cb, cc, cd, ce = st.columns(5)
    ca.metric("Runs", n_complete)
    cb.metric("Total delivered", f"{total_del}/{total_msgs}")
    cc.metric("Overall loss", f"{(1 - total_del / max(total_msgs, 1)) * 100:.1f}%")
    if mean_lat is not None:
        cd.metric("Mean latency", f"{mean_lat:.1f} ms")
        ce.metric("p95 latency", f"{p95_lat:.1f} ms")

    st.write("**Per-run summary:**")
    df_summary = pd.DataFrame([{
        "run_id": r.run_id,
        "delivered": f"{r.n_delivered}/{r.n_total}",
        "loss_%": f"{(1 - r.n_delivered / max(r.n_total, 1)) * 100:.1f}",
        "mean_latency_ms": round(r.mean_latency_ms, 2) if r.mean_latency_ms else None,
        "csv": Path(r.csv_path).name,
    } for r in completed_results])
    st.dataframe(df_summary, use_container_width=True)

    # Download buttons — one per run, in an expander to keep the page clean.
    with st.expander("Download CSVs"):
        for r in completed_results:
            p = Path(r.csv_path)
            if p.exists():
                with open(p, "rb") as f:
                    st.download_button(
                        label=p.name,
                        data=f.read(),
                        file_name=p.name,
                        mime="text/csv",
                        key=f"dl_{r.run_id}",
                    )

else:
    st.info("Configure a run above and click **▶ Run** to start.")
