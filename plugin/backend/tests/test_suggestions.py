"""GET /extensions/quillfern/sql-examples/code-source/suggestions

docs/features/013-search-by-outline.md, "plugin backend".
"""

from typing import Any

from flask.testing import FlaskClient

from conftest import GAMMA_USER, SQL_LAB_USER

SUGGESTIONS = "/extensions/quillfern/sql-examples/code-source/suggestions"


def suggest(client: FlaskClient, token: str, params: Any) -> Any:
    return client.get(
        SUGGESTIONS, query_string=params, headers={"Authorization": f"Bearer {token}"}
    )


def values(response: Any) -> list[str]:
    assert response.status_code == 200, response.json
    return [s["value"] for s in response.json["result"]["suggestions"]]


def test_returns_code_sources_suggestions(client, token_for, code_source_body):
    response = suggest(client, token_for(SQL_LAB_USER), {"q": "ord"})

    assert response.status_code == 200
    assert response.json == {"result": code_source_body("/api/v1/suggestions", q="ord")}
    assert "orders" in values(response)


def test_forwards_every_field(client, token_for):
    token = token_for(SQL_LAB_USER)

    assert values(suggest(client, token, {"q": "s", "field": "function"})) == ["SUM"]
    assert values(
        suggest(client, token, [("q", "s"), ("field", "function"), ("field", "reference")])
    ) == ["sales.orders", "SUM"]


def test_forwards_ext_and_limit(client, token_for):
    token = token_for(SQL_LAB_USER)

    assert values(suggest(client, token, {"q": "ord", "ext": "md"})) == []
    assert len(values(suggest(client, token, {"q": "s", "limit": "1"}))) == 1


def test_passes_through_invalid_parameter(client, token_for):
    response = suggest(client, token_for(SQL_LAB_USER), {"q": "ord", "field": "table"})

    assert response.status_code == 400
    assert response.json["code"] == "invalid_parameter"
    assert "field" in response.json["message"]


def test_passes_through_not_ready(client, token_for, code_source_url, not_ready_url):
    code_source_url(not_ready_url)

    response = suggest(client, token_for(SQL_LAB_USER), {"q": "ord"})

    assert response.status_code == 503
    assert response.json["code"] == "not_ready"


def test_unreachable_code_source_is_a_bad_gateway(client, token_for, code_source_url):
    code_source_url("http://nothing-listens-here.invalid:8080")

    response = suggest(client, token_for(SQL_LAB_USER), {"q": "ord"})

    assert response.status_code == 502
    assert response.json["message"]


def test_missing_configuration_is_a_server_error(client, token_for, code_source_url):
    code_source_url(None)

    response = suggest(client, token_for(SQL_LAB_USER), {"q": "ord"})

    assert response.status_code == 500
    assert "SQL_EXAMPLES_CODE_SOURCE_URL" in response.json["message"]


def test_requires_login(client):
    assert client.get(SUGGESTIONS, query_string={"q": "ord"}).status_code == 401


def test_requires_sql_lab_access(client, token_for):
    response = suggest(client, token_for(GAMMA_USER), {"q": "ord"})

    assert response.status_code == 403
