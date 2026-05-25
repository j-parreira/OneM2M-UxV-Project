# Dockerfile — ACME oneM2M CSE (v2025.11)
#
# Builds a headless ACME CSE container for the UxV benchmark.
# Includes iproute2 for tc netem (Scenario 3 — degraded network).
#
# Usage (via docker-compose):
#   docker compose up --build
#
# Manual smoke test after startup:
#   curl http://localhost:8080/id-in -H "X-M2M-Origin: CAdmin" -H "X-M2M-RI: t" -H "X-M2M-RVI: 3"

FROM python:3.11-slim

# iproute2  — tc netem for Scenario 3 (packet loss emulation)
# No curl needed; healthcheck uses Python's urllib
RUN apt-get update \
 && apt-get install -y --no-install-recommends iproute2 \
 && rm -rf /var/lib/apt/lists/*

# Pin ACME CSE version for reproducibility (research paper requirement)
RUN pip3 install --no-cache-dir acmecse==2025.11

# Working data directory — acme.ini mounted here by docker-compose
RUN mkdir -p /data/db

# Restart-loop script: exit code 82 means "CSE is restarting", keep container alive
COPY runACME.sh /runACME.sh
RUN chmod +x /runACME.sh

# HTTP REST  — primary OneM2M binding
EXPOSE 8080/tcp
# WebSocket  — bidirectional binding (Android app connects here)
EXPOSE 8180/tcp
# CoAP       — UDP; must be declared explicitly or Docker only exposes TCP
EXPOSE 5683/udp

# Healthcheck: GET /id-in with mandatory OneM2M headers, via Python (no extra packages needed)
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 CMD python3 -c "import urllib.request; urllib.request.urlopen(urllib.request.Request('http://localhost:8080/id-in',headers={'X-M2M-RI':'hc','X-M2M-Origin':'CAdmin','X-M2M-RVI':'3'}),timeout=4)"

CMD ["/bin/sh", "/runACME.sh"]
