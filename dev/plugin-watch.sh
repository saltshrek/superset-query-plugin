#!/usr/bin/env bash
# Rebuilds plugin/dist whenever plugin source changes.
#
# This stands in for `superset-extensions dev`, which rebuilds on *any*
# watchdog event. On Linux that includes the file-open events webpack
# causes by reading the source, so it rebuilds in an endless loop. Here we
# wait only for writes, creates, deletes and moves, then run the CLI's
# ordinary build. Writes made on a macOS host reach the container as
# MODIFY events only (no CLOSE_WRITE), so both count as writes.
#
# After a successful build that changed the backend, it touches a stamp
# that Superset watches, so Superset restarts and imports the new backend
# (see superset-start.sh). Frontend-only changes don't restart Superset.
set -u
cd /plugin

watched=(extension.json backend/src backend/pyproject.toml frontend/src frontend/package.json frontend/package-lock.json frontend/webpack.config.js frontend/tsconfig.json)
marker=/tmp/last-build
backend_built_stamp=/plugin-build/backend-built

build() {
  local backend_changed=yes
  if [ -f "$marker" ] && [ -z "$(find backend/src backend/pyproject.toml -newer "$marker" -print -quit)" ]; then
    backend_changed=no
  fi
  if [ -f "$marker" ] && [ -n "$(find frontend/package.json frontend/package-lock.json -newer "$marker" -print -quit)" ]; then
    npm --prefix frontend install
  fi
  touch "$marker"
  if superset-extensions build; then
    if [ "$backend_changed" = yes ]; then
      touch "$backend_built_stamp"
    fi
  else
    echo "❌ Build failed; waiting for the next change"
  fi
}

# The CLI only installs when node_modules is missing, and here it's always
# present (it's a volume), so install explicitly.
npm --prefix frontend install
# Superset watches the stamp only if it exists when Superset starts, and
# Superset waits for this service to be healthy (after the first build).
# Don't touch an existing one: a running Superset would restart mid-build.
[ -e "$backend_built_stamp" ] || touch "$backend_built_stamp"
build
while true; do
  # Skip waiting if something changed while the last build ran.
  if [ -z "$(find "${watched[@]}" -newer "$marker" -print -quit)" ]; then
    inotifywait -qq -r -e modify,close_write,create,delete,move "${watched[@]}"
  fi
  echo "🔁 Change detected, rebuilding"
  build
done
