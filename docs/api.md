# code-source HTTP API

The only contract between `plugin/` and `code-source/`. Change this file in the
same commit as any change to the API.

## Conventions

- Base path: `/api/v1`, except operational endpoints (`/health`,
  `/refresh`), which sit at the root so infrastructure can reach them
  without knowing the API version
- JSON responses; errors as `{ "error": { "code": "...", "message": "..." } }`
- Paths in the repository are canonical: empty for the root, otherwise
  segments joined by `/`, each non-empty, free of NUL, and neither `.` nor
  `..`. No leading or trailing `/`. Non-canonical paths are rejected with
  400 `invalid_path`, never normalised. Paths returned by the API are
  always canonical.
- Repository endpoints answer 503 `not_ready` until the repository has
  been loaded for the first time.

## Endpoints

### GET /health

Purpose: liveness check for operators and orchestration (e.g. Compose,
load balancers). Says nothing about the repository.

Response 200:
```json
{ "status": "ok" }
```

Errors: none. Any other response, or none, means the service is down.

### POST /refresh

Purpose: operational. Brings the served tree up to date with the
repository now, instead of waiting for the next scheduled refresh. Usable
as a webhook target. Not meant for end users' clients.

Synchronous: it answers once the refresh has finished. If one is already
running, it waits for that one and then runs its own.

Response 200:
```json
{ "status": "ok" }
```

Errors: 503 `refresh_failed` when the repository couldn't be loaded; the
cause is in `message`. Previously loaded contents are still served.

### GET /api/v1/tree

Purpose: list one directory of the tree.

Query params:
- `path` (string, optional) – canonical path of a directory. Absent or
  empty means the root.

Response 200:
```json
{
  "path": "orders",
  "description": "Queries over orders and their line items.",
  "entries": [
    { "name": "archive", "path": "orders/archive", "type": "directory", "description": null },
    { "name": "top_products.sql", "path": "orders/top_products.sql", "type": "file", "outline": { ... }, "modified": "2025-03-12T14:03:00Z" }
  ]
}
```

- `type` is `file` or `directory`. Symbolic links and submodules aren't
  listed.
- File entries have an `outline` (see Outlines) and `modified`, the same
  as the file response's. Directory entries have neither.
- `description` describes a folder: the one listed, at the top, and each
  directory entry. It's null when the folder has none. It comes from the
  folder's `README.md` (in any case): its first paragraph that isn't a
  heading, lines joined with spaces, Markdown as written.
- Entries are sorted by `name` in code-point order, and aren't filtered.

Errors: 400 `invalid_path` when `path` isn't canonical; 400
`not_a_directory` when `path` is a file; 404 `not_found` when nothing is
at `path`; 503 `not_ready`.

### GET /api/v1/file

Purpose: read one file's contents.

Query params:
- `path` (string) – canonical path of a file. Absent or empty means the
  root, which is a directory.

Response 200:
```json
{ "path": "orders/top_products.sql", "content": "SELECT ...\n", "outline": { ... }, "modified": "2025-03-12T14:03:00Z" }
```

- `outline` describes what's in the file (see Outlines).
- `modified` is when the file last changed in the served tree, as an
  ISO 8601 timestamp in UTC, to the second: when its newest change
  arrived there, not when that change was first written. Null when it
  can't be worked out.

- `content` is the file's bytes decoded as UTF-8 and otherwise unchanged:
  line endings and any trailing newline are kept. A leading BOM is
  dropped.

