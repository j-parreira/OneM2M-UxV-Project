#!/bin/sh
# runACME.sh — Restart loop for ACME CSE inside the Docker container.
#
# ACME CSE exits with code 82 when it needs to restart (e.g. after a
# configuration reload). This loop keeps the container alive in that case.
# Any other exit code (error or clean shutdown) breaks the loop.

while true; do
    acmecse -dir /data --headless

    if [ $? -ne 82 ]; then
        break
    fi

    echo "ACME CSE restarting..."
done
