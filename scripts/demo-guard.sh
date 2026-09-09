#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CUSTOMER_ID="${CUSTOMER_ID:-cust-123}"
REGION="${REGION:-us-east}"
REPEAT="${REPEAT:-1}"
SLEEP_BETWEEN_SECONDS="${SLEEP_BETWEEN_SECONDS:-0}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-5}"

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl is required but not installed." >&2
  exit 1
fi

for value_name in REPEAT SLEEP_BETWEEN_SECONDS TIMEOUT_SECONDS; do
  value="${!value_name}"
  if ! [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "ERROR: ${value_name} must be a non-negative integer." >&2
    exit 1
  fi
done

if (( REPEAT <= 0 )); then
  echo "ERROR: REPEAT must be > 0." >&2
  exit 1
fi

TARGET="${BASE_URL%/}/api/demo/slow/${CUSTOMER_ID}?region=${REGION}"

echo "Checking server readiness at ${BASE_URL%/}/api/demo/health ..."
health_code="$(curl -sS -o /dev/null -m "$TIMEOUT_SECONDS" -w "%{http_code}" "${BASE_URL%/}/api/demo/health" || true)"
if [[ "$health_code" != "200" ]]; then
  echo "ERROR: server is not ready (health status: $health_code)." >&2
  exit 1
fi

echo
echo "Guard demo target: $TARGET"
echo "repeat=$REPEAT sleep_between_seconds=$SLEEP_BETWEEN_SECONDS timeout_seconds=$TIMEOUT_SECONDS"
echo

for i in $(seq 1 "$REPEAT"); do
  echo "Request #$i"
  response_file="$(mktemp)"
  http_code="$(curl -sS -m "$TIMEOUT_SECONDS" -o "$response_file" -w "%{http_code}" "$TARGET" || true)"
  timing="$(curl -sS -o /dev/null -m "$TIMEOUT_SECONDS" -w "total=%{time_total}s starttransfer=%{time_starttransfer}s" "$TARGET" || true)"

  echo "  http_code=$http_code $timing"
  echo "  payload:"
  sed 's/^/    /' "$response_file"
  rm -f "$response_file"

  if (( i < REPEAT )) && (( SLEEP_BETWEEN_SECONDS > 0 )); then
    sleep "$SLEEP_BETWEEN_SECONDS"
  fi
  echo
done

echo "Expected on allowed calls: simulatedLatencyMs around 300 and guardThresholdMs=250."
echo "Tip: set REPEAT>1 with SLEEP_BETWEEN_SECONDS=0 to observe potential deny responses in the same rate-limit window."
