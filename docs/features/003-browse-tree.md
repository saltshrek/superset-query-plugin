# 003: Browse folder tree

**Status:** done

## Purpose

Let analysts find examples by navigating the repository's folder structure
in SQL Lab's left panel, with no setup on their side.

## Behaviour

- The panel shows the repository's top-level folders and SQL files.
- Folders expand and collapse to show their contents, loaded as needed.
  Folders come first, then files, each sorted by name.
- Only what an analyst can use is shown: SQL files (`.sql`, any case) and
  folders. Entries whose names start with `.` (e.g. `.github`) are hidden.
  A folder is shown even if nothing inside it passes the filter.
- Navigation is pleasant to use: fast, keyboard-friendly, and clear about
  loading and error states.
- Moving through the tree with the keyboard keeps the active row in
  view, scrolling the panel as needed (added 2026-10-13, see Later
  changes).
- A Reload button in the panel re-reads the tree from code-source and
  collapses it to the top level. It doesn't make code-source fetch from the
  remote. That happens on a timer and through an operational endpoint.
- Expanded folders are not remembered across page reloads.
- The tree replaces 002's code-source status line. If code-source can't be
  reached, the tree's error state says so.

## Acceptance criteria

- [x] The panel lists the top level of the repository: folders and `.sql`
      files only, with dot-entries hidden
- [x] Expanding a folder shows its contents, loaded on expand
- [x] Folders can be expanded and collapsed with the keyboard
- [x] Moving with the keyboard scrolls the active row into view
- [x] Loading and error states are visible, and errors can be retried
      without reloading the page (for the whole tree and per folder)
- [x] code-source rejects paths that aren't canonical repository paths,
      including any that would escape the repository root
- [x] After code-source refreshes (timer or `POST /refresh`), the listing
      reflects the configured ref's new contents, and the panel's Reload
      shows them
- [x] A user without access to SQL Lab can't use the tree route

## Out of scope

- Viewing file contents or inserting code (004)
- Search or filtering by the user
- Triggering a git fetch from the panel
- Remembering expanded folders
- Remotes that need credentials (SSH or HTTPS). 003 clones any URL that
  needs none, which covers `file://` in dev and tests. See 005.
- Returning more than one level per request (a `depth` parameter)

## Components and API changes

### code-source

- Configuration from environment variables:
  - `REPO_URL` (required): the remote to clone. The service exits at
    startup if it's missing.
  - `REPO_REF` (optional): branch or tag to serve. Defaults to the
    remote's default branch.
  - `REFRESH_INTERVAL_SECONDS` (optional, default 300; 0 disables the
    timer).
  - `DATA_DIR` (optional, default a temporary directory): where the
    clone lives.
- Bare clone in the background at startup. HTTP serves immediately, and
  repository endpoints answer 503 `not_ready` until the first clone
  succeeds. A failed clone is retried at the refresh interval (or every
  30 s if the timer is disabled).
- Fetches on the timer and on `POST /refresh`. One fetch runs at a time.
  A failed fetch keeps serving the previous contents.
- Listings are read from the git tree of the commit the ref points at.
  Nothing is checked out, so `.git` never appears, and no filesystem path
  is built from request input.
- Path validation, as specified below.

### API (add to `docs/api.md`)

`GET /api/v1/tree?path=<dir>` lists one directory.

- `path` (string, optional): a canonical repository path (see Paths).
  Absent or empty means the root.
- 200:
  ```json
  {
    "path": "orders",
    "entries": [
      { "name": "archive", "path": "orders/archive", "type": "directory" },
      { "name": "top_products.sql", "path": "orders/top_products.sql", "type": "file" }
    ]
  }
  ```
  Entries are sorted by name (code-point order) and aren't filtered.
  Regular and executable files are `file`. Symlinks and submodules are
  left out. A later `depth` parameter can be added without breaking
  clients: it would default to 1 and give directory entries an optional
  nested `entries` field.
- 400 `invalid_path`: `path` isn't canonical.
- 400 `not_a_directory`: `path` names a file.
- 404 `not_found`: nothing at `path`.
- 503 `not_ready`: no successful clone yet.

