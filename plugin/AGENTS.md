# plugin/

Superset extension that adds the SQL examples panel to SQL Lab.

## Rules

- Get data only from code-source, through the endpoints in `../docs/api.md`.
  No direct filesystem or git access from the plugin.
- The browser never calls code-source. The frontend calls the plugin's
  backend (`backend/src/quillfern/sql_examples/entrypoint.py`, served under
  `/extensions/quillfern/sql-examples/`), which has one route per
  code-source operation. Every route uses `requires_sql_lab_read`. Never
  set an API's `class_permission_name` to an existing Superset view such
  as `SQLLab`: FAB would delete that view's other permissions.
- The code-source base URL comes from configuration, never hard-coded:
  Superset config key `SQL_EXAMPLES_CODE_SOURCE_URL`.
- The panel should look and feel native to Superset. Prefer Superset's own
  UI components and theme tokens over custom components or styling; add
  custom UI only where Superset has nothing suitable.
- Use the `@apache-superset/core` APIs for registering views and commands.
  Check the Superset version in `dev/` before assuming an API exists; the
  extension API has been changing between releases.

## Commands

- Dev build/watch: runs in the `plugin-dev` service of
  `docker compose -f dev/compose.yaml up`. The extension's frontend lives in
  `frontend/` and its backend in `backend/`; Superset loads the built
  `dist/` from this directory, and restarts after backend changes.
- Frontend tests (Jest, on the host): in `frontend/`, `nvm use` (Node 22,
  from `.nvmrc`), `npm install`, then `npm test`. They render the panel
  in jsdom against real code-source processes, built from `../code-source`
  with Gradle on first run, and real git fixture repos (needs `git`).
  `@apache-superset/core` is faked in `test/fakes/`; our backend route is
  replaced by a forwarder (`test/backend.ts`) that must match the route's
  contract in the feature specs (`docs/features/003-browse-tree.md`
  for the tree, `004-use-a-file.md` for files, `006-filename-search.md`
  and `013-search-by-outline.md` for search and suggestions).
- Backend tests (pytest, against a real Superset and code-source):
  `docker compose -f dev/compose.yaml --profile test run --rm plugin-backend-test`.
  See `dev/README.md`.
- Bundle: `superset-extensions bundle`. Installing it in a real
  Superset: `DEPLOYMENT.md`.
