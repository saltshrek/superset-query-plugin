# Sample examples

Example queries for the dev environment, over the tables in Superset's
example data (loaded into the "examples" database on first start). They
use SQLite's dialect, which is what the dev examples database is.

It deliberately includes nested folders, non-SQL files, a dot-folder, and
names in snake_case, camelCase and kebab-case, so the panel's filtering
and search can be seen at work.

Comments describe queries and CTEs, as code-source reads them (see
docs/features/007-file-outlines.md). A comment above a query describes
it. A comment directly above a CTE's name describes the CTE, so when a
query starts `WITH name AS (`, its own comment is separated from the code
by a blank line, with the CTE's comment in between. A folder's README
describes the folder. `sales/` and `video_games/` show all of these.
