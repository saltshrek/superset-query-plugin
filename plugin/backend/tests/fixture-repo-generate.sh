#!/bin/sh
# Adds files to the backend test fixture repo (tests/fixture-repo) that
# would be impractical to keep in this repository. Runs in a copy of it.
set -e

# Just over code-source's 1 MiB limit (1,048,576 bytes): too_large.
head -c 1048577 /dev/zero | tr '\0' 'x' > queries/too_large.sql
# "select 'café'" in Latin-1, which isn't valid UTF-8: not_text.
printf "select 'caf\351'\n" > queries/latin1.sql
