# Dev-only Superset config. Never use these values in production.

import os

SECRET_KEY = "dev-only-not-a-secret"

# Metadata DB: SQLite in the persisted volume.
SQLALCHEMY_DATABASE_URI = "sqlite:////app/superset_home/superset.db"

FEATURE_FLAGS = {
    "ENABLE_EXTENSIONS": True,
}

# Path inside the container; see the volume in compose.yaml.
LOCAL_EXTENSIONS = [
    "/app/extensions/sql-examples",
]

TALISMAN_ENABLED = False

# Where the plugin's backend finds code-source (set in compose.yaml).
SQL_EXAMPLES_CODE_SOURCE_URL = os.environ.get("CODE_SOURCE_URL")