`POST /refresh` (operational, at the root like `/health`) fetches now.

- Synchronous: it answers once the fetch has finished. If a fetch is
  already running, it waits for that fetch and then runs its own.
- 200 `{ "status": "ok" }`.
- 503 `refresh_failed` with the cause in `message`. The previous contents
  are still served.

### Paths (isolated logic in code-source)

A canonical repository path is either the empty string (the root) or one
or more segments joined by `/`. Each segment is non-empty, contains no
NUL, and isn't `.` or `..`. Any other input is rejected with
`invalid_path`, never normalised. In particular, a leading or trailing
`/`, `//`, and `.` or `..` segments are all rejected. Clients only need
to send back paths they received, which are always canonical.

This is stricter than the code-source AGENTS.md wording ("normalise them
and reject..."). Rejecting without normalising leaves one form per path,
so there's nothing to get wrong. I'll update that line to match.

### plugin backend

`GET /extensions/quillfern/sql-examples/code-source/tree?path=<dir>`
forwards to `GET /api/v1/tree`. It is gated by `requires_sql_lab_read`
like `/health`.

- 200 `{"result": <code-source body>}`
- 400, 404, 503 from code-source: the same status with
  `{"message": <code-source message>, "code": <code-source code>}`
- 502 when code-source is unreachable or answers anything else
- 500 when `SQL_EXAMPLES_CODE_SOURCE_URL` isn't configured

There is no refresh route (see Decisions in 002 and below).

### plugin frontend

- The panel shows a header with a Reload button, then the tree.
- Uses antd's `Tree` from the host. Superset's own `Tree` is a plain
  re-export of antd's, and the host shares `antd` as a Module Federation
  singleton, so it picks up Superset's theme. `antd` is added to
  `shared` in `webpack.config.js` with `import: false`, as React already
  is. Keyboard navigation is antd's. (Changed during implementation:
  folders load from `onExpand` rather than antd's `loadData`; see
  Decisions.)
- Filtering (`.sql`, dot-entries) and folders-first sorting happen here.
- States:
  - Top level loading: a spinner.
  - Top level failed: an error alert with a Retry button.
  - Folder loading: a "Loading…" row inside the folder.
  - Folder failed: an error row inside the folder with Retry.
  - Empty folder (after filtering): a "No SQL examples here" row.
  - Empty repository (after filtering): "No SQL examples found".
- Looks like SQL Lab's schema browser above it (added after review; see
  Decisions).

### dev

- `compose.yaml` sets `REPO_URL=file:///srv/git/examples.git` for
  code-source.
- The sample repository gains a nested folder and a non-SQL file so the
  filtering is visible by eye.
- `smoke-test.sh` checks that the tree route lists the sample repo's top
  level and is gated like `/health`.

## Testing

This is the first feature with real behaviour, so it sets up testing for
both components. Tests run on the host where they can. Only the plugin
backend's tests need a container, for Superset's Python environment.

- Node: `plugin/frontend/.nvmrc` pins 22, the version in the `plugin-dev`
  image, and `package.json` sets `engines.node`. Run `nvm use` in
  `plugin/frontend/` first. Documented in `plugin/AGENTS.md` and
  `dev/README.md`.
- JDK: Gradle's foojay resolver provisions JDK 21, so no host setup is
  needed.

- **code-source:** `./gradlew test`, through HTTP with Ktor's
  `testApplication`. Fixture repositories are built on local disk with
  JGit and cloned via `file://`. This covers listing, errors, `not_ready`,
  ref selection, and refresh after a new commit is pushed to the fixture
  remote. Paths get a unit-style test against the rule above. No fakes.
- **plugin backend:** pytest in a Superset-based container, against a
  real code-source serving a fixture repository. This covers forwarding,
  error mapping, 502 when unreachable, and the permission gate.
