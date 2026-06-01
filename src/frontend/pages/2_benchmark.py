"""Benchmark orchestrator page — automated experiment runner.

Runs a complete benchmark scenario via the configured protocol, logs metrics
to data/raw/, and shows live progress. Supports graceful stop mid-run.

One run = one CSV + one JSON sidecar. Runs are NOT repeatable via this page
in a loop — run it manually 30 times (or script via CLI) for the 30-repetition
requirement in the methodology.

See docs/benchmark-flow.md for the full experimental workflow.
"""
import threading
import time
from pathlib import Path
from typing import Optional

import pandas as pd
import streamlit as st
from streamlit.runtime.scriptrunner import add_script_run_ctx

from core.config import load_config
from core.orchestrator import RunConfig, RunResult, run as orchestrator_run

st.set_page_config(page_title="Benchmark", layout="wide")
st.title("Benchmark Orchestrator")

cfg = load_config()

# ------------------------------------------------------------------
# Scenario reference cards
# ------------------------------------------------------------------

st.subheader("Scenarios")

col_s1, col_s2 = st.columns(2)

with col_s1:
    st.info(
        "**Scenario 1 — Telemetry Uplink**\n\n"
        "Drone hovers at fixed altitude. Android app pushes telemetry CINs to "
        "`cse-in/uxv/telemetry` at a fixed rate. Dashboard receives each CIN via "
        "subscription notification and records arrival timestamp.\n\n"
        "**Measures:** delivery latency (`timestamp_ms − t_send_ms`), packet loss "
        "(gaps in `seq`), protocol overhead (`header_bytes / total_bytes`). "
        "Rates: 1, 5, 10 msg/s · Duration: 5 min."
    )

with col_s2:
    st.info(
        "**Scenario 2 — Command Round-Trip**\n\n"
        "Dashboard sends 50 command CINs in rapid succession to `cse-in/uxv/commands`. "
        "Android receives each via subscription notification, dispatches to DJI SDK, "
        "and sends an ACK CIN to `cse-in/uxv/ack` with timestamps.\n\n"
        "**Measures:** CIN create RTT (`cin_create_ms`, Streamlit→CSE, NTP-free); "
        "command latency (`t_recv_ms − t_cmd_ms`, cross-device NTP); "
        "delivery rate within 10 s timeout."
    )

st.divider()

# ------------------------------------------------------------------
# Run configuration form
# ------------------------------------------------------------------

st.subheader("Run Configuration")

col1, col2, col3 = st.columns(3)

with col1:
    protocol = st.selectbox("Protocol", ["websocket", "mqtt", "http", "coap"])
    scenario = st.selectbox("Scenario", [1, 2], format_func=lambda s: f"Scenario {s}")

with col2:
    if scenario == 1:
        rate_msg_s = st.selectbox("Telemetry rate", [1, 5, 10], format_func=lambda r: f"{r} msg/s")
        duration_s = st.number_input("Duration (s)", min_value=10, max_value=600, value=300, step=10)
        n_commands = (rate_msg_s or 1) * duration_s
        ack_run_timeout_s = None  # unused in S1
    else:
        rate_msg_s = None
        n_commands = st.number_input("Commands to send", min_value=1, max_value=200, value=50)
        ack_run_timeout_s = st.number_input(
            "Run timeout (s)",
            min_value=10,
            max_value=600,
            value=60,
            step=10,
            help="Wall-clock cap for the whole burst. Prevents a full 10 s/ACK wait × n_commands worst case.",
        )
        duration_s = int(ack_run_timeout_s)  # used only for the sidecar; not a hard deadline in S2

with col3:
    notes = st.text_area("Operator notes", placeholder="Battery %, network conditions, …", height=100)

# Generate a preview of the run_id that will be used.
from core.logger import next_run_id
preview_run_id = next_run_id(protocol, scenario, cfg.data_raw_dir, rate_msg_s)
st.caption(f"Run ID (preview): **{preview_run_id}**")

# NTP advisory — latency_ms is cross-device; both machines must be NTP-synced for valid results.
if scenario in (1, 2):
    st.warning(
        "**NTP sync required:** `latency_ms` is computed across two independent clocks "
        "(dev machine and Android RC). Ensure both are NTP-synchronised before starting a run. "
        "On Android, enable automatic date/time in Settings → General Management → Date and Time.",
        icon="⚠️",
    )

st.divider()

# ------------------------------------------------------------------
# Session state for the active run
# ------------------------------------------------------------------

if "bench_running" not in st.session_state:
    st.session_state.bench_running = False
if "bench_stop_event" not in st.session_state:
    st.session_state.bench_stop_event = threading.Event()
