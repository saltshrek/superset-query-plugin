# code-source/

Standalone, read-only HTTP service for finding and reading code in a
repository (browse, fetch, later search). Git is the first backend. It will be
extracted into its own repository and used by clients other than Superset,
mainly agents doing semantic search.

## Rules

- No Superset-specific code, names or dependencies.
- Language-specific code (SQL first) lives only in analyzers behind the
  analyzer interface. The API's generic fields are language-neutral;
  language-specific detail sits in an object keyed by the language (see
  `../docs/features/007-file-outlines.md`).
- The API is about code, not version control: no commits, branches, history
  or diffs. Git details stay behind the repository-access interface.
- Implement exactly the endpoints in `../docs/api.md`; update that file in the
  same commit as any API change.
- Treat repository paths from requests as untrusted: accept only canonical
  paths and reject anything else, never normalise (see Paths in
  `../docs/features/003-browse-tree.md`).
- Keep the repository-access code behind one module/interface, so search
  (and later semantic search) can be added without touching the HTTP layer.

## Commands

Kotlin/JVM, Ktor, Gradle (Kotlin DSL), JGit for git access. SQL is
analysed by a Python script using sqlglot (`analyzers/sql/`), run as a
subprocess once per refresh; see `../docs/features/007-file-outlines.md`.
Building needs a `python3` (3.9 or later, for sqlglot) with `venv` on the PATH: Gradle
creates the analyzers' venv from `analyzers/requirements.txt`.

- Run: `REPO_URL=<git url> ./gradlew run`. Configuration is from
  environment variables: `REPO_URL` (required), `REPO_REF`,
  `REFRESH_INTERVAL_SECONDS` (default 300), `DATA_DIR`, `PORT` (default
  8080); see `../docs/features/003-browse-tree.md`. `SQL_DIALECT` is the
  sqlglot dialect SQL is parsed in (e.g. `trino`, `oracle`; default
  sqlglot's generic one). Credentials for the
  remote are files named by `REPO_SSH_KEY_FILE` and
  `REPO_SSH_KNOWN_HOSTS_FILE` (SSH), or `REPO_USERNAME` and
  `REPO_PASSWORD_FILE` (HTTP(S)); see
  `../docs/features/005-authenticated-remotes.md`. In the dev environment
  it runs as the `code-source` service with auto-reload, serving the
  sample repository.
- Tests: `./gradlew test`. Cases about SQL dialects or the analyzer's
  heuristics go in `SqlAnalyzerTest`, which calls the real analyzer
  directly through `SqlAnalyzer.installed(dialect)`. Whole example files
  per dialect go in `src/test/resources/sql-examples/<dialect>/`, with
  a test in `SqlExamplesTest`. Changes to search's matching or ranking
  are checked against `RelevanceTest`, searches over a copy of the dev
  sample repository (`src/test/resources/relevance/`); see
  `../docs/features/014-search-descriptions-and-ctes.md`.
- Production image: `docker build code-source` (`Dockerfile`, base
  images are build arguments). `./image-test.sh` builds it and checks
  it; `deploy/kubernetes.yaml` is an example deployment. See
  `../docs/features/011-production-image.md`.