- **plugin frontend:** Jest and Testing Library render the panel against
  a real code-source process started on a fixture repository. code-source
  is started from its Gradle build (`installDist`).
  - Our backend can't run without Superset, so the test replaces it with
    a test-only forwarder at the `fetch` seam. The forwarder maps the
    backend route to code-source's endpoint and wraps the response as the
    backend contract above specifies. That fakes only our own thin layer,
    which the pytest suite covers against the same contract.
  - `@apache-superset/core` (`views.registerView`, `components.Alert`) is
    faked at the module boundary.
  - Tests use the user's view: visible names, clicks, keyboard, retry
    after a failure (code-source stopped, then started), and Reload after
    a fixture commit and `POST /refresh`.
- **End to end:** `dev/smoke-test.sh` as above. The panel's look is
  checked by eye.

Suggested commit sequence (one logical step each):

1. code-source: path validation
2. code-source: clone, refresh, and `GET /api/v1/tree` + `POST /refresh`,
   with the `docs/api.md` update
3. dev: code-source configured with the sample repo, and the smoke test
4. plugin backend: tree route and the pytest setup
5. plugin frontend: test setup, then the tree panel

## Open questions

- Resolved: non-`.sql` files and dot-entries are hidden by the plugin.
  code-source lists everything, so the API stays content-agnostic.
- Resolved: expanded folders aren't remembered.
- Resolved: refresh is operational only. The panel's Reload just re-reads.
- Resolved: no `depth` parameter. Each expand is one cheap request, and
  the Superset hop dominates its latency. Measure once the panel works.
  If expands feel slow, try prefetching subfolders in the panel before
  changing the API. Depth may be worth adding for agent clients later.
- Resolved: authenticated remotes move to their own feature (005).

## Decisions

- Filtering in the plugin, not through an API `?ext=` filter. The panel
  is the only client that needs it, and listings are small. A generic
  filter can be added when a second client asks. Rejected: an extension
  filter on the API (more surface now for a guess about later), and
  showing everything (READMEs would show up as things to insert in 004).
- No refresh route in the plugin backend. Any SQL Lab user could
  otherwise make code-source fetch from the remote on demand. Rejected:
  a panel button that triggers a fetch.
- Reload collapses to the top level, rather than re-reading every
  expanded folder. That's simpler, and expanded folders that have
  disappeared need no special handling. Revisit if it proves annoying.
