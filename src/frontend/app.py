"""OneM2M UxV Benchmark Dashboard — entry point.

Run with:
    streamlit run app.py

Pages:
    1_manual.py   — Manual command dispatch + live telemetry
    2_benchmark.py — Automated benchmark orchestrator
    3_results.py  — Quick view of collected data/raw/ CSVs
"""
import requests
import streamlit as st

from core.config import load_config

st.set_page_config(
    page_title="OneM2M UxV Benchmark",
    page_icon="🛸",
    layout="wide",
)

st.title("OneM2M UxV Benchmark Dashboard")
st.caption("IPL Leiria — Mestrado em Engenharia Informática — Multi-Protocol Performance Study")

# ------------------------------------------------------------------
# Load config (cached per session)
# ------------------------------------------------------------------

@st.cache_resource
def get_config():
    return load_config()

cfg = get_config()

# ------------------------------------------------------------------
# CSE health check
# ------------------------------------------------------------------

st.subheader("CSE Status")

def check_cse(cfg) -> tuple[bool, str]:
    """GET /id-in and return (healthy, message)."""
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
            return True, f"ACME CSE reachable at {cfg.cse_http_base} (HTTP 200, ty=5)"
        return False, f"Unexpected response: HTTP {resp.status_code}"
    except requests.ConnectionError:
        return False, f"Cannot reach {cfg.cse_http_base} — is Docker running?"
    except requests.Timeout:
        return False, "Connection timeout"

healthy, msg = check_cse(cfg)
if healthy:
    st.success(msg)
else:
    st.error(msg)

if st.button("Re-check CSE"):
    st.rerun()

# ------------------------------------------------------------------
# Configuration summary
# ------------------------------------------------------------------

st.subheader("Active Configuration")

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

st.divider()
st.markdown("""
**Navigation:**
- **Manual** — Send individual commands, view live telemetry
- **Benchmark** — Run automated experiments (Scenarios 1 & 2), save to `data/raw/`
- **Results** — Quick overview of collected run CSVs
""")
