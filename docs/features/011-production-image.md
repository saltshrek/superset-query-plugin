# 011: Production image for code-source

**Status:** done

## Purpose

A container image of code-source that can run in production on k8s, and
in Compose when we want to try the real thing locally. Until now
code-source has only run from source, through Gradle, in the dev image.
Since 007, code-source includes a Python analyzer in a venv, and how that
is packaged is one of 007's open results: an installation's venv has to
be created in place, so the image has to build it itself. The image is
code-source's own, kept in `code-source/`, so it moves with code-source
when it's extracted.

## Behaviour

What an operator sees:

- One `docker build` of `code-source/` gives a self-contained image: the
  service, its analyzers and their venv, and the Python and Java they
  need. Nothing is built or downloaded when a container starts.
- A container is configured exactly as code-source is today: the
  environment variables in `code-source/AGENTS.md` (`REPO_URL`,
  `REPO_REF`, `REFRESH_INTERVAL_SECONDS`, `DATA_DIR`, `PORT`,
  `SQL_DIALECT`), with secrets as mounted files (005).
- It runs as a non-root user and needs nothing writable except
  `DATA_DIR` and a temporary directory, so it works with a read-only root
  filesystem.
- It exposes `PORT` (8080 by default). `GET /health` is suitable for
  liveness probes. A readiness probe can use any repository endpoint,
  which answers 503 `not_ready` until the first load.
- It stops cleanly and promptly on SIGTERM, as k8s sends.
- The base image is a build argument with a default, so an
  organisation can substitute its own, and the Dockerfile documents what
  a substitute must provide.
- The dev environment can run the image instead of the source-built
  service, opt-in, for trying production behaviour locally.
- An example k8s manifest shows code-source deployed from the image:
  probes, a non-root read-only container, secrets as mounted files, and
  reachable only from Superset. It's an example to adapt, not a
  supported chart.
- A deployment guide in `plugin/` tells a Superset operator how to
  install the extension: the bundle, the Superset config and environment
  it needs, and the change the Superset fork needs (a short snippet, not
  a patch to maintain).

## Acceptance criteria

- [x] `docker build code-source` succeeds from a clean checkout, with no
      dependencies beyond Docker
- [x] The base images are build arguments (`BUILD_IMAGE`, `BASE_IMAGE`)
      with working defaults
- [x] A container from the image serves a repository, with SQL outlines
      (not partial) for a valid SQL file
- [x] The container runs as a non-root numeric user, and works with a
      read-only root filesystem plus writable `DATA_DIR` and `/tmp`
- [x] The analyzer in the image uses the same sqlglot version as the
      tests (`analyzers/requirements.txt`)
- [x] The container exits within a few seconds of SIGTERM
- [x] A Compose override runs the image against the sample repository
      in place of the source-built service, and the dev smoke test passes
      against it
- [x] The example k8s manifest is valid (client-side dry run)
- [x] The image's size is recorded, with and without the analyzer's
      Python, as a result of 007's experiment

The image criteria are checked by `code-source/image-test.sh`, which
builds the image and runs it against a fixture repository.

## Out of scope

- Publishing the image: registries, tags, CI pipelines
- A Helm chart, or k8s manifests for Superset itself. The one manifest
  is an example for code-source.
- Packaging the plugin, which ships as a Superset extension (`.supx`),
  not an image. The deployment guide says how to build and install it.
- Making the image smaller than it reasonably needs to be: a distroless
  or custom JRE base, for example. Measure first.
- Multi-architecture builds, unless one of ours needs it (see Open
  questions)

## Components and API changes

- **code-source:** `code-source/Dockerfile` and `.dockerignore`. A
  multi-stage build:
  - A build stage with the JDK and `python3`, running `./gradlew
    installDist`. That compiles code-source and creates the analyzers'
    venv in place (007).
  - A runtime stage with a JRE and the same `python3`, holding only the
    installation.
  The venv's `python` is a symlink to the system Python, so it must be at
  the same path, and the same version, in both stages. Using the same
  base distribution for both (e.g. `eclipse-temurin:21-jdk` and
  `21-jre`, both Ubuntu) keeps them in step. The alternative is creating
  the venv in the runtime stage, which needs `pip` there and the network
  at build time in that stage too.
- **code-source:** `deploy/kubernetes.yaml`, the example manifest.
- **code-source build:** an optional `installDir` Gradle property, so the
  image can install to its runtime path and create the venv there.
- **dev/:** `compose.image.yaml`, an override that swaps the `code-source`
  service for the image. `dev/smoke-test.sh` runs unchanged against it.
- **plugin:** `DEPLOYMENT.md`, the deployment guide.
- **API:** none.