Errors: 400 `invalid_path` when `path` isn't canonical; 400 `not_a_file`
when `path` is a directory (including the root); 404 `not_found` when
nothing is at `path` (symbolic links and submodules count as nothing,
since the tree doesn't list them); 413 `too_large` when the file is over
1 MiB (1,048,576 bytes); 422 `not_text` when the file isn't valid UTF-8;
503 `not_ready`.

### GET /api/v1/search

Purpose: find files by words in their paths and in their parts' names
and descriptions, and by what their outlines say they refer to, call and
use.

A file's *parts* are pieces of it that may have a name and a
description, as its language's analyzer finds them. For SQL, they're
its queries (with descriptions, but no names) and their CTEs (see
Outlines: `sql`). Files in languages that aren't analysed have none.

A word is a run of letters and digits. A change from lower to upper case
also starts a new word (`newCustomers` → `new`, `customers`;
`HTTPServer` → `http`, `server`), and case is ignored. Paths, parts'
names and descriptions, and `q` are split the same way. A file matches
when every word of `q` matches a word somewhere in it: in its name, a
part's name, a part's description or its folders. The last word matches
as a prefix, the others whole. Different words may match in different
places.

Each word of `q` counts by the best place it matched: the file name,
then a part's name, then a part's description, then the folders, with
whole words above prefixes in each. A word that matches in a better
place always counts for more. A file ranks by the sum over the words.
Among files that rank the same, those whose parts' descriptions are more
about the words of `q` rank higher (BM25: words rarer across
descriptions count for more, and so do words said more often, or in
fewer words). Only whole words count for this, so the last word counts
once it's complete. Then, with `reference`, files that wrote it in full
(see `unqualified`). Then path order.

Query params:
- `q` (string, optional) – the search text. Absent, empty or with no
  words, and no `reference`, `function` or `keyword` given, means no
  results.
- `limit` (integer, optional) – most results to return, 1 to 100. Default
  20.
- `ext` (string, optional, repeatable) – only files whose name ends in
  `.<ext>`, ignoring case. Given without the dot (`ext=txt`). Several
  mean any of them.
- `reference` (string, optional, repeatable) – only files whose outline
  has this reference (see Outlines: `references`), such as a table.
  Names are compared part by part from the right, `.` separating parts,
  ignoring case and quotes; parts both names have must be equal.
  `orders` finds files that wrote `orders`, `sales.orders` or
  `iceberg.sales.orders`. Several must all match.
- `unqualified` (`include` or `exclude`, optional) – whether a
  `reference` also finds files that wrote it with fewer parts than given
  (`sales.orders` finding a file that wrote `orders`). Default
  `include`. Files that wrote every part given rank above those that
  didn't, among results that `q` scores the same.
- `function` (string, optional, repeatable) – only files whose outline
  has this function, ignoring case. Several must all match.
- `keyword` (string, optional, repeatable) – only files whose outline
  has this keyword, ignoring case and runs of spaces (`group  by`).
  Several must all match.

Response 200:
```json
{
  "total": 1,
  "results": [
    {
      "path": "customers/new_customers_by_month.sql",
      "type": "file",
      "score": 7.0,
      "matches": [
        { "start": 0, "end": 4 },
        { "start": 10, "end": 13 },
        { "start": 14, "end": 18 }
      ],
      "parts": [
        {
          "range": { "start": 4, "end": 8, "startColumn": 6, "endColumn": 1 },
          "name": "new_customers",
          "nameMatches": [{ "start": 0, "end": 3 }, { "start": 4, "end": 8 }],
          "description": "One row per customer, from their first order.",
          "descriptionMatches": [{ "start": 12, "end": 16 }]
        }
      ],
      "outline": { ... },
      "modified": "2025-03-12T14:03:00Z"
    }
  ]
}
```

- That's the response for `q=new cust`.
- `results` are ranked best first, at most `limit` of them. `total` is how
  many files matched in all.
- `type` is always `file` for now.
- `score` is only meaningful for comparing results in the same response.
- `matches` are the parts of `path` that matched a word of `q`, as
  `[start, end)` offsets in code points, in order and not overlapping.
  Empty when `q` has no words.
  For a prefix match only the matched prefix is included.
- `parts` are the file's parts whose name or description a word of `q`
  matched, in the order they're written in the file. Empty when `q` has
  no words, or nothing matched in a part.
  - `range` is the part's range, as its language's outline gives it
    (for SQL, a query's or a CTE's `range`), so a client can find the
    part there.
  - `name` and `description` are the part's, or null when it has none
    (a SQL query has no name).
  - `nameMatches` and `descriptionMatches` are where words of `q`
    matched in `name` and `description`, like `matches`. Empty when
    nothing matched there.
- `outline` and `modified` are the file's, as its folder's listing gives
  them (see GET /api/v1/tree).
- Paths with any segment starting with `.` are never returned, though
  listings show them. Symbolic links and submodules aren't returned.

Errors: 400 `invalid_parameter` when `q` is over 500 characters, `limit`
isn't a whole number from 1 to 100, an `ext` is empty or contains `.`
or `/`, a `reference` is over 500 characters or has an empty part, a
`function` or `keyword` is blank or over 500 characters, or
`unqualified` is neither `include` nor `exclude`; `message` names the
parameter. 503 `not_ready`.

### GET /api/v1/suggestions

Purpose: values that a search's outline filters (`reference`,
`function`, `keyword`) can take, starting with what a client has typed,
with how many files each would find. For clients that help users build
a search.

