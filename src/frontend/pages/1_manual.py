"""Manual control page — send individual commands and view live telemetry.

Used for integration testing and pre-benchmark validation (see benchmark-flow.md §2).
Connects to the CSE using the selected protocol, dispatches commands, and
subscribes to telemetry/ACK notifications for live display.
"""
import json
import math
import queue
import threading
import time
from typing import Optional

import streamlit as st

from core.config import load_config
from core.orchestrator import _make_client

st.set_page_config(page_title="Manual Control", layout="wide")
st.title("Manual Control")

cfg = load_config()

# ------------------------------------------------------------------
# Protocol selector + connection management
# ------------------------------------------------------------------

PROTOCOLS = ["websocket", "mqtt", "http", "coap"]

with st.sidebar:
    st.header("Connection")
    protocol = st.selectbox("Protocol", PROTOCOLS, index=0)
    st.caption(f"CSE: {cfg.cse_host}")

# Session state keys for the active client and message queues.
if "manual_client" not in st.session_state:
    st.session_state.manual_client = None
if "tel_queue" not in st.session_state:
    st.session_state.tel_queue = queue.Queue(maxsize=50)
if "ack_queue" not in st.session_state:
    st.session_state.ack_queue = queue.Queue(maxsize=50)
if "connected_protocol" not in st.session_state:
    st.session_state.connected_protocol = None
if "last_ack" not in st.session_state:
    st.session_state.last_ack = None

col_status, col_connect = st.columns([3, 1])

with col_status:
    if st.session_state.manual_client is not None:
        st.success(f"Connected — {st.session_state.connected_protocol.upper()}")
    else:
        st.error("Not connected")

with col_connect:
    if st.session_state.manual_client is None:
        if st.button("Connect", use_container_width=True):
            with st.spinner(f"Connecting via {protocol}…"):
                try:
                    client = _make_client(protocol, cfg)

                    # Clear queues from previous session.
                    while not st.session_state.tel_queue.empty():
                        st.session_state.tel_queue.get_nowait()
                    while not st.session_state.ack_queue.empty():
                        st.session_state.ack_queue.get_nowait()

                    # Capture queue references now (Streamlit main thread).
                    # Do NOT access st.session_state from recv callback threads —
                    # Streamlit session state is not accessible outside the main thread.
                    _tel_q = st.session_state.tel_queue
                    _ack_q = st.session_state.ack_queue

                    def on_telemetry(con: dict) -> None:
                        try:
                            _tel_q.put_nowait(con)
                        except queue.Full:
                            pass

                    def on_ack(con: dict) -> None:
                        try:
                            _ack_q.put_nowait(con)
                        except queue.Full:
                            pass

                    client.subscribe_telemetry(on_telemetry)
                    client.subscribe_ack(on_ack)
                    client.connect()
                    st.session_state.manual_client = client
                    st.session_state.connected_protocol = protocol
                    st.rerun()
                except Exception as e:
                    st.error(f"Connection failed: {e}")
    else:
        if st.button("Disconnect", use_container_width=True):
            try:
                st.session_state.manual_client.disconnect()
            except Exception:
                pass
            st.session_state.manual_client = None
            st.session_state.connected_protocol = None
            st.rerun()

st.divider()

# ------------------------------------------------------------------
# Command panel
# ------------------------------------------------------------------

st.subheader("Commands")

client: Optional = st.session_state.manual_client

CMD_BUTTONS = [
    ("Takeoff", "takeoff"),
    ("Land", "land"),
    ("Identify", "identify"),
    ("RTH", "startGoHome"),
]

cols = st.columns(len(CMD_BUTTONS) + 1)
seq_cmd = int(time.time())   # use timestamp as a loose sequence number

for i, (label, cmd) in enumerate(CMD_BUTTONS):
    with cols[i]:
        if st.button(label, disabled=client is None, use_container_width=True):
            t_cmd_ms = int(time.time() * 1000)
            payload = {"command": cmd, "seq_cmd": seq_cmd, "t_cmd_ms": t_cmd_ms}
            with st.spinner(f"Sending {cmd}…"):
                latency_ms, ok = client.send_command(payload)
            if ok:
                st.success(f"Sent — CIN latency: {latency_ms:.1f} ms")
            else:
                st.error("Send failed")

with cols[-1]:
    custom_cmd = st.text_input("Custom JSON", placeholder='{"command":"identify"}', label_visibility="collapsed")
    if st.button("Send custom", disabled=client is None or not custom_cmd, use_container_width=True):
        try:
            payload = json.loads(custom_cmd)
        except json.JSONDecodeError:
            st.error("Invalid JSON")
            payload = None
        if payload is not None:
            latency_ms, ok = client.send_command(payload)
            if ok:
                st.success(f"Sent — {latency_ms:.1f} ms")
            else:
                st.error("Send failed")