## Open questions

Answered as below, but not yet confirmed against the cluster's
policies. Each one is easy to change later.

- **Base images.** Build arguments, defaulting to Temurin 21 on Ubuntu
  24.04 (`eclipse-temurin:21-jdk-noble` and `21-jre-noble`). A
  substitute must be Debian-family (the image installs `python3` with
  `apt-get`), have Java 21, and give both stages the same `python3`.
- **Architecture.** Whatever the build machine is; nothing in the
  Dockerfile is architecture-specific and the defaults are multi-arch,
  so `docker buildx build --platform linux/amd64` works from Apple
  Silicon.
- **Run-time user.** UID and GID 10001, numeric in `USER`, so
  `runAsNonRoot` can verify it.
- **JVM settings.** Rely on container awareness, with
  `-XX:MaxRAMPercentage=60` as the image's default `JAVA_OPTS`. Lower
  than the usual 75 because the analyzer's Python runs in the same
  memory limit during refreshes. Operators override `JAVA_OPTS`.
- **Temporary directory.** The image sets `DATA_DIR=/var/lib/code-source`
  and declares no volumes. Operators mount `/tmp` and `DATA_DIR` (an
  `emptyDir` each in the manifest). `VOLUME` would leave anonymous
  volumes behind on every `docker run`, and derived images can't undo it.
- **Smoke test against the image.** Run the existing `dev/smoke-test.sh`
  with the image swapped in by a Compose override, so the same checks
  cover both.

## Decisions

- **The image installs at its runtime path.** `installDist
  -PinstallDir=/opt/code-source` in the build stage, then the runtime
  stage copies that directory to the same path. The venv is created
  where it runs (007), and Docker's `COPY --from` keeps its `python`
  symlink to `/usr/bin/python3` as a symlink. Alternatives: build in
  `build/install/` and copy to another path (moves a venv, which 007
  avoids); create the venv in the runtime stage (needs `pip`,
  `python3-venv` and the network there, and bigger layers).
- **Two base-image arguments, not one.** `BUILD_IMAGE` and `BASE_IMAGE`,
  since the stages need a JDK and a JRE. The Dockerfile's header says
  what a substitute must provide. A single argument with `-jdk`/`-jre`
  suffixes would only work for images named Temurin's way.
- **No `HOME` setting.** Tried with an unwritable `HOME` on a read-only
  root: nothing failed or warned (JGit doesn't need it here; SSH uses
  code-source's private directory under `/tmp`, 005). The user's home is
  `/tmp`, in case anything ever wants one.
- **A Compose override, not a profile.** A profile would add a second
  service beside `code-source`, and Superset and the smoke test would
  still talk to the source-built one. The override replaces the service
  under the same name, so the smoke test runs unchanged. It has an
  image name of its own: without one, building it replaced the dev
  service's image, which share the default name.
- **The k8s example includes a NetworkPolicy.** code-source has no auth
  and an operational `POST /refresh`, so "only Superset can reach it"
  is part of deploying it safely, not an extra.

## Implementation notes

- `code-source/Dockerfile`, `.dockerignore`, `image-test.sh` (the image
  acceptance checks), `deploy/kubernetes.yaml`; `build.gradle.kts` gains
  the optional `installDir` property.
- `dev/compose.image.yaml` swaps the image in; `dev/README.md` says how.
  `dev/smoke-test.sh` passed against it unchanged.
- `plugin/DEPLOYMENT.md`: bundle, `EXTENSIONS_PATH`, config and
  environment, permissions, and the fork's SQL Lab change as a snippet.
  A bundle built by `superset-extensions bundle` was checked to load
  through the fork's `.supx` discovery.
- **Size** (arm64, Temurin 21 JRE on Ubuntu 24.04): 428 MB in all. The
  base is 318 MB, code-source's jars 51 MB, `python3` from apt 36 MB,
  the analyzers' venv (sqlglot) 23 MB. Without the analyzer's Python
  it would be about 369 MB, so Python adds about 59 MB (14%).
- **Stopping**: SIGTERM stops the container in 1–2 s, exit code 143.
  Ktor's shutdown hook needs nothing of ours.
- **Build time**: about ten minutes from cold on an Apple Silicon
  laptop with the dev stack running, mostly Gradle and Kotlin
  compilation. Gradle's cache is a BuildKit cache mount, so rebuilds
  are faster.
- **Follow-ups**: Lucene logs that the Java vector module isn't enabled
  (`--add-modules jdk.incubator.vector` in `JAVA_OPTS` would enable it).
  It's harmless at this index size, and dev logs it too. The plugin
  guide hasn't been tried with Superset's Talisman (CSP) on.