Query params:
- `q` (string, optional) – what's typed, matched as a prefix ignoring
  case (and, for keywords, runs of spaces). Absent or blank means no
  suggestions.
- `field` (string, optional, repeatable) – which of `reference`,
  `function` and `keyword` to suggest values of. Absent means all three.
- `ext` (string, optional, repeatable) – as for search: counts only
  files with one of these extensions, and leaves out values only other
  files have.
- `limit` (integer, optional) – most suggestions to return, 1 to 100.
  Default 10.

Response 200 (for `q=ord`):
```json
{
  "suggestions": [
    { "field": "keyword", "value": "ORDER BY", "files": 9 },
    { "field": "reference", "value": "orders", "files": 3 },
    { "field": "reference", "value": "sales.orders", "files": 3 }
  ]
}
```

- `field` is the search parameter the value goes in, so a client can
  pass `<field>=<value>` straight to search.
- `value` is in the form search compares: references with quotes
  removed, lower case, parts joined by `.`; functions and keywords upper
  case, keywords with single spaces.
- A reference is suggested by each name it can be searched by: a file
  that wrote `iceberg.sales.orders` gives `orders`, `sales.orders` and
  `iceberg.sales.orders`, each suggested when it starts with `q`.
- `files` is the `total` that a search for only that value, with the
  same `ext` and `unqualified` at its default, gives.
- Ordered by `files`, most first; then by `field`, in the order above;
  then by `value` in code-point order. At most `limit` of them.
- Files under a dot-entry don't count, as search never returns them.

Errors: 400 `invalid_parameter` when `q` is over 500 characters, a
`field` isn't one of the three, an `ext` is empty or contains `.` or
`/`, or `limit` isn't a whole number from 1 to 100; `message` names the
parameter. 503 `not_ready`.

## Outlines

What's in a file, worked out when the repository is loaded or refreshed.
Every file in a listing or file response has one. An outline never makes
a request fail: what can't be worked out is left out, and `partial` says
so.

For a file that starts:

```sql
-- Revenue per month, with a running total for each year.

-- One row per month.
WITH monthly AS (
```

