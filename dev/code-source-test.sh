#!/usr/bin/env bash
# Two code-source instances for the plugin backend's tests: one serving the
# fixture repository (port 8080), and one whose repository doesn't exist,
# so it never becomes ready (port 8081).
set -euo pipefail
cd /code-source

./gradlew --console=plain -q installDist
bin=build/install/code-source/bin/code-source

REPO_URL=file:///srv/git/examples.git PORT=8080 "$bin" &
REPO_URL=file:///srv/git/does-not-exist.git PORT=8081 REFRESH_INTERVAL_SECONDS=3600 "$bin" &
wait -n
