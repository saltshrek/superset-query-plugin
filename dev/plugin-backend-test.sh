#!/usr/bin/env bash
# Runs the plugin backend's tests against a fresh Superset metadata DB.
# Arguments are passed to pytest.
set -euo pipefail

# The extension is loaded from plugin/dist, which plugin-dev builds. Wait
# until the built backend matches the source, so a stale build isn't tested.
plugin=/app/extensions/sql-examples
echo "Waiting for plugin-dev to build the current backend…"
for _ in $(seq 120); do
  stale=no
  while IFS= read -r src; do
    cmp -s "$plugin/backend/$src" "$plugin/dist/backend/$src" || { stale=yes; break; }
  done < <(cd "$plugin/backend" && find src -name '*.py')
  [ "$stale" = no ] && break
  sleep 1
done
[ "$stale" = no ] || { echo "plugin/dist/backend is out of date with plugin/backend/src"; exit 1; }

echo "Preparing a fresh Superset metadata DB…"
rm -f /tmp/superset-test.db
superset db upgrade >/tmp/db-upgrade.log 2>&1 || { cat /tmp/db-upgrade.log; exit 1; }
superset init >/tmp/init.log 2>&1 || { cat /tmp/init.log; exit 1; }

# The plugin is mounted read-only.
export PYTHONDONTWRITEBYTECODE=1
exec pytest -p no:cacheprovider "$plugin/backend/tests" "$@"