# Telemetry rate control (Scenario 1 pre-test).
st.subheader("Telemetry Rate Control")
rate_col, send_col = st.columns([2, 1])
with rate_col:
    rate = st.selectbox("Rate", [1, 5, 10], format_func=lambda r: f"{r} msg/s", index=1)
with send_col:
    if st.button("Set Rate", disabled=client is None, use_container_width=True):
        interval_ms = 1000 // rate
        client.send_command({"command": "setTelemetryRate", "intervalMs": interval_ms})
        st.success(f"Rate set to {rate} msg/s ({interval_ms} ms)")

st.divider()

# ------------------------------------------------------------------
# Live telemetry display
# ------------------------------------------------------------------

st.subheader("Live Telemetry")


@st.fragment(run_every=0.5)
def _live_display() -> None:
    """Fragment that polls the queues every 0.5 s.

    Using @st.fragment avoids a full-page rerun on each tick, which eliminates
    the browser title-bar spinner while the live display is updating.
    Displays a 3×3 grid of telemetry metrics from the Android CIN payload:
    {seq, t_send_ms, lat, lng, alt, velX, velY, velZ, heading, bat, flightState, gimbalPitch}.
    """
    # Drain the telemetry queue — keep only the latest reading.
    latest_tel: Optional[dict] = None
    while not st.session_state.tel_queue.empty():
        try:
            latest_tel = st.session_state.tel_queue.get_nowait()
        except queue.Empty:
            break

    if latest_tel:
        # Row 1: position + kinematics
        r1a, r1b, r1c = st.columns(3)
        with r1a:
            lat = latest_tel.get("lat")
            lng = latest_tel.get("lng")
            if lat is not None and lng is not None:
                st.metric("GPS", f"{lat:.5f}, {lng:.5f}")
            else:
                st.metric("GPS", "—")
        with r1b:
            alt = latest_tel.get("alt")
            st.metric("Altitude", f"{alt:.1f} m" if alt is not None else "—")
        with r1c:
            vel_x = latest_tel.get("velX")
            vel_y = latest_tel.get("velY")
            if vel_x is not None and vel_y is not None:
                st.metric("Speed", f"{math.hypot(vel_x, vel_y):.1f} m/s")
            else:
                st.metric("Speed", "—")

        # Row 2: orientation + battery + flight state
        r2a, r2b, r2c = st.columns(3)
        with r2a:
            heading = latest_tel.get("heading")
            st.metric("Heading", f"{heading:.1f}°" if heading is not None else "—")
        with r2b:
            bat_obj = latest_tel.get("bat", {})
            bat_lvl = bat_obj.get("lvl") if isinstance(bat_obj, dict) else None
            bat_v = bat_obj.get("v") if isinstance(bat_obj, dict) else None
            label = f"{bat_lvl}%" if bat_lvl is not None else "—"
            delta = f"{bat_v:.1f} V" if bat_v is not None else None
            st.metric("Battery", label, delta=delta, delta_color="off")
        with r2c:
            state = latest_tel.get("flightState", "—")
            st.metric("Flight State", state)

        # Row 3: gimbal + sequence counter + telemetry age
        r3a, r3b, r3c = st.columns(3)
        with r3a:
            gp = latest_tel.get("gimbalPitch")
            st.metric("Gimbal Pitch", f"{gp:.1f}°" if gp is not None else "—")
        with r3b:
            seq = latest_tel.get("seq")
            st.metric("Seq #", seq if seq is not None else "—")
        with r3c:
            t_send = latest_tel.get("t_send_ms")
            if t_send is not None:
                age_ms = int(time.time() * 1000) - t_send
                st.metric("CIN age", f"{age_ms} ms")
            else:
                st.metric("CIN age", "—")
    else:
        st.info("No telemetry yet — connect and ensure the drone is active.")

    # Drain the ACK queue.
    latest_ack: Optional[dict] = None
    while not st.session_state.ack_queue.empty():
        try:
            latest_ack = st.session_state.ack_queue.get_nowait()
        except queue.Empty:
            break

    if latest_ack:
        t_cmd = latest_ack.get("t_cmd_ms")
        t_recv = latest_ack.get("t_recv_ms")
        latency = (t_recv - t_cmd) if (t_recv and t_cmd) else "—"
        st.success(
            f"Last ACK: cmd={latest_ack.get('command')} seq={latest_ack.get('seq_cmd')} "
            f"latency={latency} ms"
        )


_live_display()
