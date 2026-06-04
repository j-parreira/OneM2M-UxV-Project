"""OneM2M UxV Benchmark Dashboard — entry point.

Benchmarks OneM2M middleware (ACME CSE v2025.11) across 4 transports for
DJI UxV operations. Academic context: IPL Leiria, MSc Engenharia Informática.
Deliverable: MDPI paper, 8–12 pages, Mobilidade em Sistemas Computacionais.

Run with:
    streamlit run app.py

Pages:
    1_manual.py   — Manual command dispatch + live telemetry (pre-benchmark validation)
    2_benchmark.py — Automated benchmark orchestrator (S1/S2 runs → data/raw/)
    3_results.py  — Aggregate view of collected CSVs (latency, overhead, comparison)

Authors: João Parreira, Pedro Barbeiro
"""
import re
from collections import defaultdict
from pathlib import Path

import requests
import streamlit as st

from core.config import load_config

st.set_page_config(
    page_title="OneM2M UxV Benchmark",
    page_icon="🛸",
    layout="wide",
)

# ------------------------------------------------------------------
# Title + research context
# ------------------------------------------------------------------

st.title("OneM2M UxV Benchmark Dashboard")
st.markdown(
    "**Research objective:** Quantify end-to-end latency, throughput, packet loss, and "
    "protocol overhead for OneM2M middleware across **WebSocket, MQTT, HTTP, and CoAP** "
    "in a DJI UxV operational scenario. "
    "Scenarios: S1 — sustained telemetry uplink (drone→CSE→dashboard); "
    "S2 — command round-trip (dashboard→CSE→drone→ACK). "
    "Target: ≥30 runs per protocol per scenario. "
    "Deliverable: MDPI paper, 8–12 pp. — IPL Leiria, MSc Engenharia Informática."
)

# ------------------------------------------------------------------
# Load config (cached per session)
# ------------------------------------------------------------------


@st.cache_resource
def get_config():
    """Load and cache configuration for the session lifetime."""
    return load_config()


cfg = get_config()

st.divider()

# ------------------------------------------------------------------
# System readiness checks
# ------------------------------------------------------------------

st.subheader("System Readiness")


def check_cse(cfg) -> tuple[bool, str]:
    """GET /id-in — verify CSE is healthy (ty=5 in response body).

    Returns:
        (healthy, status_message)
    """
    try:
        resp = requests.get(
            f"{cfg.cse_http_base}/id-in",
            headers={
                "X-M2M-Origin": "CAdmin",
                "X-M2M-RI": "health-check",
                "X-M2M-RVI": "3",
                "Accept": "application/json",
            },
            timeout=3.0,
        )
        if resp.status_code == 200 and ('"ty":5' in resp.text or '"ty": 5' in resp.text):
            return True, f"ACME CSE v2025.11 at `{cfg.cse_http_base}`"
        return False, f"HTTP {resp.status_code} — unexpected response"
    except requests.ConnectionError:
        return False, f"Cannot reach `{cfg.cse_http_base}` — start Docker Compose"
    except requests.Timeout:
        return False, "Timeout (3 s)"


def check_android_ae(cfg) -> tuple[bool, str]:
    """GET /cse-in/uxv — verify Android AE is registered (ty=2).

    Returns:
        (registered, status_message)
    """
    try:
        resp = requests.get(
            f"{cfg.cse_http_base}/cse-in/uxv",
            headers={
                "X-M2M-Origin": "CAdmin",
                "X-M2M-RI": "ae-check",
                "X-M2M-RVI": "3",
                "Accept": "application/json",
            },
            timeout=3.0,
        )
        if resp.status_code == 200:
            try:
                body = resp.json()
                # ACME CSE wraps resources; ty may be at top level or inside m2m:ae.
                ty = body.get("ty") or body.get("m2m:ae", {}).get("ty")
                if ty == 2:
                    return True, "AE `/cse-in/uxv` registered"
            except ValueError:
                pass
            return False, f"HTTP 200 but unexpected body"
        if resp.status_code == 404:
            return False, "AE not found — launch Android app"
        return False, f"HTTP {resp.status_code}"
    except requests.ConnectionError:
        return False, "CSE unreachable — check step 1"
    except requests.Timeout:
        return False, "Timeout (3 s)"


def count_runs(data_raw_dir: Path) -> dict:
    """Count completed runs per (protocol, scenario[, rate]) from CSV filenames.

    Handles both old format (<protocol>_s<N>_<YYYYMMDD>_run<NNN>.csv) and new
    S1-with-rate format (<protocol>_s1_r<rate>_<YYYYMMDD>_run<NNN>.csv).

    Returns:
        dict mapping (protocol, scenario_int, rate_or_None) → count
    """
    counts: dict[tuple[str, int, int | None], int] = defaultdict(int)
    # Group 3 (rate) is optional — present for S1 runs only.
    pattern = re.compile(
        r"^(websocket|mqtt|http|coap)_s([12])(?:_r(\d+))?_\d{8}_run\d+\.csv$"
    )
    if data_raw_dir.exists():
        for f in data_raw_dir.glob("*.csv"):
            m = pattern.match(f.name)
            if m:
                rate = int(m.group(3)) if m.group(3) else None
                counts[(m.group(1), int(m.group(2)), rate)] += 1
    return counts


