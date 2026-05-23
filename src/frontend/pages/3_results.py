"""Results viewer — quick overview of collected data/raw/ CSVs.

Shows a summary table of all runs in data/raw/, with per-run statistics.
Intended for a quick sanity check during data collection — full statistical
analysis is performed by src/analysis/scripts/ and notebooks/.
"""
import json
from pathlib import Path

import pandas as pd
import plotly.express as px
import streamlit as st

from core.config import load_config

st.set_page_config(page_title="Results", layout="wide")
st.title("Collected Results")

cfg = load_config()
data_dir = cfg.data_raw_dir

# ------------------------------------------------------------------
# Load all CSVs from data/raw/
# ------------------------------------------------------------------

if not data_dir.exists() or not any(data_dir.glob("*.csv")):
    st.info(f"No CSV files found in `{data_dir}`. Run some benchmarks first.")
    st.stop()

csv_files = sorted(data_dir.glob("*.csv"))
st.caption(f"Found {len(csv_files)} run(s) in `{data_dir}`")

@st.cache_data(ttl=30)
def load_all_runs(data_dir_str: str) -> pd.DataFrame:
    """Load and concatenate all CSVs into a single DataFrame."""
    dfs = []
    for f in sorted(Path(data_dir_str).glob("*.csv")):
        try:
            df = pd.read_csv(f)
            dfs.append(df)
        except Exception:
            pass
    if not dfs:
        return pd.DataFrame()
    return pd.concat(dfs, ignore_index=True)

df_all = load_all_runs(str(data_dir))

if df_all.empty:
    st.error("Could not parse any CSV files.")
    st.stop()

# ------------------------------------------------------------------
# Filter controls
# ------------------------------------------------------------------

col1, col2, col3 = st.columns(3)
with col1:
    protocols = ["all"] + sorted(df_all["protocol"].dropna().unique().tolist())
    sel_protocol = st.selectbox("Protocol", protocols)
with col2:
    scenarios = ["all"] + sorted(df_all["scenario"].dropna().unique().astype(int).tolist())
    sel_scenario = st.selectbox("Scenario", scenarios)
with col3:
    runs = ["all"] + sorted(df_all["run_id"].dropna().unique().tolist())
    sel_run = st.selectbox("Run ID", runs)

df = df_all.copy()
if sel_protocol != "all":
    df = df[df["protocol"] == sel_protocol]
if sel_scenario != "all":
    df = df[df["scenario"] == int(sel_scenario)]
if sel_run != "all":
    df = df[df["run_id"] == sel_run]

st.caption(f"Showing {len(df)} records from {df['run_id'].nunique()} run(s)")

# ------------------------------------------------------------------
# Per-run summary table
# ------------------------------------------------------------------

st.subheader("Run Summary")

summary = (
    df.groupby(["run_id", "protocol", "scenario"])
    .agg(
        n_total=("seq", "count"),
        n_delivered=("delivered", "sum"),
        mean_latency_ms=("latency_ms", "mean"),
        p95_latency_ms=("latency_ms", lambda x: x.quantile(0.95)),
        mean_payload_bytes=("payload_bytes", "mean"),
        mean_header_bytes=("header_bytes", "mean"),
    )
    .reset_index()
)
summary["loss_pct"] = (1 - summary["n_delivered"] / summary["n_total"].clip(lower=1)) * 100
summary["overhead_pct"] = (
    summary["mean_header_bytes"]
    / (summary["mean_header_bytes"] + summary["mean_payload_bytes"]).clip(lower=1)
    * 100
)

st.dataframe(
    summary.round(2),
    use_container_width=True,
    column_config={
        "mean_latency_ms": st.column_config.NumberColumn("Mean latency (ms)", format="%.1f"),
        "p95_latency_ms": st.column_config.NumberColumn("p95 latency (ms)", format="%.1f"),
        "loss_pct": st.column_config.NumberColumn("Loss (%)", format="%.1f"),
        "overhead_pct": st.column_config.NumberColumn("Overhead (%)", format="%.1f"),
    },
)

# ------------------------------------------------------------------
# Quick plots
# ------------------------------------------------------------------

if df["latency_ms"].notna().any():
    st.subheader("Latency Distribution")
    fig = px.box(
        df[df["latency_ms"].notna()],
        x="protocol",
        y="latency_ms",
        color="protocol",
        facet_col="scenario",
        labels={"latency_ms": "Latency (ms)", "protocol": "Protocol"},
        points="outliers",
    )
    fig.update_layout(showlegend=False)
    st.plotly_chart(fig, use_container_width=True)

if df["latency_ms"].notna().any():
    st.subheader("Latency CDF")
    fig2 = px.ecdf(
        df[df["latency_ms"].notna()],
        x="latency_ms",
        color="protocol",
        labels={"latency_ms": "Latency (ms)"},
    )
    st.plotly_chart(fig2, use_container_width=True)

# ------------------------------------------------------------------
# Sidecar metadata viewer
# ------------------------------------------------------------------

st.subheader("Run Metadata (JSON sidecars)")

json_files = sorted(data_dir.glob("*.json"))
if json_files:
    selected_json = st.selectbox("Sidecar", [f.name for f in json_files])
    json_path = data_dir / selected_json
    try:
        with open(json_path) as f:
            sidecar = json.load(f)
        st.json(sidecar)
    except Exception as e:
        st.error(f"Could not read {selected_json}: {e}")
