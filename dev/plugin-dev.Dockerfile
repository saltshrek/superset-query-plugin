# Node + the Superset extensions CLI, plus inotify-tools for plugin-watch.sh.
FROM node:22-bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv inotify-tools \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /opt/cli \
    && /opt/cli/bin/pip install --no-cache-dir apache-superset-extensions-cli==0.1.0

ENV PATH="/opt/cli/bin:${PATH}"