- Strict canonical paths, rejected rather than normalised (see Paths).
- Plugin tests split at our backend (see Testing). Rejected: Playwright
  against the Compose stack for panel behaviour (slow, and AGENTS.md
  keeps end-to-end to a few smoke tests); frontend-only tests (would
  leave the backend's error mapping and gate untested).
- The tree replaces 002's status line rather than sitting under it. Its
  error state carries the same information. The `/health` route stays
  for the smoke test and operators.
- No `depth` parameter for now (see Open questions).

Made during implementation:

- Folders load from `onExpand`, not antd's `loadData`. With `loadData`,
  rc-tree keeps its own record of loaded folders, which retry and Reload
  would have had to keep in step through a controlled `loadedKeys`. Now
  the panel's folder state is the only record. The cost: a "Loading…"
  row inside the folder instead of antd's loading icon on it.
- An expanded folder with nothing to show says so ("No SQL examples
  here"). The spec only covered an empty repository, and an empty
  expanded folder looked broken.
- `Tree.DirectoryTree` rather than `Tree`: the same antd component, with
  folder and file icons and expand-on-click of the name.
- Clicking a row focuses the tree and makes that row active, so arrow
  keys work straight after a click. rc-tree takes keys through a hidden
  input that a click doesn't focus, and has no API to focus it, so the
  panel finds the input in its own container.
- The tree matches the schema browser above it (the fork's
  `TableExploreTree`): 28px rows, 16px indent, large icons, no expand
  arrows (the folder icon shows open or closed), and its hover and
  selected colours. Row height, indent and colours are antd Tree tokens,
  set by a `ConfigProvider` around our tree from the host's own tokens.
  Hiding the arrows and sizing the icons have no token, and use `styled`
  from `@apache-superset/core` with Superset's theme values, as the schema
  browser does. Rejected: overriding antd's internal classes for all of
  it (tied to antd's markup and selector precedence); keeping antd's
  arrows (redundant next to the folder icons, and unlike the neighbour).
- The `module()` used by code-source's `HealthTest` serves only the
  operational endpoints; `module(source)` is the whole service. That kept
  `HealthTest` unchanged.

## Implementation notes

- code-source (`code-source/src/main/kotlin/codesource/`):
  - `RepoPath`: canonical path parsing.
  - `CodeSource`: the repository-access interface the HTTP layer uses.
  - `GitCodeSource`: bare clone, fetch of branches and tags, and
    listings read from git tree objects. A refresh before the first
    successful clone clones. Each refresh swaps in a whole new snapshot.
    Entries are sorted by UTF-8 bytes, which is code-point order; git's
    own order and Kotlin's `String` order both differ from it.
  - `RefreshScheduler`: the timer.
  - `Application.kt`: routes and environment configuration.
  - Tests: `TreeTest` (through HTTP, with fixture repos written straight
    into git's object store by `FixtureRepo`, so symlinks and submodules
    can be built) and `RepoPathTest`. Not tested: reading the environment
    in `main()`. That was checked by hand.
- plugin backend: `tree` route in `entrypoint.py`, sharing
  `call_code_source()` with `health`. Passed-through errors are built
  with `jsonify`, because FAB's `self.response()` uses `code` for the
  status and so can't return a body field named `code`.
  - Tests: `plugin/backend/tests`, run with
    `docker compose -f dev/compose.yaml --profile test run --rm plugin-backend-test`.
    They use a throwaway SQLite metadata DB, the built extension from
    `plugin/dist`, and the `code-source-test` service (a fixture repo on
    `:8080`, and a never-ready instance on `:8081`). The run waits until
    `dist/backend` matches `backend/src`. Not tested: 502 when
    code-source answers unexpectedly.
- plugin frontend: `src/api.ts` (backend calls and error messages),
  `src/Panel.tsx`, `src/index.tsx` (registration). `@ant-design/icons` is
  bundled (two icons, about 30 KB in all); antd and React come from the
  host.
  - Tests: `npm test` in `plugin/frontend` on the host (Node 22 via
    `.nvmrc`). Jest renders the registered view against real
    code-source processes (`test/codeSource.ts`) and git fixture repos
    (`test/fixtureRepo.ts`). `test/backend.ts` stands in for our backend
    route at `fetch`, and `test/fakes/superset-core.tsx` stands in for
    `@apache-superset/core`.
  - Test-environment adjustments, each commented where made:
    `@testing-library/dom` is pinned to 8.20.1 so that user-event and
    React Testing Library 12 share the copy that wraps events in `act`.
    Arrow keys are sent with `fireEvent`, because rc-tree reads
    `keyCode`, which user-event 14 can't set. Testing Library's
    visibility check and user-event's pointer-events check are off,
    because the second set of antd styles from the `ConfigProvider`
    makes jsdom's `getComputedStyle` take seconds. ts-jest checks each
    file on its own, so globals are declared in the files that use them.
- dev:
  - code-source serves the sample repo, which now has a nested folder,
    non-SQL files and a dot-folder. It refreshes every 10 s.
  - `plugin-watch.sh` ignores `backend/tests`, and reinstalls npm
    dependencies when the package files change.
  - The smoke test checks the tree route and its permission gate.
- Known limitations:
  - Reload collapses everything.
  - `DATA_DIR`'s clone is redone from scratch on every start.
  - Credentials for remotes are 005.
  - A full frontend test run takes about 30 s, mostly JVM startups.

### Later changes

- 2026-10-13, found while reviewing 013: arrow keys could move the active
  row past the bottom of the panel, because rc-tree only scrolls its own
  virtual list, which the panel doesn't use. The panel now scrolls the
  active row into view itself, stopping below 013's sticky search bar.
  The test stubs `scrollIntoView`, which jsdom lacks, and checks which
  row it was asked to show.
