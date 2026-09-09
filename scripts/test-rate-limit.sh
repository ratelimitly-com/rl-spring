#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCENARIO="${SCENARIO:-both}"
ENDPOINT="${ENDPOINT:-}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-60}"
CONCURRENCY="${CONCURRENCY:-20}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-5}"
MAX_OTHER_RESPONSES="${MAX_OTHER_RESPONSES:-5}"

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl is required but not installed." >&2
  exit 1
fi

if ! [[ "$TOTAL_REQUESTS" =~ ^[0-9]+$ ]] || ! [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] || ! [[ "$TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || ! [[ "$MAX_OTHER_RESPONSES" =~ ^[0-9]+$ ]]; then
  echo "ERROR: TOTAL_REQUESTS, CONCURRENCY, TIMEOUT_SECONDS, and MAX_OTHER_RESPONSES must be integers." >&2
  exit 1
fi

if (( TOTAL_REQUESTS <= 0 || CONCURRENCY <= 0 || TIMEOUT_SECONDS <= 0 )); then
  echo "ERROR: TOTAL_REQUESTS, CONCURRENCY, and TIMEOUT_SECONDS must be > 0." >&2
  exit 1
fi

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "Checking server readiness at ${BASE_URL%/}/api/demo/health ..."
health_code="$(curl -sS -o /dev/null -m "$TIMEOUT_SECONDS" -w "%{http_code}" "${BASE_URL%/}/api/demo/health" || true)"
if [[ "$health_code" != "200" ]]; then
  echo "ERROR: server is not ready (health status: $health_code)." >&2
  exit 1
fi

run_burst() {
  local name="$1"
  local endpoint="$2"
  local status_file="$WORKDIR/${name}.statuses.txt"
  local target="${BASE_URL%/}${endpoint}"

  echo
  echo "Sending burst traffic to [$name] $target"
  echo "total_requests=$TOTAL_REQUESTS concurrency=$CONCURRENCY timeout_seconds=$TIMEOUT_SECONDS max_other_responses=$MAX_OTHER_RESPONSES"

  export TARGET="$target" TIMEOUT_SECONDS
  seq "$TOTAL_REQUESTS" | xargs -P"$CONCURRENCY" -I{} bash -c '
    code="$(curl -sS -o /dev/null -m "$TIMEOUT_SECONDS" -w "%{http_code}" "$TARGET" || echo 000)"
    echo "$code"
  ' >> "$status_file"

  local total allowed denied other
  total="$(wc -l < "$status_file" | tr -d ' ')"
  allowed="$(grep -c '^200$' "$status_file" || true)"
  denied="$(grep -c '^429$' "$status_file" || true)"
  other="$(grep -Ev '^(200|429)$' "$status_file" | wc -l | tr -d ' ')"

  echo "Results for $name"
  echo "  total:   $total"
  echo "  allowed: $allowed"
  echo "  denied:  $denied"
  echo "  other:   $other"

  if (( denied == 0 )); then
    echo "FAIL: no denied requests observed for $name."
    return 1
  fi

  if (( other > MAX_OTHER_RESPONSES )); then
    echo "FAIL: too many unexpected statuses for $name (other=$other > max=$MAX_OTHER_RESPONSES)."
    return 1
  fi

  echo "PASS: observed denied requests (HTTP 429) for $name with acceptable unexpected status count."
  return 0
}

overall_status=0

if [[ -n "$ENDPOINT" ]]; then
  run_burst "custom" "$ENDPOINT" || overall_status=1
else
  case "$SCENARIO" in
    users)
      run_burst "users" "/api/demo/users/alice" || overall_status=1
      ;;
    customers)
      run_burst "customers" "/api/demo/customers/cust-123?region=us-east&status=active" || overall_status=1
      ;;
    both)
      run_burst "users" "/api/demo/users/alice" || overall_status=1
      run_burst "customers" "/api/demo/customers/cust-123?region=us-east&status=active" || overall_status=1
      ;;
    *)
      echo "ERROR: SCENARIO must be one of users, customers, both." >&2
      exit 1
      ;;
  esac
fi

if (( overall_status == 0 )); then
  echo
  echo "PASS: observed denied requests for all selected scenarios."
  exit 0
fi

echo
echo "FAIL: at least one scenario did not produce denied requests."
echo "Hints:"
echo "  - Ensure ratelimitly is enabled in application.yml"
echo "  - Increase TOTAL_REQUESTS or CONCURRENCY"
echo "  - Verify the target endpoint is protected by servlet/method policy"
echo "  - Raise MAX_OTHER_RESPONSES if your local environment is noisy"
exit 2
