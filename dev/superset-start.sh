#!/usr/bin/env bash
set -e

superset db upgrade

superset fab create-admin \
  --username admin --password admin \
  --firstname Admin --lastname User \
  --email admin@example.com || true

superset init

# All of Superset's example data, including the test and big-data sets
# (1,000+ tables), loaded once on first start. Takes a few minutes.
# Set LOAD_EXAMPLES=no to skip; `docker compose down -v` to start fresh.
EXAMPLES_MARKER=/app/superset_home/.examples_loaded
if [ ! -f "$EXAMPLES_MARKER" ] && [ "${LOAD_EXAMPLES:-yes}" = "yes" ]; then
  echo "Loading example data (first start only)..."
  superset load_examples --load-test-data --load-big-data && touch "$EXAMPLES_MARKER"
fi

# The plugin's backend is imported once at startup, so Superset has to
# restart when it changes. plugin-watch.sh touches this stamp after a build
# that changed the backend. The stamp must exist before the reloader starts,
# or it isn't watched; plugin-dev creates it before it reports healthy.
BACKEND_BUILT_STAMP=/plugin-build/backend-built

# The reloader also watches the directories of loaded modules, which
# includes the backend in plugin/dist. Each build deletes and recreates
# dist, so that would restart Superset mid-build and possibly load half a
# plugin. Exclude it; the stamp is the only signal.
PLUGIN_DIST='/app/extensions/sql-examples/dist/*'

# Flask dev server, reloading when Superset's own Python or the plugin's
# backend changes.
exec superset run -h 0.0.0.0 -p 8088 --with-threads --reload --debugger \
  --extra-files "$BACKEND_BUILT_STAMP" --exclude-patterns "$PLUGIN_DIST"
