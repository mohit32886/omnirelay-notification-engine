#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_DIR}"

ARTIFACTS_DIR="${SCRIPT_DIR}/artifacts"
mkdir -p "${ARTIFACTS_DIR}"

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 && command -v k6 >/dev/null 2>&1; then
  echo "[gameday] Running full Docker Compose + Toxiproxy + k6 1,000 req/sec Gameday..."
  docker compose up -d --build
  for i in $(seq 1 40); do
    if curl -sf http://localhost:8080/actuator/health >/dev/null; then
      break
    fi
    sleep 2
  done

  k6 run "${SCRIPT_DIR}/k6-chaos-loadtest.js" &
  K6_PID=$!
  sleep 10
  bash "${SCRIPT_DIR}/inject-sendgrid-blackout.sh"
  wait "${K6_PID}"

  echo "[gameday] Collecting Grafana/Prometheus summary proof..."
  python3 "${SCRIPT_DIR}/capture-grafana-proof.py" --duration 16 --rps 1000 --out-dir "${ARTIFACTS_DIR}"
else
  echo "[gameday] Running local standalone Chaos Gameday (Spring Boot + Mock Upstreams + 1,000 req/sec Load)..."
  mvn -q -DskipTests package

  python3 "${SCRIPT_DIR}/mock_upstreams.py" --port 18081 >/tmp/omnirelay-sg.log 2>&1 &
  SG_PID=$!
  python3 "${SCRIPT_DIR}/mock_upstreams.py" --port 18082 >/tmp/omnirelay-ses.log 2>&1 &
  SES_PID=$!

  java -XX:+UseZGC -XX:+ZGenerational \
    -jar target/omnirelay-notification-engine-1.0.0.jar \
    --server.port=8080 \
    --omnirelay.failover.sliding-window-size=20 \
    --omnirelay.failover.minimum-number-of-calls=5 \
    >/tmp/omnirelay-app.log 2>&1 &
  APP_PID=$!

  cleanup() {
    kill "${APP_PID}" "${SG_PID}" "${SES_PID}" 2>/dev/null || true
  }
  trap cleanup EXIT

  for i in $(seq 1 30); do
    if curl -sf http://localhost:8080/actuator/health >/dev/null; then
      echo "[gameday] OmniRelay engine UP on :8080"
      break
    fi
    sleep 1
  done

  python3 "${SCRIPT_DIR}/capture-grafana-proof.py" \
    --base-url http://localhost:8080 \
    --mock-admin-url http://localhost:18081 \
    --duration 18 \
    --rps 1000 \
    --out-dir "${ARTIFACTS_DIR}"
fi

echo "[gameday] Proof artifacts written to ${ARTIFACTS_DIR}:"
ls -lh "${ARTIFACTS_DIR}"
