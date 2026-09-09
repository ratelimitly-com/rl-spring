#!/usr/bin/env bash
set -euo pipefail

root=$(git rev-parse --show-toplevel)
scanner=${GITLEAKS_BIN:-gitleaks}
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
mkdir "$fixture/input"

scan() {
  "$scanner" dir "$fixture/input" --config "$root/.gitleaks.toml" \
    --redact=100 --no-banner --no-color --ignore-gitleaks-allow \
    --gitleaks-ignore-path "$fixture/no-ignore-file" \
    --report-format json --report-path "$fixture/report.json" --exit-code 1
}

# Deliberately malformed synthetic strings, assembled to test recognition;
# never production credentials or authenticated traffic.
for prefix in rl-aes1 rl-cookie1 rl-secret1 RL-AES1 RL-COOKIE1 RL-SECRET1; do
  printf '%s%s\n' "$prefix" "$(printf 'q%.0s' {1..40})" > "$fixture/input/example.txt"
  rc=0
  scan > "$fixture/scanner.log" 2>&1 || rc=$?
  if [[ "$rc" != 1 ]] || ! grep -q '"RuleID": "ratelimitly-api-key"' "$fixture/report.json"; then
    echo 'ERROR: RateLimitly credential detector self-test failed' >&2
    exit 1
  fi
done

# Default rules must stay enabled as well as the application-specific rule.
printf '%s%s\n' 'ghp_' 'pYJkEs3RBmwB7lxCRCa6BX1DJbyPM4JNrsSf' > "$fixture/input/example.txt"
rc=0
scan > "$fixture/scanner.log" 2>&1 || rc=$?
if [[ "$rc" != 1 ]] || ! grep -q '"RuleID": "github-pat"' "$fixture/report.json"; then
  echo 'ERROR: default GitHub token detector self-test failed' >&2
  exit 1
fi

# This existing public NONE fixture is intentionally usable only as a test key.
printf 'API_KEY="%s"\n' 'rl-none1qyyqwps9qspsyq2sk8e0sfdp3ys' > "$fixture/input/example.txt"
scan > "$fixture/scanner.log" 2>&1
echo 'Secret scanner self-tests passed (authenticated keys blocked; NONE fixture allowed).'
