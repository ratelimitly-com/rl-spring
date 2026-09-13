#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source_root="$PWD"
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
unset MAVEN_GPG_PASSPHRASE MAVEN_GPG_KEY GITLAB_MAVEN_USERNAME GITLAB_MAVEN_TOKEN
gpg --batch --pinentry-mode loopback --passphrase '' --quick-generate-key \
  'rl-spring CI dry run <ci@invalid>' rsa2048 sign 1d
epoch="${SOURCE_DATE_EPOCH:-2026-01-01T00:00:00Z}"
mkdir "$scratch/project"
git ls-files -z | tar --null -T - -cf - | tar -xf - -C "$scratch/project"
cd "$scratch/project"
version="$(mvn -B -ntp -q org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate \
  -DforceStdout -Dexpression=project.version | tail -n 1 | tr -d '[:space:]')"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
# Empty settings prevent accidental use of inherited real publisher credentials.
printf '<settings/>\n' > "$scratch/settings.xml"
python3 scripts/mock-maven.py "$scratch/repository" "$scratch/port" &
mock_pid=$!
for attempt in {1..100}; do
  [ -s "$scratch/port" ] && break
  kill -0 "$mock_pid"
  sleep 0.05
done
port="$(< "$scratch/port")"
[[ "$port" =~ ^[0-9]+$ ]]
for build in first second; do
  reviewed=()
  if [ "$build" = second ]; then
    reviewed=("-Drelease.expectedArtifacts=$scratch/reviewed")
  fi
  mvn -B -ntp -s "$scratch/settings.xml" -Pmaven-release \
    "-Dproject.build.outputTimestamp=$epoch" \
    "${reviewed[@]}" \
    "-DaltDeploymentRepository=gitlab-maven::http://127.0.0.1:$port/$build" clean deploy
  python3 scripts/create_maven_bundle.py "$scratch/repository/$build" "$version" "$scratch/$build.zip"
  if [ "$build" = first ]; then
    mkdir "$scratch/reviewed"
    find "$scratch/repository/first" -type f \( -name '*.jar' -o -name '*.pom' \) \
      -exec cp {} "$scratch/reviewed/" \;
  fi
done
python3 scripts/compare-maven-bundles.py "$scratch/first.zip" "$scratch/second.zip"
# Exercise the real Maven binding, not only the comparison helper. A mismatch
# in the last publishable module must prevent even the parent's earlier upload.
printf '\nchanged reviewed POM\n' >> "$scratch/reviewed/ratelimitly-spring-boot-starter-$version.pom"
if mvn -B -ntp -s "$scratch/settings.xml" -Pmaven-release -DskipTests \
    "-Dproject.build.outputTimestamp=$epoch" \
    "-Drelease.expectedArtifacts=$scratch/reviewed" \
    "-DaltDeploymentRepository=gitlab-maven::http://127.0.0.1:$port/rejected" \
    clean deploy > "$scratch/rejected.log" 2>&1; then
  echo 'ERROR: Maven accepted artifacts differing from the reviewed build' >&2
  exit 1
fi
if ! grep -q 'Reviewed release artifact mismatch: ratelimitly-spring-boot-starter-' "$scratch/rejected.log" \
    || [ -e "$scratch/repository/rejected" ]; then
  echo 'ERROR: mismatch was not refused before all uploads' >&2
  tail -n 80 "$scratch/rejected.log" >&2
  exit 1
fi
echo 'Maven rejected a late-module mismatch before uploading any module.'
mkdir -p "$source_root/target"
cp "$scratch/first.zip" "$source_root/target/maven-dry-run-bundle.zip"
