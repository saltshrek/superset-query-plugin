"""Fixtures for the plugin backend's tests.

They run in the `plugin-backend-test` Compose service (see dev/README.md):
a real Superset app with this extension loaded from plugin/dist, calling a
real code-source that serves tests/fixture-repo.
"""

import os
import time
from typing import Any, Callable

import pytest
import requests
from flask import Flask
from flask.testing import FlaskClient

from superset.app import create_app

CODE_SOURCE_URL_CONFIG_KEY = "SQL_EXAMPLES_CODE_SOURCE_URL"

# Users created for the tests; each password equals the username.
SQL_LAB_USER = "test_sqllab"  # role sql_lab: can use SQL Lab
GAMMA_USER = "test_gamma"  # role Gamma: can't


def _ensure_user(app: Flask, username: str, role_name: str) -> None:
    sm = app.appbuilder.sm
    if sm.find_user(username=username):
        return
    sm.add_user(
        username,
        username,
        "Test",
        f"{username}@example.com",
        sm.find_role(role_name),
        password=username,
    )


def _wait_until_ready(base_url: str, timeout_seconds: float = 300) -> None:
    """code-source builds itself on start and then clones; wait for both."""
    deadline = time.monotonic() + timeout_seconds
    while True:
        try:
            if requests.get(f"{base_url}/api/v1/tree", timeout=5).status_code == 200:
                return
        except requests.RequestException:
            pass
        if time.monotonic() > deadline:
            raise RuntimeError(f"code-source at {base_url} didn't become ready")
        time.sleep(1)


@pytest.fixture(scope="session")
def app() -> Flask:
    app = create_app()
    app.config["TESTING"] = True
    with app.app_context():
        _ensure_user(app, SQL_LAB_USER, "sql_lab")
        _ensure_user(app, GAMMA_USER, "Gamma")
    _wait_until_ready(app.config[CODE_SOURCE_URL_CONFIG_KEY])
    return app


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    return app.test_client()


@pytest.fixture
def token_for(client: FlaskClient) -> Callable[[str], str]:
    """An access token for one of the test users."""

    def login(username: str) -> str:
        response = client.post(
            "/api/v1/security/login",
            json={"username": username, "password": username, "provider": "db"},
        )
        assert response.status_code == 200, response.json
        return response.json["access_token"]

    return login


@pytest.fixture
def code_source_url(
    app: Flask, monkeypatch: pytest.MonkeyPatch
) -> Callable[[str | None], None]:
    """Points the extension at another code-source for one test."""

    def set_url(url: str | None) -> None:
        monkeypatch.setitem(app.config, CODE_SOURCE_URL_CONFIG_KEY, url)

    return set_url


@pytest.fixture
def code_source_body(app: Flask) -> Callable[..., Any]:
    """What code-source itself answers at an endpoint, for checking that a
    route passes its body through unchanged."""

    def get(endpoint: str, **params: str) -> Any:
        url = app.config[CODE_SOURCE_URL_CONFIG_KEY] + endpoint
        response = requests.get(url, params=params, timeout=10)
        assert response.status_code == 200, response.text
        return response.json()

    return get


@pytest.fixture
def not_ready_url() -> str:
    return os.environ["NOT_READY_CODE_SOURCE_URL"]
