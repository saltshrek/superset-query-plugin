# JDK + inotify-tools for code-source-dev.sh, and Python for code-source's
# analyzers (its build creates their venv).
FROM eclipse-temurin:21-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends inotify-tools python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*
