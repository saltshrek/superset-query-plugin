# Superset config for the plugin backend's tests. A throwaway metadata DB
# inside the test container; never use these values elsewhere.

import os

SECRET_KEY = "test-only-not-a-secret"

SQLALCHEMY_DATABASE_URI = "sqlite:////tmp/superset-test.db"

FEATURE_FLAGS = {
    "ENABLE_EXTENSIONS": True,
}

LOCAL_EXTENSIONS = [
    "/app/extensions/sql-examples",
]

TALISMAN_ENABLED = False

# code-source serving plugin/backend/tests/fixture-repo (set in compose.yaml).
# Tests point it elsewhere to exercise failures.
SQL_EXAMPLES_CODE_SOURCE_URL = os.environ.get("CODE_SOURCE_URL")
