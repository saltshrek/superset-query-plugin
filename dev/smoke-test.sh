#!/usr/bin/env bash
# Smoke test for the running dev environment (docs/features/001-dev-environment.md,
# 002-backend-path.md, 003-browse-tree.md, 004-use-a-file.md,
# 006-filename-search.md, 013-search-by-outline.md and
# 014-search-descriptions-and-ctes.md).
# Run after `docker compose -f dev/compose.yaml up` has finished starting:
#
#   dev/smoke-test.sh
#
# It can't see the panel itself; check that by eye in SQL Lab.
set -euo pipefail

compose=(docker compose -f "$(dirname "$0")/compose.yaml")
superset=${SUPERSET_URL:-http://localhost:8088}

fail() { echo "FAIL: $*" >&2; exit 1; }

# Prints an access token for a Superset user.
login() {
  curl -fsS -X POST "$superset/api/v1/security/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\",\"provider\":\"db\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}

# Prints the HTTP status of a GET as the given token's user.
status_of() {
  curl -sS -o /dev/null -w '%{http_code}' "$superset$1" -H "Authorization: Bearer $2"
}

# Creates a user with one role unless it exists already.
ensure_user() {
  "${compose[@]}" exec -T superset superset fab create-user \
    --username "$1" --password "$1" --role "$2" \
    --firstname "$1" --lastname Smoke --email "$1@example.com" >/dev/null 2>&1 || true
}

echo "code-source answers /health from inside the Superset container"
health=$("${compose[@]}" exec -T superset curl -fsS http://code-source:8080/health) \
  || fail "code-source /health not reachable from superset"
[[ $health == *'"status":"ok"'* ]] || fail "unexpected /health body: $health"

echo "Superset has loaded the plugin from plugin/"
token=$(login admin admin) || fail "could not log in to Superset at $superset"
extensions=$(curl -fsS "$superset/api/v1/extensions/" -H "Authorization: Bearer $token") \
  || fail "could not list extensions"
remote_entry=$(python3 -c '
import json, sys
for ext in json.load(sys.stdin)["result"]:
    if ext["id"] == "quillfern.sql-examples":
        print(ext["remoteEntry"])
' <<<"$extensions")
[[ -n $remote_entry ]] || fail "quillfern.sql-examples not in $extensions"

echo "Superset serves the plugin's frontend bundle"
curl -fsS -o /dev/null "$superset$remote_entry" -H "Authorization: Bearer $token" \
  || fail "could not fetch $remote_entry"

health_route=/extensions/quillfern/sql-examples/code-source/health

echo "The plugin's backend reports code-source healthy"
health=$(curl -fsS "$superset$health_route" -H "Authorization: Bearer $token") \
  || fail "$health_route failed for admin"
[[ $health == *'"status":"ok"'* ]] || fail "unexpected $health_route body: $health"

tree_route=/extensions/quillfern/sql-examples/code-source/tree

echo "The plugin's backend lists the sample repository"
tree=$(curl -fsS "$superset$tree_route" -H "Authorization: Bearer $token") \
  || fail "$tree_route failed for admin"
[[ $tree == *'"path":"orders"'* ]] || fail "sample repo's orders/ not in $tree"

file_route="/extensions/quillfern/sql-examples/code-source/file?path=orders/top_products.sql"

echo "The plugin's backend returns a sample file's contents"
file=$(curl -fsS "$superset$file_route" -H "Authorization: Bearer $token") \
  || fail "$file_route failed for admin"
python3 -c '
import json, sys
result = json.load(sys.stdin)["result"]
assert result["path"] == "orders/top_products.sql", result
assert result["content"] == open(sys.argv[1]).read(), "contents differ"
' "$(dirname "$0")/sample-repo/orders/top_products.sql" <<<"$file" \
  || fail "unexpected $file_route body: $file"

search_route="/extensions/quillfern/sql-examples/code-source/search?q=churn+rate&ext=sql"

echo "The plugin's backend finds a sample file by words in its name"
search=$(curl -fsS "$superset$search_route" -H "Authorization: Bearer $token") \
  || fail "$search_route failed for admin"
python3 -c '
import json, sys
paths = [r["path"] for r in json.load(sys.stdin)["result"]["results"]]
assert paths == ["customers/churn/monthlyChurnRate.sql"], paths
' <<<"$search" || fail "unexpected $search_route body: $search"

suggestions_route="/extensions/quillfern/sql-examples/code-source/suggestions?q=cleaned_sal&ext=sql"

echo "The plugin's backend suggests a sample table, and finds a sample file by it"
suggestions=$(curl -fsS "$superset$suggestions_route" -H "Authorization: Bearer $token") \
  || fail "$suggestions_route failed for admin"
python3 -c '
import json, sys
suggested = json.load(sys.stdin)["result"]["suggestions"]
assert {"field": "reference", "value": "cleaned_sales_data"} in [
    {"field": s["field"], "value": s["value"]} for s in suggested
], suggested
' <<<"$suggestions" || fail "unexpected $suggestions_route body: $suggestions"
by_table_route="/extensions/quillfern/sql-examples/code-source/search?reference=cleaned_sales_data&ext=sql&limit=100"
by_table=$(curl -fsS "$superset$by_table_route" -H "Authorization: Bearer $token") \
  || fail "$by_table_route failed for admin"
python3 -c '
import json, sys
paths = [r["path"] for r in json.load(sys.stdin)["result"]["results"]]
assert "orders/top_products.sql" in paths, paths
' <<<"$by_table" || fail "unexpected $by_table_route body: $by_table"

by_cte_route="/extensions/quillfern/sql-examples/code-source/search?q=ranked&ext=sql"

echo "The plugin's backend finds a sample file by a CTE's name, with that CTE among its parts"
by_cte=$(curl -fsS "$superset$by_cte_route" -H "Authorization: Bearer $token") \
  || fail "$by_cte_route failed for admin"
python3 -c '
import json, sys
results = {r["path"]: r for r in json.load(sys.stdin)["result"]["results"]}
found = results.get("video_games/publisher_market_share.sql")
assert found, list(results)
assert "ranked" in [p["name"] for p in found["parts"]], found["parts"]
' <<<"$by_cte" || fail "unexpected $by_cte_route body: $by_cte"

echo "code-source isn't published to the host"
# `compose port` prints ":0" (or nothing) for a port that isn't published.
published=$("${compose[@]}" port code-source 8080 2>/dev/null || true)
[[ -z $published || $published == *:0 ]] \
  || fail "code-source is published to the host at $published"

echo "Only users with SQL Lab access can use the backend routes"
ensure_user smoke_gamma Gamma
ensure_user smoke_sqllab sql_lab
for route in "$health_route" "$tree_route" "$file_route" "$search_route" "$suggestions_route"; do
  code=$(status_of "$route" "$(login smoke_gamma smoke_gamma)")
  [[ $code == 403 ]] || fail "Gamma-only user got $code from $route, expected 403"
  code=$(status_of "$route" "$(login smoke_sqllab smoke_sqllab)")
  [[ $code == 200 ]] || fail "sql_lab user got $code from $route, expected 200"
done

echo "OK"