if "bench_result" not in st.session_state:
    st.session_state.bench_result = None
if "bench_progress" not in st.session_state:
    st.session_state.bench_progress = (0, 0, [])   # (n_delivered, n_total, records)
if "bench_thread" not in st.session_state:
    st.session_state.bench_thread = None
if "bench_error" not in st.session_state:
    st.session_state.bench_error = None

# ------------------------------------------------------------------
# Run / Stop controls
# ------------------------------------------------------------------

run_col, stop_col = st.columns([1, 1])

with run_col:
    run_disabled = st.session_state.bench_running
    if st.button("▶ Run", disabled=run_disabled, use_container_width=True, type="primary"):
        rc = RunConfig(
            protocol=protocol,
            scenario=scenario,
            rate_msg_s=rate_msg_s,
            n_commands=int(n_commands),
            duration_s=int(duration_s),
            ack_run_timeout_s=int(ack_run_timeout_s) if ack_run_timeout_s is not None else 60,
            notes=notes,
        )
        st.session_state.bench_stop_event = threading.Event()
        st.session_state.bench_running = True
        st.session_state.bench_result = None
        st.session_state.bench_error = None
        st.session_state.bench_progress = (0, 0, [])

        def _progress_cb(n_del, n_tot, records):
            st.session_state.bench_progress = (n_del, n_tot, records)

        def _run_thread():
            try:
                result = orchestrator_run(
                    run_cfg=rc,
                    config=cfg,
                    stop_event=st.session_state.bench_stop_event,
                    progress_cb=_progress_cb,
                )
                st.session_state.bench_result = result
            except Exception as e:
                st.session_state.bench_error = str(e)
            finally:
                st.session_state.bench_running = False

        t = threading.Thread(target=_run_thread, daemon=True, name="bench-run")
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
result: Optional[RunResult] = st.session_state.bench_result
error: Optional[str] = st.session_state.bench_error

if st.session_state.bench_running:
    st.subheader("Running…")

    if scenario == 1:
        expected = (rate_msg_s or 1) * duration_s
        progress = min(1.0, n_del / max(expected, 1))
        st.progress(progress)
        col_a, col_b, col_c = st.columns(3)
        col_a.metric("Received", n_del)
        col_b.metric("Expected", expected)
        col_c.metric("Loss so far", f"{max(0, n_tot - n_del)} msgs")
    else:
        progress = min(1.0, n_tot / max(n_commands, 1))
        st.progress(progress)
        col_a, col_b, col_c = st.columns(3)
        col_a.metric("Commands sent", n_tot)
        col_b.metric("ACKs received", n_del)
        col_c.metric("Loss", n_tot - n_del)

    # Show last few records as a live table.
    if records_so_far:
        df = pd.DataFrame([{
            "seq": r.seq,
            "latency_ms": f"{r.latency_ms:.1f}" if r.latency_ms is not None else "—",
            "delivered": r.delivered,
            "payload_bytes": r.payload_bytes,
        } for r in records_so_far[-10:]])
        st.dataframe(df, use_container_width=True)

    # Auto-refresh while running.
    time.sleep(1.0)
    st.rerun()

elif error:
    st.error(f"Run failed: {error}")

elif result is not None:
    # ------------------------------------------------------------------
    # Results summary
    # ------------------------------------------------------------------
    st.subheader("Run Complete")

    c1, c2, c3, c4 = st.columns(4)
    c1.metric("Run ID", result.run_id)
    c2.metric("Delivered", f"{result.n_delivered} / {result.n_total}")
    loss_pct = (1 - result.n_delivered / max(result.n_total, 1)) * 100
    c3.metric("Packet loss", f"{loss_pct:.1f}%")
    if result.mean_latency_ms is not None:
        c4.metric("Mean latency", f"{result.mean_latency_ms:.1f} ms")
    else:
        c4.metric("Mean latency", "—")

    st.caption(f"CSV: `{result.csv_path}`")

    # Download button.
    csv_path = Path(result.csv_path)
    if csv_path.exists():
        with open(csv_path, "rb") as f:
            csv_bytes = f.read()
        st.download_button(
            label="Download CSV",
            data=csv_bytes,
            file_name=csv_path.name,
            mime="text/csv",
        )

    # Show full results table.
    if result.records:
        df = pd.DataFrame([{
            "seq": r.seq,
            "direction": r.direction,
            "latency_ms": r.latency_ms,
            "payload_bytes": r.payload_bytes,
            "header_bytes": r.header_bytes,
            "delivered": r.delivered,
        } for r in result.records])
        st.dataframe(df, use_container_width=True)

else:
    st.info("Configure a run above and click **▶ Run** to start.")
