"""GET /extensions/quillfern/sql-examples/code-source/search

docs/features/006-filename-search.md and 013-search-by-outline.md,
"plugin backend".
"""

from typing import Any

from flask.testing import FlaskClient

from conftest import GAMMA_USER, SQL_LAB_USER

SEARCH = "/extensions/quillfern/sql-examples/code-source/search"


def search(client: FlaskClient, token: str, params: Any) -> Any:
    return client.get(
        SEARCH, query_string=params, headers={"Authorization": f"Bearer {token}"}
    )


def paths(response: Any) -> list[str]:
    assert response.status_code == 200, response.json
    return [r["path"] for r in response.json["result"]["results"]]


def test_returns_code_sources_results(client, token_for, code_source_body):
    response = search(client, token_for(SQL_LAB_USER), {"q": "one"})

    assert response.status_code == 200
    assert response.json == {"result": code_source_body("/api/v1/search", q="one")}
    assert paths(response) == ["queries/one.sql"]


def test_forwards_every_ext(client, token_for):
    token = token_for(SQL_LAB_USER)

    assert paths(search(client, token, {"q": "readme", "ext": "sql"})) == []
    assert paths(
        search(client, token, [("q", "readme"), ("ext", "sql"), ("ext", "md")])
    ) == ["README.md"]


def test_forwards_every_outline_filter(client, token_for):
    token = token_for(SQL_LAB_USER)

    assert paths(search(client, token, {"reference": "orders"})) == [
        "queries/by_region.sql"
    ]
    assert paths(search(client, token, {"function": "sum", "keyword": "group by"})) == [
        "queries/by_region.sql"
    ]
    assert (
        paths(search(client, token, [("reference", "orders"), ("reference", "regions")]))
        == []
    )


def test_forwards_unqualified(client, token_for):
    token = token_for(SQL_LAB_USER)
    asked = {"reference": "iceberg.sales.orders"}

    assert paths(search(client, token, asked)) == ["queries/by_region.sql"]
    assert paths(search(client, token, {**asked, "unqualified": "exclude"})) == []


def test_forwards_limit(client, token_for):
    response = search(client, token_for(SQL_LAB_USER), {"q": "queries", "limit": "1"})

    assert len(paths(response)) == 1
    assert response.json["result"]["total"] > 1


def test_passes_through_invalid_parameter(client, token_for):
    response = search(client, token_for(SQL_LAB_USER), {"q": "one", "limit": "0"})

    assert response.status_code == 400
    assert response.json["code"] == "invalid_parameter"
    assert "limit" in response.json["message"]


def test_passes_through_not_ready(client, token_for, code_source_url, not_ready_url):
    code_source_url(not_ready_url)

    response = search(client, token_for(SQL_LAB_USER), {"q": "one"})

    assert response.status_code == 503
    assert response.json["code"] == "not_ready"


def test_unreachable_code_source_is_a_bad_gateway(client, token_for, code_source_url):
    code_source_url("http://nothing-listens-here.invalid:8080")

    response = search(client, token_for(SQL_LAB_USER), {"q": "one"})

    assert response.status_code == 502
    assert response.json["message"]


def test_missing_configuration_is_a_server_error(client, token_for, code_source_url):
    code_source_url(None)

    response = search(client, token_for(SQL_LAB_USER), {"q": "one"})

    assert response.status_code == 500
    assert "SQL_EXAMPLES_CODE_SOURCE_URL" in response.json["message"]


def test_requires_login(client):
    assert client.get(SEARCH, query_string={"q": "one"}).status_code == 401


def test_requires_sql_lab_access(client, token_for):
    response = search(client, token_for(GAMMA_USER), {"q": "one"})

    assert response.status_code == 403
