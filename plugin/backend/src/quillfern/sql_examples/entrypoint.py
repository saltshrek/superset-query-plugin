"""Backend part of the SQL Examples extension.

Superset imports this module at startup. The browser talks only to the
routes here, and they call code-source on the private network
(docs/features/002-backend-path.md). There's one route per code-source
operation, not a generic proxy, so SQL Lab users can reach only what the
panel needs.
"""

import functools
from typing import Any, Callable

import requests
from flask import Response, current_app, jsonify, make_response, request
from flask_appbuilder.api import expose, safe
from flask_jwt_extended import verify_jwt_in_request
from flask_login import current_user
from superset_core.rest_api.api import RestApi
from superset_core.rest_api.decorators import api

CODE_SOURCE_URL_CONFIG_KEY = "SQL_EXAMPLES_CODE_SOURCE_URL"
CODE_SOURCE_TIMEOUT_SECONDS = 5

# code-source errors the panel can act on, passed through with their status.
# Anything else from code-source is a 502.
FORWARDED_ERROR_STATUSES = {400, 404, 413, 422, 503}


class CodeSourceNotConfigured(Exception):
    """SQL_EXAMPLES_CODE_SOURCE_URL isn't set."""


class CodeSourceUnavailable(Exception):
    """code-source couldn't be called, or didn't answer with JSON."""


def call_code_source(
    path: str, params: dict[str, list[str]] | None = None
) -> tuple[int, Any]:
    """GET `path` on code-source; returns (status, parsed JSON body).

    Raises CodeSourceNotConfigured if the base URL isn't set, and
    CodeSourceUnavailable if code-source can't be reached or its answer
    isn't JSON.
    """
    base_url = current_app.config.get(CODE_SOURCE_URL_CONFIG_KEY)
    if not base_url:
        raise CodeSourceNotConfigured()
    try:
        upstream = requests.get(
            f"{base_url.rstrip('/')}{path}",
            params=params,
            timeout=CODE_SOURCE_TIMEOUT_SECONDS,
        )
        return upstream.status_code, upstream.json()
    except (requests.RequestException, ValueError) as ex:
        raise CodeSourceUnavailable() from ex


def requires_sql_lab_read(f: Callable[..., Response]) -> Callable[..., Response]:
    """Allow the call only for users who can use SQL Lab.

    Same authentication as FAB's @protect() (session cookie, then JWT), but
    it checks SQL Lab's own read permission instead of one named after this
    API. We can't get that from @protect() by setting class_permission_name
    to "SQLLab": registering the API would then make FAB delete every other
    permission on SQLLab from all roles.
    """

    @functools.wraps(f)
    def wrapper(self: RestApi, *args: Any, **kwargs: Any) -> Response:
        if not current_user.is_authenticated:
            verify_jwt_in_request()
        if current_app.appbuilder.sm.has_access("can_read", "SQLLab"):
            return f(self, *args, **kwargs)
        return self.response_403()

    return wrapper


@api(
    id="code_source",
    name="code-source",
    description="Calls code-source on behalf of the SQL Examples panel",
    resource_name="code-source",
)
class CodeSourceApi(RestApi):
    # Our own name, so registering this API never touches other views'
    # permissions. Access is checked by requires_sql_lab_read.
    class_permission_name = "SqlExamplesCodeSource"

    @expose("/health", methods=("GET",))
    @requires_sql_lab_read
    @safe
    def health(self) -> Response:
        try:
            status, body = call_code_source("/health")
        except CodeSourceNotConfigured:
            return self._not_configured()
        except CodeSourceUnavailable:
            return self.response(502, message="code-source is unreachable")
        if status != 200 or not isinstance(body, dict) or body.get("status") != "ok":
            return self.response(502, message="code-source is not healthy")
        return self.response(200, result={"status": "ok"})

    @expose("/tree", methods=("GET",))
    @requires_sql_lab_read
    @safe
    def tree(self) -> Response:
        """One directory of the repository (docs/api.md: GET /api/v1/tree)."""
        return self._forward("/api/v1/tree", "path")

    @expose("/file", methods=("GET",))
    @requires_sql_lab_read
    @safe
    def file(self) -> Response:
        """One file's contents (docs/api.md: GET /api/v1/file)."""
        return self._forward("/api/v1/file", "path")

    @expose("/search", methods=("GET",))
    @requires_sql_lab_read
    @safe
    def search(self) -> Response:
        """Files found by words in their paths and by their outlines
        (docs/api.md: GET /api/v1/search)."""
        return self._forward(
            "/api/v1/search",
            "q",
            "limit",
            "ext",
            "reference",
            "function",
            "keyword",
            "unqualified",
        )

    @expose("/suggestions", methods=("GET",))
    @requires_sql_lab_read
    @safe
    def suggestions(self) -> Response:
        """Values search can filter by, for what's typed
        (docs/api.md: GET /api/v1/suggestions)."""
        return self._forward("/api/v1/suggestions", "q", "field", "ext", "limit")

    def _forward(self, code_source_path: str, *param_names: str) -> Response:
        """Calls a repository endpoint with the request's values for
        `param_names` (each repeated as given), and maps the answer as the
        feature specs (003, 004, 006, 013) describe."""
        params = {
            name: request.args.getlist(name)
            for name in param_names
            if name in request.args
        }
        try:
            status, body = call_code_source(code_source_path, params)
        except CodeSourceNotConfigured:
            return self._not_configured()
        except CodeSourceUnavailable:
            return self.response(502, message="code-source is unreachable")
        if status == 200 and isinstance(body, dict):
            return self.response(200, result=body)
        error = body.get("error") if isinstance(body, dict) else None
        if status in FORWARDED_ERROR_STATUSES and isinstance(error, dict):
            # Not self.response(): its status parameter is also named `code`.
            body = {"message": error.get("message"), "code": error.get("code")}
            return make_response(jsonify(body), status)
        return self.response(502, message="code-source answered unexpectedly")

    def _not_configured(self) -> Response:
        return self.response(
            500, message=f"{CODE_SOURCE_URL_CONFIG_KEY} is not configured"
        )
