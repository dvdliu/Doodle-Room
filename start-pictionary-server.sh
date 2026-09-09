#!/usr/bin/env bash
# start-pictionary-server.sh — build (if needed) and run the Pictionary game server.
#
# This is a separate process/port from the free-draw whiteboard server
# (start-server.sh / WhiteboardServer, port 8080). Both live in the same
# Maven module and jar — this script just launches the other main class.
#
# Usage:
#   ./start-pictionary-server.sh              # runs on default port 8081
#   ./start-pictionary-server.sh 9091         # runs on port 9091
#   SKIP_BUILD=1 ./start-pictionary-server.sh # skip mvn package, just run the existing jar

set -euo pipefail

# Always run from the directory this script lives in
cd "$(dirname "$0")"

PORT="${1:-8081}"

# ── 1. Prereq check ──────────────────────────────────────────────
command -v java >/dev/null 2>&1 || { echo "ERROR: 'java' not found in PATH. Install JDK 17+."; exit 1; }
command -v mvn  >/dev/null 2>&1 || { echo "ERROR: 'mvn' not found in PATH. Install Maven 3.6+."; exit 1; }

# ── 2. Build ─────────────────────────────────────────────────────
JAR="target/collaborative-whiteboard-1.0-SNAPSHOT.jar"

if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$JAR" ]; then
    echo "Building with Maven..."
    mvn -q clean package
fi

if [ ! -f "$JAR" ]; then
    echo "ERROR: build did not produce $JAR"
    exit 1
fi

# ── 3. Run ───────────────────────────────────────────────────────
echo
echo "Starting Pictionary server on ws://localhost:${PORT}"
echo "Open pictionary.html in two or more browser tabs to play."
echo "Press Ctrl+C to stop."
echo

exec java -cp "$JAR" com.pictionary.PictionaryServer "$PORT"
