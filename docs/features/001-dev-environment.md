# 001: Dev environment

**Status:** done

## Purpose

A one-command local environment where plugin and server changes show up in a
running Superset quickly, so every later feature can be tried end to end.

## Behaviour

- One command starts Superset (with the extension loaded from source),
  code-source, and a small sample SQL repository.
- Editing plugin source shows up in the browser without rebuilding images.
- Editing code-source source restarts or reloads the server.
- Superset starts with all of its example data loaded (standard, test and
  big-data sets), so SQL Lab's schema browser has many tables to show
  alongside the panel.

## Acceptance criteria

- [x] `docker compose -f dev/compose.yaml up` brings everything up from a clean checkout
- [x] An empty placeholder panel from `plugin/` appears in SQL Lab
- [x] code-source responds on a health endpoint from inside the Superset container
- [x] The commands are written into the AGENTS.md files
- [x] A fresh start has an examples database in SQL Lab with Superset's
      example tables, including the big-data set (1,000+ tables)

## Out of scope

- Any real panel UI or API endpoints beyond health
- Production deployment

## Components and API changes

- plugin: minimal extension skeleton registering an empty panel
- code-source: minimal service with `GET /health`
- API: add `/health` to `docs/api.md`
- dev: Docker Compose setup adapted from the hot-reload setup in
  `superset-llm-integration`, running the modified Superset 6.1.0 fork

## Open questions

- Resolved: the reference dev setup is the `superset-llm-integration` repo.
  The fork is a local checkout of upstream Superset at the 6.1.0 RC3 commit
  with an uncommitted change to SQL Lab's `AppLayout` adding the
  `sqllab.leftSidebarBottom` view location, built with
  `docker build --target lean -t superset-custom:6.1.0-mine .`.

## Decisions

- code-source: Kotlin, Ktor, Gradle (Kotlin DSL). See `docs/decisions.md`.

- Panel location: SQL Lab's left panel, shared with the built-in schema
  panel. This depends on the modified 6.1.0 fork (see `docs/vision.md`).

- The fork's location is named `sqllab.leftSidebarBottom`, following
  upstream's `sqllab.*` naming. Rejected: `sqllab.leftSidebar`, which
  upstream already declares; views at upstream locations replace what's
  there, but this one sits alongside the schema browser.

- Everything runs in containers, including the plugin and code-source
  builds. The reference setup ran `superset-extensions dev` on the host as a
  second command; containers make it one command and need no Node, Python or
  JDK on the host.

- Plugin rebuilds use our own watch loop (`dev/plugin-watch.sh`) around
  `superset-extensions build`, not `superset-extensions dev`. In a Linux
  container, `dev` rebuilds endlessly: it reacts to every watchdog event,
  including the file-open events webpack generates by reading the source.
  Alternatives: running `dev` on the host (works on macOS, but is a second
  command and needs host tooling); patching the CLI's watchdog observer
  (depends on CLI internals).

- code-source restarts on change rather than hot-reloading. Ktor's
  development-mode auto-reload logged reloads but kept serving old code.
  Restarting takes 5–10 s, needs no dev-only code in code-source, and keeps
  the old server running when the new code doesn't compile.

- code-source isn't published to the host, only exposed on the Compose
  network, in line with 002.

- Superset runs without `FLASK_DEBUG`, which keeps its debug-only
  `LOCAL_EXTENSIONS` watcher off. That watcher restarts the server on any
  watchdog event under `dist/`, including the file-open events Superset
  causes by serving the plugin, so a page load restarts the server mid-load
  and the panel fails to register. `superset run --reload --debugger` still
  gives the reloader and debugger. Frontend changes need no restart: the
  extensions API reads `dist/` on each request. Rejected: running as root
  so the watcher can write (that's what exposed the restart-on-read).


## Implementation notes

- `dev/compose.yaml`: services `superset` (fork image, `SUPERSET_IMAGE` to
  override), `plugin-dev`, `code-source`, and `sample-repo` (a bare git repo
  built from `dev/sample-repo/` into a volume at
  `file:///srv/git/examples.git`, mounted read-only into code-source for
  003). `dev/README.md` covers usage and building the fork image.
- `plugin/`: `extension.json` and a frontend-only skeleton
  (`frontend/src/index.tsx`) that registers a placeholder view at
  `sqllab.leftSidebarBottom`. Module Federation container name `quillfern_sqlExamples`.
- `code-source/`: Ktor on Netty, `GET /health`, port from `PORT`. Gradle
  9.8.1 wrapper, Kotlin 2.4.21, Ktor 3.6.0, JVM toolchain 21.
- Tests: `code-source/src/test/.../HealthTest.kt` covers `/health` through
  Ktor's test host. `dev/smoke-test.sh` checks the running stack: code-source
  health from inside the Superset container, the extension listed by
  Superset's API, and its bundle served. Whether the panel actually renders
  in SQL Lab is checked by eye: there's no browser test yet. The plugin has
  no test setup yet; the first panel feature adds it.
- Known limitations and follow-ups:
  - The fork's change is uncommitted in a local checkout, so nobody else can
    reproduce the image. Push it to a fork branch and reference it from
    `dev/README.md`.
  - The `sample-repo` volume is rebuilt on every start; code-source doesn't
    use it until 003.
  - Example data is loaded on first start (`superset load_examples
    --load-test-data --load-big-data`, as in the reference repo but with
    every set), guarded by a marker file in the `superset_home` volume.
    Superset's examples all go into one SQLite database with the single
    schema `main`: 1,023 tables.
  - Nothing restarts Superset when plugin backend code changes. A backend
    part (002) is loaded at startup, so it will need one, e.g.
    `superset run --extra-files` pointed at the built backend, since
    Werkzeug's reloader ignores open events.
  - A change made while a plugin or code-source build is running triggers
    one more build afterwards, never a missed one, but builds aren't
    debounced.
