# Production image of code-source (docs/features/011-production-image.md).
#
#   docker build -t code-source code-source
#
# Configure a container with the environment variables in AGENTS.md
# (REPO_URL is required), and secrets as mounted files.
#
# The base images are build arguments, so an organisation can use its own.
# A substitute for either must:
#   - be Debian-family: Python is installed with apt-get;
#   - have Java 21: a JDK for BUILD_IMAGE, a JRE (or JDK) for BASE_IMAGE;
#   - give both stages the same python3 at the same path. The analyzers'
#     venv is created in the build stage, and its python is a symlink to
#     the system's python3, so the runtime stage must have the same one.
#     The same distribution release in both stages is the easy way.
#
# Behind a firewall, MAVEN_REPOSITORY_URL is a mirror for Gradle's
# dependencies, and for its plugins too unless PLUGIN_REPOSITORY_URL names
# another (settings.gradle.kts).
ARG BUILD_IMAGE=eclipse-temurin:21-jdk-noble
ARG BASE_IMAGE=eclipse-temurin:21-jre-noble

FROM ${BUILD_IMAGE} AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*

ARG MAVEN_REPOSITORY_URL=
ARG PLUGIN_REPOSITORY_URL=

WORKDIR /src
COPY . .
# Installs at the runtime path, so the venv is created where it will run
# (build.gradle.kts). The cache keeps Gradle and dependencies between builds.
# The repository URLs are passed on only when they're set.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain installDist -PinstallDir=/opt/code-source \
        ${MAVEN_REPOSITORY_URL:+"-PmavenRepositoryUrl=$MAVEN_REPOSITORY_URL"} \
        ${PLUGIN_REPOSITORY_URL:+"-PpluginRepositoryUrl=$PLUGIN_REPOSITORY_URL"}

FROM ${BASE_IMAGE}

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*

# A numeric user, so k8s can check runAsNonRoot. Its home is /tmp, the
# only writable place besides DATA_DIR.
RUN groupadd --gid 10001 code-source \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /tmp \
        --shell /usr/sbin/nologin code-source \
    && install -d -o 10001 -g 10001 /var/lib/code-source

COPY --from=build /opt/code-source /opt/code-source

# JAVA_OPTS sizes the heap from the container's memory limit. Not the
# usual 75%: the analyzers' Python shares the limit during refreshes.
ENV DATA_DIR=/var/lib/code-source \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=60"

USER 10001:10001
EXPOSE 8080
# The start script execs java, so the JVM is PID 1 and gets SIGTERM.
ENTRYPOINT ["/opt/code-source/bin/code-source"]
