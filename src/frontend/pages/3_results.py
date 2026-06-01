"""Results viewer — aggregate overview of collected data/raw/ CSVs.

Shows a protocol comparison table, latency distributions, overhead breakdown,
and per-run detail. Intended for quick sanity checks during data collection
and a paper-ready summary view.

Full statistical analysis (confidence intervals, significance tests, figures)
is performed by src/analysis/scripts/ and notebooks/.
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
# Protocol comparison table (aggregate across all runs)
# ------------------------------------------------------------------

st.subheader("Protocol Comparison")
st.caption(
    "Aggregated across all runs and scenarios. "
    "Latency is NTP-dependent (two clocks: dev machine + Android RC). "
    "CIN create RTT (Scenario 2 only) is NTP-free (single machine)."
)


def build_comparison(df: pd.DataFrame) -> pd.DataFrame:
    """Aggregate per-protocol statistics across all runs.

    Returns:
        DataFrame with one row per protocol containing mean/p95/jitter/loss/overhead.
    """
    rows = []
    for proto in sorted(df["protocol"].dropna().unique()):
        sub = df[df["protocol"] == proto]
        n_runs = sub["run_id"].nunique()
        n_msgs = len(sub)
        lat = sub["latency_ms"].dropna()
        rows.append({
            "Protocol": proto.upper(),
            "Runs": n_runs,
            "Messages": n_msgs,
            "Mean lat (ms)": lat.mean() if len(lat) else None,
            "p95 lat (ms)": lat.quantile(0.95) if len(lat) else None,
            "Jitter (ms)": lat.std() if len(lat) else None,
            "Loss (%)": (1 - sub["delivered"].mean()) * 100 if "delivered" in sub else None,
            "Overhead (%)": (
                sub["header_bytes"].mean()
                / max(sub["header_bytes"].mean() + sub["payload_bytes"].mean(), 1e-9)
                * 100
            ) if "header_bytes" in sub.columns and "payload_bytes" in sub.columns else None,
        })
    return pd.DataFrame(rows)


comparison_df = build_comparison(df_all)
if not comparison_df.empty:
    num_cols = ["Mean lat (ms)", "p95 lat (ms)", "Jitter (ms)", "Loss (%)", "Overhead (%)"]
    col_cfg_cmp = {
        c: st.column_config.NumberColumn(c, format="%.2f") for c in num_cols
    }
    st.dataframe(comparison_df.round(2), use_container_width=True, column_config=col_cfg_cmp)

st.divider()

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

has_cin = "cin_create_ms" in df.columns

summary_agg = {
    "n_total": ("seq", "count"),
    "n_delivered": ("delivered", "sum"),
    "mean_latency_ms": ("latency_ms", "mean"),
    "p95_latency_ms": ("latency_ms", lambda x: x.quantile(0.95)),
    "mean_payload_bytes": ("payload_bytes", "mean"),
    "mean_header_bytes": ("header_bytes", "mean"),
}
if has_cin:
    # cin_create_ms only has values for Scenario 2 command rows — mean over those.
    summary_agg["mean_cin_create_ms"] = ("cin_create_ms", "mean")

summary = (
    df.groupby(["run_id", "protocol", "scenario"])
    .agg(**summary_agg)
    .reset_index()
)
summary["loss_pct"] = (1 - summary["n_delivered"] / summary["n_total"].clip(lower=1)) * 100
summary["overhead_pct"] = (
    summary["mean_header_bytes"]
    / (summary["mean_header_bytes"] + summary["mean_payload_bytes"]).clip(lower=1)
    * 100
)

col_cfg = {
    "mean_latency_ms": st.column_config.NumberColumn("Mean latency (ms)", format="%.1f"),
    "p95_latency_ms": st.column_config.NumberColumn("p95 latency (ms)", format="%.1f"),
    "loss_pct": st.column_config.NumberColumn("Loss (%)", format="%.1f"),
    "overhead_pct": st.column_config.NumberColumn("Overhead (%)", format="%.1f"),
}
if has_cin:
    col_cfg["mean_cin_create_ms"] = st.column_config.NumberColumn(
        "Mean CIN create (ms)", format="%.1f"
    )

st.dataframe(summary.round(2), use_container_width=True, column_config=col_cfg)

# ------------------------------------------------------------------
# Quick plots
# ------------------------------------------------------------------

if df["latency_ms"].notna().any():
    lat_df = df[df["latency_ms"].notna()]

    tab_violin, tab_box, tab_cdf = st.tabs(["Violin", "Box", "CDF"])

    with tab_violin:
        st.subheader("Latency Distribution — Violin")
        fig_v = px.violin(
            lat_df,
            x="protocol",
            y="latency_ms",
            color="protocol",
            facet_col="scenario",
            box=True,
            points="outliers",
            labels={"latency_ms": "Latency (ms)", "protocol": "Protocol"},
        )
        fig_v.update_layout(showlegend=False)
        st.plotly_chart(fig_v, use_container_width=True)

    with tab_box:
        st.subheader("Latency Distribution — Box")
        fig_b = px.box(
            lat_df,
            x="protocol",
            y="latency_ms",
            color="protocol",
            facet_col="scenario",
            labels={"latency_ms": "Latency (ms)", "protocol": "Protocol"},
            points="outliers",
        )
        fig_b.update_layout(showlegend=False)
        st.plotly_chart(fig_b, use_container_width=True)

    with tab_cdf:
        st.subheader("Latency CDF")
        fig_cdf = px.ecdf(
            lat_df,
            x="latency_ms",
            color="protocol",
            facet_col="scenario",
            labels={"latency_ms": "Latency (ms)"},
        )
        st.plotly_chart(fig_cdf, use_container_width=True)

# Protocol overhead bar chart
if "header_bytes" in df.columns and df["header_bytes"].notna().any():
    st.subheader("Protocol Overhead")
    st.caption("Mean header bytes as a percentage of total bytes (header + payload) per protocol.")
    overhead_df = (
        df.groupby("protocol")
        .apply(lambda g: pd.Series({
            "overhead_pct": (
                g["header_bytes"].mean()
                / max(g["header_bytes"].mean() + g["payload_bytes"].mean(), 1e-9)
                * 100
            ),
            "mean_header_bytes": g["header_bytes"].mean(),
            "mean_payload_bytes": g["payload_bytes"].mean(),
        }))
        .reset_index()
    )
    fig_oh = px.bar(
        overhead_df.sort_values("overhead_pct", ascending=True),
        x="overhead_pct",
        y="protocol",
        orientation="h",
        color="protocol",
        labels={"overhead_pct": "Overhead (%)", "protocol": "Protocol"},
        text="overhead_pct",
    )
    fig_oh.update_traces(texttemplate="%{text:.1f}%", textposition="outside")
    fig_oh.update_layout(showlegend=False, xaxis_range=[0, 100])
    st.plotly_chart(fig_oh, use_container_width=True)

# CIN creation round-trip — Scenario 2 only (Streamlit → CSE overhead).
if has_cin and df["cin_create_ms"].notna().any():
    st.subheader("CIN Creation RTT (Streamlit → CSE)")
    st.caption(
        "Time from `send_command()` to CSE response. Single-machine monotonic clock — "
        "NTP-independent. Measures protocol framing + network hop to CSE."
    )
    fig3 = px.box(
        df[df["cin_create_ms"].notna()],
        x="protocol",
        y="cin_create_ms",
        color="protocol",
        labels={"cin_create_ms": "CIN create RTT (ms)", "protocol": "Protocol"},
        points="outliers",
    )
    fig3.update_layout(showlegend=False)
    st.plotly_chart(fig3, use_container_width=True)

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
