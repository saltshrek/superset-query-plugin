#!/usr/bin/env bash
# Restarts code-source whenever its source changes. If the new code doesn't
# compile, the previous server keeps running.
set -u
cd /code-source

watched=(src analyzers build.gradle.kts settings.gradle.kts)
marker=/tmp/last-build
server=

restart() {
  touch "$marker"
  if ! ./gradlew --console=plain classes; then
    echo "❌ Build failed; waiting for the next change"
    return
  fi
  if [ -n "$server" ]; then
    kill "$server"
    wait "$server"
  fi
  ./gradlew --console=plain -q installDist
  build/install/code-source/bin/code-source &
  server=$!
}

restart
while true; do
  # Skip waiting if something changed while the last build ran.
  if [ -z "$(find "${watched[@]}" -newer "$marker" -print -quit)" ]; then
    inotifywait -qq -r -e close_write,create,delete,move "${watched[@]}"
  fi
  echo "🔁 Change detected, restarting"
  restart
done
