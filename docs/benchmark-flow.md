# Benchmark Master Flow

> Complete workflow for one benchmark experiment session.
> This document drives the methodology section of the paper.
>
> Status: Android + CSE verified. Streamlit and analysis steps are placeholders
> for the implementation that follows.

---

## 1. Pre-session Setup

### 1.1 Start the CSE stack

```bash
cd src/cse
docker compose up --build   # first time
# or
docker compose up           # subsequent runs (no rebuild needed)
```

Wait until both containers are healthy (~45 s):

```bash
docker compose ps
# Expected: acme-cse (healthy), mosquitto (healthy)
```

Smoke test:
```bash
curl http://localhost:8080/id-in \
  -H "X-M2M-RI: smoke" -H "X-M2M-Origin: CAdmin" -H "X-M2M-RVI: 3"
# Expected: HTTP 200, body contains "ty":5
```

### 1.2 Start the Streamlit dashboard

```bash
cd src/frontend
source .venv/bin/activate   # Windows: .venv\Scripts\activate
streamlit run app.py
# Opens at http://localhost:8501
```

### 1.3 Launch the Android app

1. Power on DJI M2EA drone
2. Connect DJI RC to the drone (OcuSync link established)
3. Open **UxV Benchmark** app on the RC
4. Tap **Registar App** (first run only — registers DJI SDK)
5. Once drone is connected, tap **Open Benchmark**
6. Enter the dev machine's LAN IP in the "CSE Host" field
7. Tap **Connect CSE** (or it connects automatically)

### 1.4 Verify OneM2M session

The Android app status field progresses through:
```
Connecting to ws://192.168.1.X:8180...
Registering AE (C<serial>)...
Creating telemetry container...
Creating commands container...
Creating subscription...
Creating ack container...
OneM2M ready — C<serial>          ← green: session active
TX: seq=12                        ← telemetry flowing
Bat: 85% | Flying | 18 sats
```

Verify in the CSE web UI (`http://localhost:8080/webui`):
```
/cse-in/<ae-name>/telemetry     ← CINs arriving every 250ms
/cse-in/<ae-name>/commands      ← empty
/cse-in/<ae-name>/commands/sub-commands  ← subscription active
/cse-in/<ae-name>/ack           ← empty
```

---

## 2. Pre-benchmark Validation

Before starting a run, validate the command loop:

1. In Streamlit, select protocol = **WebSocket**, send command `identify state=true`
2. Drone LEDs should flash
3. Verify ACK CIN appears in `/cse-in/<ae-name>/ack` with `t_recv_ms - t_cmd_ms < 200ms`

If ACK is not received:
- Check Android status field (should be green)
- Check CSE web UI for new CINs in `/cse-in/<ae-name>/commands`
- See troubleshooting guide in `src/cse/README.md`

---

## 3. Scenario 1 — Telemetry Stream

**For each: protocol ∈ {WebSocket, MQTT, HTTP, CoAP} × rate ∈ {4, 16} msg/s**

### 3.1 Select protocol in Streamlit

Protocol selector → choose protocol under test → CSE path and client configured automatically.

### 3.2 Set telemetry rate

Streamlit sends via the current protocol:
```json
{"command": "setTelemetryRate", "intervalMs": 250}
```
Android status field confirms: `"Telemetry rate: 4 msg/s (250ms)"`

### 3.3 Configure Streamlit benchmark run

```
Protocol:  WebSocket
Scenario:  1
Rate:      4 msg/s  (or 16 msg/s for 4-drone load simulation)
Duration:  120 s
Run ID:    websocket_s1_r4_20260602_run001
```

### 3.4 Run and collect

1. Click **Run** in Streamlit → starts logging
2. Streamlit subscribes to `/cse-in/uxv/telemetry` and records for each CIN:
   - `timestamp_ms` (reception time)
   - `seq` (from CIN.con)
   - `t_send_ms` (from CIN.con)
   - `latency_ms = timestamp_ms - t_send_ms` (NTP-dependent)
   - `payload_bytes`, `header_bytes`
3. After 120 s, Streamlit saves:
   - `data/raw/websocket_s1_r4_20260602_run001.csv`
   - `data/raw/websocket_s1_r4_20260602_run001.json` (metadata sidecar)

### 3.5 Metrics computed per run

| Metric | Computation |
|---|---|
| Latency | `mean(latency_ms)` for delivered messages |
| Throughput | `n_delivered / duration_s` |
| Packet loss | `gaps(seq) / n_sent × 100` |
| Overhead | `mean(header_bytes / (header_bytes + payload_bytes)) × 100` |
| Jitter | `std(latency_ms)` |

**Repeat 10 times** per (protocol, rate) combination. Reset telemetry between runs.

---

## 4. Scenario 2 — Command Burst

**For each: protocol ∈ {WebSocket, MQTT, HTTP, CoAP}**

### 4.1 Configure

```
Protocol:              WebSocket
Scenario:              2
Commands:              60
Inter-command delay:   1000 ms  (after ACK, ~1 cmd/s)
Run timeout:           600 s
```

### 4.2 Run

Streamlit sends 60 commands at ~1 cmd/s in a repeating 4-command cycle:
```json
{"command": "takeoff",  "seq_cmd": 1, "t_cmd_ms": 1748000000000}
{"command": "identify", "state": true,  "seq_cmd": 2, "t_cmd_ms": 1748000001200}
{"command": "land",     "seq_cmd": 3, "t_cmd_ms": 1748000002400}
{"command": "identify", "state": false, "seq_cmd": 4, "t_cmd_ms": 1748000003600}
...  (cycle repeats 15×)
```

