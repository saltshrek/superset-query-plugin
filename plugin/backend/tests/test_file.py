"""GET /extensions/quillfern/sql-examples/code-source/file

docs/features/004-use-a-file.md, "plugin backend".
"""

from typing import Any

from flask.testing import FlaskClient

from conftest import GAMMA_USER, SQL_LAB_USER

FILE = "/extensions/quillfern/sql-examples/code-source/file"


def get_file(client: FlaskClient, token: str, **params: str) -> Any:
    return client.get(
        FILE, query_string=params, headers={"Authorization": f"Bearer {token}"}
    )


def test_returns_a_files_contents(client, token_for, code_source_body):
    response = get_file(client, token_for(SQL_LAB_USER), path="queries/one.sql")

    assert response.status_code == 200
    assert response.json == {
        "result": code_source_body("/api/v1/file", path="queries/one.sql")
    }
    assert response.json["result"]["content"] == "select 1;\n"


def test_passes_through_invalid_path(client, token_for):
    response = get_file(client, token_for(SQL_LAB_USER), path="../etc/passwd")

    assert response.status_code == 400
    assert response.json["code"] == "invalid_path"
    assert response.json["message"]


def test_passes_through_not_a_file(client, token_for):
    response = get_file(client, token_for(SQL_LAB_USER), path="queries")

    assert response.status_code == 400
    assert response.json["code"] == "not_a_file"


def test_passes_through_not_found(client, token_for):
    response = get_file(client, token_for(SQL_LAB_USER), path="queries/missing.sql")

    assert response.status_code == 404
    assert response.json["code"] == "not_found"


def test_passes_through_too_large(client, token_for):
    response = get_file(client, token_for(SQL_LAB_USER), path="queries/too_large.sql")

    assert response.status_code == 413
    assert response.json["code"] == "too_large"
    assert response.json["message"]


def test_passes_through_not_text(client, token_for):
    response = get_file(client, token_for(SQL_LAB_USER), path="queries/latin1.sql")

    assert response.status_code == 422
    assert response.json["code"] == "not_text"
    assert response.json["message"]


def test_passes_through_not_ready(client, token_for, code_source_url, not_ready_url):
    code_source_url(not_ready_url)

    response = get_file(client, token_for(SQL_LAB_USER), path="queries/one.sql")

    assert response.status_code == 503
    assert response.json["code"] == "not_ready"


def test_unreachable_code_source_is_a_bad_gateway(client, token_for, code_source_url):
    code_source_url("http://nothing-listens-here.invalid:8080")

    response = get_file(client, token_for(SQL_LAB_USER), path="queries/one.sql")

    assert response.status_code == 502
    assert response.json["message"]


def test_missing_configuration_is_a_server_error(client, token_for, code_source_url):
    code_source_url(None)

    response = get_file(client, token_for(SQL_LAB_USER), path="queries/one.sql")

    assert response.status_code == 500
    assert "SQL_EXAMPLES_CODE_SOURCE_URL" in response.json["message"]


def test_requires_login(client):
    assert client.get(FILE, query_string={"path": "queries/one.sql"}).status_code == 401


def test_requires_sql_lab_access(client, token_for):
    response = get_file(client, token_for(GAMMA_USER), path="queries/one.sql")

    assert response.status_code == 403
