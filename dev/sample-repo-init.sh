#!/bin/sh
# Turns /seed (dev/sample-repo/, or a test fixture) into a bare git
# repository with a single commit. If /seed-generate.sh is mounted, it runs
# in the copy first, to add files that are impractical to keep in this repo.
#
# With SPREAD_DATES=1, the history is instead a series of backdated commits,
# so the panel shows files changed minutes, days and years ago (008). Each
# .sql file gets an age picked from its path, so it's the same on every
# start; everything else is in the first, oldest commit.
set -e

export GIT_AUTHOR_NAME=dev GIT_AUTHOR_EMAIL=dev@example.com
export GIT_COMMITTER_NAME=dev GIT_COMMITTER_EMAIL=dev@example.com

# Ages in minutes, oldest first: about 2 years, 10 and 4 months, 45, 25, 12,
# 3 and 1 days, 5 hours, 30 minutes and none.
AGES="1051200 432000 172800 64800 36000 17280 4320 1440 300 30 0"

# Commits what's staged, dated `$1` minutes ago.
commit_aged() {
  when="@$(( $(date +%s) - $1 * 60 )) +0000"
  GIT_AUTHOR_DATE="$when" GIT_COMMITTER_DATE="$when" \
    git commit -q --allow-empty -m "Sample examples, $1 minutes old"
}

# The age bucket (0 to 10) for path `$1`.
bucket() {
  echo $(( $(printf %s "$1" | cksum | cut -d' ' -f1) % 11 ))
}

work=$(mktemp -d)
cp -R /seed/. "$work"
cd "$work"
if [ -f /seed-generate.sh ]; then sh /seed-generate.sh; fi

git init -q -b main
if [ "$SPREAD_DATES" = 1 ]; then
  sql=$(find . -path ./.git -prune -o -type f -name '*.sql' -print | sed 's|^\./||')
  git add .
  for f in $sql; do git reset -q -- "$f"; done
  n=0
  for age in $AGES; do
    for f in $sql; do
      if [ "$(bucket "$f")" = $n ]; then git add -- "$f"; fi
    done
    commit_aged "$age"
    n=$((n + 1))
  done
else
  git add .
  git commit -q -m "Sample examples"
fi

rm -rf /srv/git/examples.git
git clone -q --bare "$work" /srv/git/examples.git
echo "Sample repository ready at file:///srv/git/examples.git"
