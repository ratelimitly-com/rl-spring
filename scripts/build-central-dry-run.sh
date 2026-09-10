#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source_root="$PWD"

version="$(mvn -B -ntp -q org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate \
  -DforceStdout -Dexpression=project.version | tail -n 1 | tr -d '[:space:]')"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]
scratch="$(mktemp -d)"
mock_pid=""
cleanup() {
  if [ -n "$mock_pid" ]; then
    kill "$mock_pid" 2>/dev/null || true
    wait "$mock_pid" 2>/dev/null || true
  fi
  gpgconf --homedir "$scratch/gnupg" --kill all >/dev/null 2>&1 || true
  rm -rf "$scratch"
}
trap cleanup EXIT
export GNUPGHOME="$scratch/gnupg"
mkdir -m 700 "$GNUPGHOME"
# No inherited real signing or Portal credentials can be used by this dry run.
unset MAVEN_GPG_PASSPHRASE MAVEN_GPG_KEY MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_TOKEN
gpg --batch --pinentry-mode loopback --passphrase '' --quick-generate-key \
  'rl-spring CI dry run <ci@invalid>' rsa2048 sign 1d
epoch="${SOURCE_DATE_EPOCH:-2026-01-01T00:00:00Z}"

# The plugin's SNAPSHOT path produces no release bundle. Use a disposable copy
# of tracked working-tree files and a synthetic non-SNAPSHOT version. The source
# checkout and its declared version remain untouched. Add new files to Git first.
mkdir "$scratch/project"
git ls-files -z | tar --null -T - -cf - | tar -xf - -C "$scratch/project"
cd "$scratch/project"
version="${version%-SNAPSHOT}-dry-run"
mvn -B -ntp -s scripts/central-dry-run-settings.xml \
  org.codehaus.mojo:versions-maven-plugin:2.21.0:set \
  -DnewVersion="$version" -DgenerateBackupPoms=false -DprocessAllModules=true

# 0.11.0 skips artifact staging entirely when skipPublishing=true, despite its
# documentation describing bundle-only mode. Exercise the real plugin against
# an ephemeral loopback-only upload/validation fixture instead. No Portal access.
python3 scripts/mock-central.py "$scratch/port" &
mock_pid=$!
for attempt in {1..100}; do
  [ -s "$scratch/port" ] && break
  kill -0 "$mock_pid"
  sleep 0.05
done
port="$(< "$scratch/port")"
[[ "$port" =~ ^[0-9]+$ ]]
publisher="http://127.0.0.1:$port"

# The Central extension creates the bundle and sends it only to our fixture.
mvn -B -ntp -s scripts/central-dry-run-settings.xml -Pcentral-release -Dcentral.skipPublishing=false \
  -Dcentral.baseUrl="$publisher" \
  -Dproject.build.outputTimestamp="$epoch" clean deploy
bundle="target/central-publishing/central-bundle.zip"
python3 scripts/verify_central_bundle.py "$bundle" "$version"
cp "$bundle" "$scratch/first.zip"

# Verification above ran all tests. A second unsigned build checks reproducibility;
# signatures deliberately differ and are not part of byte-for-byte comparison.
mvn -B -ntp -s scripts/central-dry-run-settings.xml -Pcentral-release -Dcentral.skipPublishing=false -DskipTests \
  -Dcentral.baseUrl="$publisher" \
  -Dproject.build.outputTimestamp="$epoch" clean deploy
python3 scripts/verify_central_bundle.py "$bundle" "$version"
python3 scripts/compare-central-bundles.py "$scratch/first.zip" "$bundle"
mkdir -p "$source_root/target"
cp "$bundle" "$source_root/target/central-dry-run-bundle.zip"
