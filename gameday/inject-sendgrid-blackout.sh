#!/usr/bin/env bash
set -euo pipefail

TOXIPROXY_URL="${TOXIPROXY_URL:-http://localhost:8474}"
OMNIRELAY_URL="${OMNIRELAY_URL:-http://localhost:8080}"
MOCK_ADMIN_URL="${MOCK_ADMIN_URL:-http://localhost:8090}"
BLACKOUT_DURATION_SEC="${BLACKOUT_DURATION_SEC:-15}"

echo "[gameday] Baseline failover status before blackout:"
curl -sS "${OMNIRELAY_URL}/api/v1/failover/status" | jq '{activeProviderByRegion, droppedMessages, totalDelivered}'

START_MS=$(python3 -c 'import time; print(int(time.time()*1000))')
echo "[gameday] Injecting SendGrid blackout via Toxiproxy (${TOXIPROXY_URL})..."

# Inject 100% latency/timeout blackout toxic on sendgrid_upstream proxy if Toxiproxy is up;
# also flip mock upstream /admin/blackout/sendgrid/on as deterministic fallback.
curl -sS -X POST "${TOXIPROXY_URL}/proxies/sendgrid_upstream/toxics" \
  -H 'Content-Type: application/json' \
  -d '{"name":"sendgrid_blackout_timeout","type":"timeout","stream":"downstream","toxicity":1.0,"attributes":{"timeout":2500}}' >/dev/null 2>&1 || true

curl -sS -X POST "${MOCK_ADMIN_URL}/admin/blackout/sendgrid/on" >/dev/null 2>&1 || true

RECOVERED=0
RECOVERY_MS=0
for i in $(seq 1 60); do
  STATUS_JSON=$(curl -sS "${OMNIRELAY_URL}/api/v1/failover/status")
  EU_PROVIDER=$(echo "${STATUS_JSON}" | jq -r '.activeProviderByRegion.EU')
  SG_CB_STATE=$(echo "${STATUS_JSON}" | jq -r '.circuitBreakers.SENDGRID.state')
  DROPPED=$(echo "${STATUS_JSON}" | jq -r '.droppedMessages')

  if [ "${EU_PROVIDER}" = "AWS_SES_V2" ] && [ "${SG_CB_STATE}" = "OPEN" ]; then
    NOW_MS=$(python3 -c 'import time; print(int(time.time()*1000))')
    RECOVERY_MS=$((NOW_MS - START_MS))
    echo "[gameday] PASS: SendGrid CB=${SG_CB_STATE}, EU active=${EU_PROVIDER}, MTTR=${RECOVERY_MS}ms (<30000ms), droppedMessages=${DROPPED}"
    RECOVERED=1
    break
  fi
  sleep 0.5
done

if [ "${RECOVERED}" -ne 1 ]; then
  echo "[gameday] ERROR: Failover did not complete within 30s!" >&2
  exit 1
fi

if [ "${RECOVERY_MS}" -ge 30000 ]; then
  echo "[gameday] ERROR: MTTR ${RECOVERY_MS}ms exceeded 30000ms SLA!" >&2
  exit 1
fi

echo "[gameday] Sustaining AWS SESv2 failover traffic for ${BLACKOUT_DURATION_SEC}s..."
sleep "${BLACKOUT_DURATION_SEC}"

echo "[gameday] Removing SendGrid blackout toxic..."
curl -sS -X DELETE "${TOXIPROXY_URL}/proxies/sendgrid_upstream/toxics/sendgrid_blackout_timeout" >/dev/null 2>&1 || true
curl -sS -X POST "${MOCK_ADMIN_URL}/admin/blackout/sendgrid/off" >/dev/null 2>&1 || true

echo "[gameday] Final summary:"
curl -sS "${OMNIRELAY_URL}/api/v1/metrics/summary" | jq '.'
