# Dev environment

One command starts Superset with the plugin loaded from source, code-source,
and a sample SQL repository:

    docker compose -f dev/compose.yaml up

Then open http://localhost:8088 (admin / admin) and go to SQL Lab. The
plugin's panel sits below the schema browser in the left panel.

The first start takes several minutes: pulling images, downloading Gradle
and npm dependencies, and loading Superset's example data (about 3 minutes).
Later starts take about a minute. All builds run
inside containers; you need only Docker on the host to run the stack.

Check it's all working with:

    dev/smoke-test.sh

It creates two test users, `smoke_gamma` and `smoke_sqllab` (password =
username), to check that only SQL Lab users can reach the plugin's
backend.

## What runs

| Service       | What it does                                                                    |
|---------------|---------------------------------------------------------------------------------|
| `superset`    | The Superset fork, loading `plugin/` via `LOCAL_EXTENSIONS`. Port 8088. Finds code-source via `CODE_SOURCE_URL`. |
| `plugin-dev`  | Rebuilds `plugin/dist` (via `superset-extensions build`) when plugin source changes. |
| `code-source` | Rebuilds and restarts code-source when its source changes. Internal only, at `http://code-source:8080`. |
| `sample-repo` | Builds a bare git repo from `dev/sample-repo/` at `file:///srv/git/examples.git`. |

## Tests

Component test commands are in `code-source/AGENTS.md` and
`plugin/AGENTS.md`. code-source's and the plugin frontend's tests run on
the host: they need a JDK (Gradle provisions 21), `git`, and Node 22 for
the frontend (`nvm use` in `plugin/frontend/` picks it from `.nvmrc`). The plugin backend's tests need Superset itself, so
they run in Compose services under the `test` profile:

    docker compose -f dev/compose.yaml --profile test run --rm plugin-backend-test

| Service               | What it does                                                            |
|-----------------------|-------------------------------------------------------------------------|
| `plugin-backend-test` | The Superset fork plus pytest. Prepares a throwaway metadata DB, then runs `plugin/backend/tests`. |
| `code-source-test`    | Two code-source instances: `:8080` serves `plugin/backend/tests/fixture-repo`, `:8081` never becomes ready. |
| `fixture-repo`        | Builds the fixture repo, as `sample-repo` does for the sample. |

The tests load the extension from `plugin/dist`, which `plugin-dev`
builds, so the run starts `plugin-dev` if needed and waits until the
built backend matches `plugin/backend/src`. Arguments after the service
name go to pytest, e.g. `-k not_ready`. The first run builds code-source, which
takes a minute or two.

## Running code-source's production image

To try code-source as production runs it (`code-source/Dockerfile`:
non-root, read-only root filesystem), swap it in for the source-built
service:

    docker compose -f dev/compose.yaml -f dev/compose.image.yaml up -d --build code-source

`dev/smoke-test.sh` runs against it unchanged. There's no auto-reload:
rerun the command after changing code-source. To go back:

    docker compose -f dev/compose.yaml up -d code-source

`code-source/image-test.sh` checks the image on its own, without the
stack; see `docs/features/011-production-image.md`.

## Example data

On first start Superset loads all of its example data into an `examples`
database (SQLite, schema `main`): the standard examples plus the test and
big-data sets, about 1,000 tables in all, including 1,000 tiny
`small_table_N` tables, a 100-column `wide_table` and a table with a
60-character name. A marker in the `superset_home` volume stops it loading
again; `docker compose -f dev/compose.yaml down -v` resets it. To skip
loading, start with `LOAD_EXAMPLES=no`.

## Making changes

- **Plugin frontend:** edit `plugin/frontend/src`, wait a few seconds for
  the rebuild, then refresh the browser. Superset reads `plugin/dist` on
  each request, so it doesn't need restarting.
- **Plugin backend:** edit `plugin/backend/src`. After the rebuild,
  `plugin-dev` touches a stamp that makes Superset restart (5–10 s), since
  Superset imports the backend only at startup.
- **code-source:** edit `code-source/src`; it's back with the change in
  5–10 seconds. If the change doesn't compile, the previous version keeps
  running and the error is in `docker compose -f dev/compose.yaml logs code-source`.
- **Sample repo:** edit `dev/sample-repo/` and restart `sample-repo`. It's
  rebuilt from scratch each time. code-source fetches it every 10 seconds;
  use Reload in the panel to see the change.

To start from a clean slate (Superset metadata DB, caches, node_modules):

    docker compose -f dev/compose.yaml down -v

## The Superset image

`superset` runs our Superset 6.1.0 fork, which adds the `sqllab.leftSidebarBottom` view
location to SQL Lab's left panel. Upstream images don't have it: the plugin
loads, but the panel never appears.

Build the fork image from a checkout of the fork:

    cd path/to/superset-fork
    docker build --target lean -t superset-custom:6.1.0-mine .

Use another tag with `SUPERSET_IMAGE=<image> docker compose -f dev/compose.yaml up`.
