"""GET /extensions/quillfern/sql-examples/code-source/tree

docs/features/003-browse-tree.md, "plugin backend".
"""

from typing import Any, Callable

from flask.testing import FlaskClient

from conftest import GAMMA_USER, SQL_LAB_USER

TREE = "/extensions/quillfern/sql-examples/code-source/tree"


def get_tree(client: FlaskClient, token: str, **params: str) -> Any:
    return client.get(
        TREE, query_string=params, headers={"Authorization": f"Bearer {token}"}
    )


def entry_names(response: Any) -> list[str]:
    return [entry["name"] for entry in response.json["result"]["entries"]]


def test_lists_the_root(client, token_for, code_source_body):
    response = get_tree(client, token_for(SQL_LAB_USER))

    assert response.status_code == 200
    assert response.json == {"result": code_source_body("/api/v1/tree")}
    assert entry_names(response) == ["README.md", "queries"]


def test_lists_a_directory(client, token_for):
    response = get_tree(client, token_for(SQL_LAB_USER), path="queries/nested")

    assert response.status_code == 200
    assert response.json["result"]["path"] == "queries/nested"
    assert entry_names(response) == ["two.sql"]


def test_passes_through_invalid_path(client, token_for):
    response = get_tree(client, token_for(SQL_LAB_USER), path="../etc")

    assert response.status_code == 400
    assert response.json["code"] == "invalid_path"
    assert response.json["message"]


def test_passes_through_not_a_directory(client, token_for):
    response = get_tree(client, token_for(SQL_LAB_USER), path="queries/one.sql")

    assert response.status_code == 400
    assert response.json["code"] == "not_a_directory"


def test_passes_through_not_found(client, token_for):
    response = get_tree(client, token_for(SQL_LAB_USER), path="missing")

    assert response.status_code == 404
    assert response.json["code"] == "not_found"


def test_passes_through_not_ready(client, token_for, code_source_url, not_ready_url):
    code_source_url(not_ready_url)

    response = get_tree(client, token_for(SQL_LAB_USER))

    assert response.status_code == 503
    assert response.json["code"] == "not_ready"


def test_unreachable_code_source_is_a_bad_gateway(client, token_for, code_source_url):
    code_source_url("http://nothing-listens-here.invalid:8080")

    response = get_tree(client, token_for(SQL_LAB_USER))

    assert response.status_code == 502
    assert response.json["message"]


def test_missing_configuration_is_a_server_error(client, token_for, code_source_url):
    code_source_url(None)

    response = get_tree(client, token_for(SQL_LAB_USER))

    assert response.status_code == 500
    assert "SQL_EXAMPLES_CODE_SOURCE_URL" in response.json["message"]


def test_requires_login(client):
    assert client.get(TREE).status_code == 401


def test_requires_sql_lab_access(client, token_for):
    response = get_tree(client, token_for(GAMMA_USER))

    assert response.status_code == 403