```json
{
  "language": "sql",
  "partial": false,
  "keywords": ["GROUP BY", "ORDER BY", "OVER", "PARTITION BY", "WITH"],
  "functions": ["ROUND", "SUM"],
  "references": ["cleaned_sales_data"],
  "sql": {
    "queries": [
      {
        "range": { "start": 4, "end": 15, "startColumn": 1, "endColumn": 20 },
        "description": "Revenue per month, with a running total for each year.",
        "descriptionRange": { "start": 1, "end": 1 },
        "keywords": ["ORDER BY", "OVER", "PARTITION BY", "WITH"],
        "functions": ["ROUND", "SUM"],
        "tables": [],
        "uses": ["monthly"],
        "recursive": false,
        "ctes": [
          {
            "name": "monthly",
            "range": { "start": 4, "end": 8, "startColumn": 6, "endColumn": 1 },
            "description": "One row per month.",
            "descriptionRange": { "start": 3, "end": 3 },
            "keywords": ["GROUP BY"],
            "functions": ["SUM"],
            "tables": ["cleaned_sales_data"],
            "uses": []
          }
        ]
      }
    ]
  }
}
```

### Generic fields

Every outline has these, whatever the file's language. A client that
reads only these can use any outline.

- `language` – the file's language, or null if code-source doesn't
  recognise it. `sql` for files named `*.sql` (in any case).
- `partial` – true when some of the file couldn't be analysed, so the
  other fields may be incomplete.
- `keywords` – the language's keywords the file uses, upper case,
  distinct and sorted. Keywords in comments and strings, and keywords so
  common they say nothing about a file, aren't included.
- `functions` – the functions the file calls, upper case, without any
  qualifier, distinct and sorted.
- `references` – things the file refers to that are defined elsewhere,
  as written, distinct and sorted. For SQL, the real tables its queries
  read.

### Language-specific fields

When code-source analyses a language, the outline also has a field named
after the language (e.g. `sql`), holding detail in that language's own
shape. Generic field names are never language names. A file whose
language isn't analysed has no such field.

#### `sql`

- `queries` – the file's queries, in order. Other statements (DDL,
  `INSERT`, ...) aren't listed. A query has no name; its position and
  `range` identify it.
- On a query:
  - `range` – where it is: the lines it occupies (`start` and `end`),
    and the columns of its first and last characters on those lines
    (`startColumn` and `endColumn`). All 1-based and inclusive; columns
    count code points. A query's ends before its `;`.
  - `description` – from the comment above it, or null (see
    Descriptions).
  - `descriptionRange` – the lines of the comment block `description`
    came from, like `range`. Null when `description` is.
  - `ctes` – the CTEs of its `WITH` clause, in order. CTEs nested
    deeper aren't listed; they count as part of the CTE or query that
    contains them.
  - `keywords`, `functions` and `tables` – as for the file, but only
    those in the query outside its CTEs.
  - `uses` – the names of its CTEs it reads from, sorted.
  - `recursive` – true when its `WITH` says `RECURSIVE`.
- On a CTE: `name`, `range` (as for a query: from its name, quotes
  included, to its closing `)`), `description` and `descriptionRange`
  (see Descriptions), and `keywords`, `functions`, `tables` and `uses`, for
  that CTE alone. A CTE's `uses` names other CTEs of the same query.
- Tables are listed as written (`orders`, `sales.orders`,
  `iceberg.sales.orders`), with aliases resolved and CTEs left out.
- Superset Jinja templating (`{{ }}`, `{% %}`, `{# #}`) is ignored.
- Files are parsed in the dialect code-source is configured with.

##### Descriptions

- A query's description is the nearest comment block above it, after
  the previous statement.
- A CTE's description is the comment block directly above the line its
  name is on, with no blank line between. So when a query's first line
  names a CTE (`WITH monthly AS (`), a comment directly above that line
  describes the CTE, and the query's must be separated from the code by
  a blank line, as in the example above.
- A comment block is consecutive lines of comments (`--` or `/* */`)
  with nothing else on them. Comments after code on a line don't count.
- The text has comment markers removed and wrapped lines joined with
  spaces. Paragraphs, separated by an empty comment line, are kept
  apart by a blank line.

<!--
Template for each endpoint:

### GET /api/v1/...

Purpose: ...

Query params:
- `name` (type, required?) – ...

Response 200:
```json
{ }
```

Errors: 404 when ..., 400 when ...
-->
