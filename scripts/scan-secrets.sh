#!/usr/bin/env bash
set -euo pipefail

# Scan only the committed source snapshot. Private historical refs, untracked
# credentials, local build artifacts, and sibling repositories are out of scope.
root=$(git rev-parse --show-toplevel)
scanner=${GITLEAKS_BIN:-gitleaks}
snapshot=$(mktemp -d)
trap 'rm -rf "$snapshot"' EXIT
git -C "$root" archive HEAD | tar -xf - -C "$snapshot"
"$scanner" dir "$snapshot" --config "$root/.gitleaks.toml" \
  --redact=100 --no-banner --no-color --ignore-gitleaks-allow \
  --gitleaks-ignore-path "$snapshot/nonexistent-ignore-file" --exit-code 1
