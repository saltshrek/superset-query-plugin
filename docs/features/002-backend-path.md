# 002: Backend path from plugin to code-source

**Status:** done

## Purpose

Prove the networking route early. The plugin's frontend talks only to
Superset's backend, which talks to code-source on the private network. This
checks the working assumption in `docs/vision.md` before any UI depends on
it.

## Behaviour

- The placeholder panel from 001 shows whether code-source is reachable,
  using a call that goes browser → Superset backend → code-source.
- The browser never contacts code-source directly.

## Acceptance criteria

- [x] The panel shows code-source's health status, fetched through Superset's
      backend
- [x] code-source isn't reachable from the host browser in the dev setup
- [x] code-source's base URL comes from configuration, not code
- [x] A user without access to SQL Lab can't use the backend route

## Out of scope

- Any repository endpoints (003, 004)
- Auth on code-source itself

## Components and API changes

- plugin: a backend part of the extension (`plugin/backend/`) with one
  route, `GET /extensions/quillfern/sql-examples/code-source/health`. It
  calls code-source's `/health`. Responses:
  - 200 `{"result": {"status": "ok"}}` when code-source answers 200 with
    `status: ok`
  - 502 `{"message": "..."}` when code-source is unreachable (5 s timeout)
    or answers anything else
  - 500 `{"message": "..."}` when the base URL isn't configured
  - 401/403 from Superset when the user isn't logged in or lacks
    permission

  The frontend calls it once when the panel mounts and shows "code-source:
  checking…", then "reachable" or "unreachable". There's no polling and no
  retry button.
- plugin config: Superset config key `SQL_EXAMPLES_CODE_SOURCE_URL`, e.g.
  `http://code-source:8080`. The dev `superset_config.py` reads it from the
  `CODE_SOURCE_URL` environment variable, which `compose.yaml` sets.
- dev: Superset restarts when the built backend changes (`superset run
  --extra-files`). This is the follow-up noted in 001. The smoke test
  gains checks for the acceptance criteria (see Testing).
- code-source: none beyond `/health` from 001
- API: none

## Open questions

- Resolved: the 6.1.0 fork supports a backend part. The CLI copies
  `plugin/backend/src/**` into `dist/`, and Superset imports
  `quillfern.sql_examples.entrypoint` at startup. Classes decorated with
  `superset_core.rest_api.decorators.api` are served under
  `/extensions/quillfern/sql-examples/` (not under `/api/v1`). They accept
  the browser's session cookie and are guarded by FAB permissions.
- Resolved: one backend route per operation, not a generic proxy (see
  Decisions).
- Resolved: `can_read` on `SQLLab` gates the route (see Decisions).

## Testing

Light on purpose: the app has too little behaviour yet to justify a test
setup, and later features will address testing fully. `dev/smoke-test.sh`
is extended where it helps check the criteria against the running stack.
The panel's display is checked by eye, as in 001.

## Decisions

- Route per operation, not a generic proxy. The plugin backend is the
  security boundary in front of code-source. A pass-through would expose
  every code-source endpoint to every SQL Lab user, including operational
  ones such as the planned refresh endpoint. It would also couple the
  frontend to code-source's URL layout. Each route stays a thin forwarder.
  Rejected: a generic proxy (less code per endpoint, but the above);
  an allowlisted proxy (the same coupling, and the allowlist is a route
  table in disguise).
- Gate on `can_read` on `SQLLab`, the permission SQL Lab's own bootstrap
  endpoint (`GET /api/v1/sqllab/`) requires. Anyone who can use SQL Lab
  has it, and Gamma doesn't (it's in `SQLLAB_PERMISSION_VIEWS`).
  Admins have nothing new to grant. Rejected: a permission of our own,
  which only Admin would get by default, so every deployment would have
  to grant it to SQL Lab users. Also rejected: `can_sqllab` on `Superset`
  (the page permission). It's equivalent in practice, and SQLLab's
  `can_read` is the one SQL Lab's API checks.
- The check is a small decorator, `requires_sql_lab_read`, not FAB's
  `@protect()`. Changed during implementation: the agreed plan was
  `@protect()` with `class_permission_name = "SQLLab"`. But when Superset
  registers an extension API, FAB syncs that view menu's permissions to
  the API's methods, deleting every other permission on `SQLLab` (e.g.
  `can_execute_sql_query`) from all roles. That would break SQL Lab for
  everyone but Admin on each start. The decorator follows `@protect()`'s
  authentication (session cookie, then JWT) and checks `can_read` on
  `SQLLab` directly. The API keeps its own permission name,
  `SqlExamplesCodeSource`, which has no permissions on it. Rejected:
  overriding `base_permissions` with SQLLab's full list (breaks when
  Superset adds one).
- Superset restarts on a stamp file, not on the built backend. The
  plugin build deletes and recreates `dist/`, so watching it restarts
  Superset mid-build. `plugin-watch.sh` touches
  `/plugin-build/backend-built` (a volume shared with `superset`) after
  a successful build that changed `backend/`. `superset-start.sh` passes
  it to `--extra-files` and excludes `dist/` with `--exclude-patterns`.
  Werkzeug otherwise watches the directories of loaded modules, which
  include the backend in `dist/`. Rejected: watching `plugin/backend`
  source (restarts before the build has copied it), and restarting on
  every build (slows frontend work for nothing).
- The base URL comes from Superset config, not from the extension's own
  environment lookup. This is where Superset admins expect deployment
  settings, and the dev config forwards an environment variable.

## Implementation notes

- `plugin/backend/`: `pyproject.toml` (CLI build config) and
  `src/quillfern/sql_examples/entrypoint.py` with `CodeSourceApi` and the
  `requires_sql_lab_read` decorator. Uses `requests` from Superset's own
  environment.
- `plugin/frontend/src/index.tsx`: the panel fetches the route on mount
  and shows the result in Superset's `Alert` (from
  `@apache-superset/core`'s `components`, which the host provides at
  runtime).
- dev: `CODE_SOURCE_URL` in `compose.yaml` → `SQL_EXAMPLES_CODE_SOURCE_URL`
  in `superset_config.py`. Backend reload as in Decisions.
- `dev/smoke-test.sh` checks the route as admin, that code-source has no
  host port, and the permission gate. It creates two users, `smoke_gamma`
  (Gamma) and `smoke_sqllab` (`sql_lab`), whose passwords equal their
  usernames. Checked by hand: 401 without login, 502 with code-source
  stopped. Not exercised: the 500 for missing config. The panel's
  display was checked by eye.
- Fixed in passing: `plugin-watch.sh` (from 001) missed edits made on a
  macOS host, which reach the container only as MODIFY events.
- Known limitations:
  - If a second backend build finishes while Superset is still starting
    after the first, its stamp touch can be missed. Superset then runs
    the first build's backend until the next backend change.
  - The route path is hard-coded in the frontend. It's our own
    extension's path, not code-source's URL.
  - No retry or polling in the panel; reload the page to re-check.