col_cse, col_ae, col_data = st.columns(3)

cse_ok, cse_msg = check_cse(cfg)
ae_ok, ae_msg = check_android_ae(cfg)
counts = count_runs(cfg.data_raw_dir)

PROTOCOLS = ["websocket", "mqtt", "http", "coap"]
S1_RATES = [1, 5, 10]
TARGET = 30
total_runs = sum(counts.values())
# S1: 3 rates × 4 protocols × 30; S2: 4 protocols × 30.
needed = len(PROTOCOLS) * (len(S1_RATES) + 1) * TARGET

with col_cse:
    if cse_ok:
        st.success(f"**1. CSE** ✓\n\n{cse_msg}")
    else:
        st.error(f"**1. CSE** ✗\n\n{cse_msg}")

with col_ae:
    if ae_ok:
        st.success(f"**2. Android AE** ✓\n\n{ae_msg}")
    else:
        st.warning(f"**2. Android AE** —\n\n{ae_msg}")

with col_data:
    pct = int(total_runs / needed * 100) if needed > 0 else 0
    if total_runs >= needed:
        st.success(f"**3. Data** ✓\n\n{total_runs}/{needed} runs ({pct}%)")
    elif total_runs > 0:
        st.info(f"**3. Data** — in progress\n\n{total_runs}/{needed} runs ({pct}%)")
    else:
        st.warning(f"**3. Data** — not started\n\n0/{needed} runs")

# ------------------------------------------------------------------
# Collection progress matrix
# ------------------------------------------------------------------

st.divider()
st.subheader("Data Collection Progress")
st.caption(
    f"Target: {TARGET} runs per (protocol × scenario × rate). "
    f"S1 has 3 rates (1/5/10 msg/s); S2 is rate-independent. "
    f"Counts valid CSV files in `{cfg.data_raw_dir}`."
)

for proto in PROTOCOLS:
    # S1 — one progress bar per rate
    rate_cols = st.columns([1] + [3] * len(S1_RATES) + [3])
    with rate_cols[0]:
        st.markdown(f"**{proto.upper()}**")
    for i, rate in enumerate(S1_RATES):
        n = counts.get((proto, 1, rate), 0)
        with rate_cols[i + 1]:
            st.progress(min(1.0, n / TARGET), text=f"S1 r{rate}: {n}/{TARGET}")
    s2_n = counts.get((proto, 2, None), 0)
    with rate_cols[-1]:
        st.progress(min(1.0, s2_n / TARGET), text=f"S2: {s2_n}/{TARGET}")

if st.button("↻ Refresh"):
    st.rerun()

# ------------------------------------------------------------------
# Navigation guide
# ------------------------------------------------------------------

st.divider()
st.subheader("Workflow")

col_m, col_b, col_r = st.columns(3)
with col_m:
    st.markdown("""
**Manual** _(page 1)_

Pre-benchmark validation: connect via a specific protocol, send individual commands, verify live telemetry is flowing. Use before each benchmark session to confirm end-to-end connectivity.
""")
with col_b:
    st.markdown("""
**Benchmark** _(page 2)_

Automated experiment runner. Select protocol + scenario, configure rate/duration/n_commands, click Run. Logs one CSV + one JSON sidecar to `data/raw/` per run.
""")
with col_r:
    st.markdown("""
**Results** _(page 3)_

Aggregate view of all collected CSVs: protocol comparison table (latency/jitter/loss/overhead), latency distributions, CDF, CIN create RTT, overhead breakdown.
""")

# ------------------------------------------------------------------
# Active configuration (collapsed by default)
# ------------------------------------------------------------------

with st.expander("Active Configuration"):
    col1, col2 = st.columns(2)
    with col1:
        st.markdown(f"""
| Parameter | Value |
|---|---|
| CSE Host | `{cfg.cse_host}` |
| HTTP Port | `{cfg.cse_http_port}` |
| WebSocket Port | `{cfg.cse_ws_port}` |
| MQTT Port | `{cfg.cse_mqtt_port}` |
| CoAP Port | `{cfg.cse_coap_port}` |
""")
    with col2:
        st.markdown(f"""
| Parameter | Value |
|---|---|
| Callback Host | `{cfg.callback_host}` |
| HTTP Callback Port | `{cfg.callback_http_port}` |
| CoAP Callback Port | `{cfg.callback_coap_port}` |
| Data Directory | `{cfg.data_raw_dir}` |
""")
