#!/usr/bin/env bash
# Checks the production image (docs/features/011-production-image.md):
# builds it, then runs it as k8s would (non-root, read-only root
# filesystem, writable DATA_DIR and /tmp) against a fixture repository.
#
#   code-source/image-test.sh
#
# Needs Docker, git, curl and python3. IMAGE=<tag> tests an existing image
# instead of building one.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
image=${IMAGE:-}
if [[ -z $image ]]; then
  image=code-source:image-test
  echo "Building the image"
  docker build -q -t "$image" "$here" >/dev/null
fi

fail() { echo "FAIL: $*" >&2; exit 1; }

work=$(mktemp -d)
container=
cleanup() {
  [[ -n $container ]] && docker rm -f "$container" >/dev/null 2>&1
  rm -rf "$work"
}
trap cleanup EXIT

# A bare repository with one SQL file, served from a read-only mount.
git init -q -b main "$work/seed"
cat >"$work/seed/monthly.sql" <<'SQL'
-- Revenue per month.
SELECT month, SUM(revenue) AS revenue
FROM sales
GROUP BY month
SQL
git -C "$work/seed" add .
git -C "$work/seed" -c user.name=test -c user.email=test@example.com commit -qm seed
git clone -q --bare "$work/seed" "$work/examples.git"
chmod -R a+rX "$work/examples.git"

container=$(docker run -d \
  --read-only \
  --tmpfs /tmp:mode=1777 \
  --tmpfs /var/lib/code-source:mode=1777 \
  -v "$work/examples.git:/srv/git/examples.git:ro" \
  -e REPO_URL=file:///srv/git/examples.git \
  -p 127.0.0.1::8080 \
  "$image")
port=$(docker port "$container" 8080 | head -1 | sed 's/.*://')
api=http://127.0.0.1:$port

echo "The container serves the repository with a read-only root filesystem"
for _ in $(seq 60); do
  [[ $(curl -s -o /dev/null -w '%{http_code}' "$api/api/v1/tree") == 200 ]] && break
  [[ $(docker inspect -f '{{.State.Running}}' "$container") == true ]] \
    || { docker logs "$container" >&2; fail "the container exited"; }
  sleep 1
done
curl -fsS "$api/health" >/dev/null || { docker logs "$container" >&2; fail "/health failed"; }
tree=$(curl -fsS "$api/api/v1/tree") || { docker logs "$container" >&2; fail "never became ready"; }
[[ $tree == *'"path":"monthly.sql"'* ]] || fail "monthly.sql not in $tree"

echo "A SQL file has a whole outline, so the analyzer ran"
curl -fsS "$api/api/v1/file?path=monthly.sql" | python3 -c '
import json, sys
outline = json.load(sys.stdin)["outline"]
assert outline["language"] == "sql", outline
assert outline["partial"] is False, outline
assert outline["references"] == ["sales"], outline
' || { docker logs "$container" >&2; fail "unexpected outline for monthly.sql"; }

echo "It runs as a non-root numeric user"
uid=$(docker exec "$container" id -u)
[[ $uid != 0 ]] || fail "runs as root"
user=$(docker inspect -f '{{.Config.User}}' "$image")
[[ $user =~ ^[0-9]+(:[0-9]+)?$ ]] || fail "USER is '$user', not numeric"

echo "The analyzer has the sqlglot version the tests use"
expected=$(sed -n 's/^sqlglot==//p' "$here/analyzers/requirements.txt")
actual=$(docker exec "$container" /opt/code-source/analyzers/venv/bin/python -c \
  'import importlib.metadata as m; print(m.version("sqlglot"))')
[[ $actual == "$expected" ]] || fail "sqlglot is $actual, expected $expected"

echo "It exits promptly on SIGTERM"
start=$(date +%s)
docker stop -t 30 "$container" >/dev/null
elapsed=$(($(date +%s) - start))
(( elapsed <= 5 )) || fail "took ${elapsed}s to stop"

echo "OK"