For each command, Streamlit waits for ACK CIN at `/cse-in/uxv/ack`:
```json
{"command": "takeoff", "seq_cmd": 1, "t_cmd_ms": ..., "t_recv_ms": ..., "t_exec_ms": ...}
```

Latency = `t_recv_ms - t_cmd_ms` (NTP-dependent: Streamlit and Android clocks)  
`cin_create_ms` = Streamlit→CSE round-trip (monotonic, no NTP dependency)

### 4.3 Data saved

`data/raw/websocket_s2_20260602_run001.csv` with one row per command.

**Repeat 10 times** per protocol.

---

## 5. Scenario 3 — Degraded Network

> **Planned after June 6 deadline.** Not required for the course paper.

**For each: protocol × loss_level ∈ {2%, 5%, 10%}**

### 5.1 Inject packet loss

```bash
docker exec acme-cse tc qdisc add dev eth0 root netem loss 5%
docker exec acme-cse tc qdisc show dev eth0   # verify
```

### 5.2 Run Scenarios 1 and 2 with netem active

Same procedure as above. The loss affects the CSE-to-app link (egress from container).

### 5.3 Reset after each run

```bash
docker exec acme-cse tc qdisc del dev eth0 root
```

**Repeat 10 times** per (protocol, loss_level) combination.

---

## 6. Analysis Pipeline

After all benchmark runs, process the data:

```bash
cd src/analysis
source .venv/bin/activate

# Step 1: Validate and load all CSVs
python scripts/01_load_validate.py
# → Reports schema errors, anomalies, missing runs
# → Output: data/processed/all_runs.parquet

# Step 2: Compute aggregate statistics
python scripts/02_compute_stats.py
# → mean, median, p5/p25/p75/p95/p99, 95% CI, n_samples per (protocol, scenario, direction)
# → Output: data/processed/stats_<scenario>_<direction>.parquet
#           data/processed/summary.csv

# Step 3: Statistical tests
python scripts/03_statistical_tests.py
# → Kruskal-Wallis H test per metric (p < 0.05 = significant difference)
# → Dunn's post-hoc pairwise with Bonferroni correction
# → Cliff's delta effect size per pair
# → Output: data/processed/statistical_tests.json

# Step 4: Publication figures
jupyter lab notebooks/04_paper_figures.ipynb
# → Latency box plot (4 protocols)
# → Latency CDF (4 protocols at 4 msg/s and 16 msg/s)
# → Protocol overhead bar chart
# → Throughput vs rate line chart
# → Packet loss bar chart
# → Output: src/analysis/figures/*.pdf (IEEE column width: 3.5in)
```

---

## 7. Troubleshooting

### Android not connecting

| Symptom | Check |
|---|---|
| Status stuck "Connecting..." | CSE IP correct? CSE container healthy? Same WiFi? |
| Status "Error: OneM2M error rsc=4103" | CSE restarted without `enableACPChecks=false`? |
| Status "OneM2M ready" but no telemetry | CSE web UI: `/cse-in/uxv/telemetry` CINs arriving? |
| Commands sent but no ACK | Check `/cse-in/uxv/ack` in web UI; verify subscription active |

### CSE issues

```bash
docker compose logs -f acme-cse   # follow logs
docker compose ps                  # check health status

# Clean restart (clears TinyDB):
docker compose down -v && docker compose up
```

### Notification not delivered to Android

Most common cause: `poa` missing from AE registration (silently discarded by CSE).
The Android app (`OneM2MSession.registerAE()`) includes `poa=['ws://host:8180']`.
If notifications stopped working after a code change, check the `registerAE()` body.

---

## 8. Data Quality Checklist

Before including a run in the paper analysis:

- [ ] ≥ 10 runs per (protocol, scenario, rate) combination
- [ ] No more than 5% of runs flagged as anomalous by `01_load_validate.py`
- [ ] Battery > 30% throughout each run (check drone battery logs)
- [ ] No CSE restarts during run (check Docker logs)
- [ ] NTP sync confirmed before Scenario 1 runs (for latency measurements)
- [ ] Netem reset confirmed after each Scenario 3 run

---

## 9. Quick Reference — OneM2M Resource Paths

| Purpose | Path | Who writes | Who reads |
|---|---|---|---|
| Telemetry stream | `/cse-in/uxv/telemetry` | Android | Streamlit (via SUB) |
| Commands | `/cse-in/uxv/commands` | Streamlit | Android (via SUB) |
| Command ACKs | `/cse-in/uxv/ack` | Android | Streamlit (via SUB) |
| CSE-Base health check | `GET /id-in` | — | Both |
| Web UI | `http://host:8080/webui` | — | Debug |

---

## 10. Software Versions (paper reproducibility)

| Component | Version | Notes |
|---|---|---|
| ACME CSE | 2025.11 | `pip show acmecse` inside container |
| Android app | 4.0 | `versionCode 1` in `app/build.gradle` |
| DJI Mobile SDK | 4.16.4 | Fixed in `app/build.gradle` |
| Python | 3.11.x | `python --version` in containers |
| Eclipse Mosquitto | 2.x | `docker inspect mosquitto` |
| Streamlit | 1.45.1 | Pinned in `src/frontend/requirements.txt` |
