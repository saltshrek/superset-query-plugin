# The Superset fork plus pytest, for the plugin backend's tests
# (plugin/backend/tests). See plugin-backend-test.sh.
ARG SUPERSET_IMAGE=superset-custom:6.1.0-mine
FROM ${SUPERSET_IMAGE}

USER root
RUN uv pip install --no-cache --python /app/.venv/bin/python pytest==8.4.2
USER superset
