# 007: File outlines from code-source

**Status:** done

## Purpose

Let code-source say what's in a file, not only what the file contains:
its parts and what they're for, how those parts depend on each other,
what they read from, and what language features they use. For SQL
examples, four things matter most:

- **Tables**: which real tables an example reads. An analyst often knows
  the table before anything else ("show me how people query
  `order_items`"), so tables are a strong way into the repository. They
  are also what context-aware ranking would match against: the tables
  in the analyst's editor or schema.
- **CTEs**: the "data shapes" an analyst may want to find or build from.
- **Keywords**: the SQL constructs an example uses (`QUALIFY`, `PIVOT`,
  `OVER`, `RECURSIVE`, `UNNEST`). Analysts often look for "an example of
  how to do X" rather than "an example over table Y", and the keywords
  are the cheapest handle on X.
- **Functions**: the functions an example calls (`ROW_NUMBER`,
  `LISTAGG`, `APPROX_DISTINCT`). Often just as much what "how to do X"
  means as keywords, but a different thing: identifiers that are called,
  not part of the grammar, and they vary more between dialects. Clients
  can present keywords and functions together if that suits them.

008 shows this in the tree, and 009 lets analysts use a single CTE.
Search and future agent clients can use the same information.

Curators shouldn't need to do anything new to get value from this. A
file gets an outline from its code alone. A few optional conventions
(below) make it better, and they're habits contributors mostly have
already.

### This is also an experiment

SQL is analysed with sqlglot (Python), run by code-source (Kotlin) as a
subprocess. No other part of the project does this yet. Besides
delivering outlines, this feature should show what that pattern costs,
so the decision to keep it (or not) rests on evidence. Record what we
find under Implementation notes, on each of:

- **Call cost**: starting Python and importing sqlglot, per refresh and
  as the repository grows.
- **Failure handling**: a crash, a hang, bad output, or a single file
  that breaks the analyzer, and how each shows up to API clients and
  operators.
- **Packaging**: what Python adds to the image's size and build, and
  how its dependencies are pinned.
- **Dev loop**: the auto-reloading `code-source` service, and editing
  the Python side while it runs.
- **Tests**: `./gradlew test` needing real Python and sqlglot, locally
  and in containers.
- **Two codebases**: reviewing, structuring and versioning a Kotlin and
  a Python side with a contract between them.

## Behaviour

What an API client gets. The shape is sketched under API below.

- **Every file read or listed through the API comes with an outline**:
  in the file response, and on file entries in tree listings, so a
  client can decorate a tree without fetching each file. It has two
  levels:
  - **Generic fields**, the same for every language, describing the
    whole file: its `language`, whether the outline is `partial`, and
    its `keywords`, `functions` and `references`. A client
    that reads only these (an agent, search) still gets a useful
    outline, whatever the language.
  - **A language-specific object**, keyed by the value of `language`
    (`"sql": { ... }`), when code-source has an analyzer for it. Its
    shape is the language's own. Generic field names are never language
    names, so the two can't clash.
- **`language`** is the file's language when code-source recognises it
  (`sql` for `.sql` files), otherwise null. A recognised language with
  no analyzer gets the generic fields it can, and no language-specific
  object.
- **SQL: queries and their CTEs.** The `sql` object lists the file's
  queries in order. A query has no name; it's identified by its
  position and its line range, and it has a description. Each query
  lists the CTEs in its `WITH` clause, each with its name, line range
  and description. Each query
  and CTE says which of that query's CTEs it uses, so CTE names only
  need to be unique within their query, as in SQL. Only top-level CTEs
  are listed: a `WITH` nested inside a CTE or subquery is part of the
  thing that contains it.
- **Other SQL statements** (DDL, `INSERT`, `CREATE TABLE AS`, ...) aren't
  listed as queries, and don't make the outline partial. Code-source
  doesn't analyse them, as it doesn't analyse languages it has no
  analyzer for. The shape allows adding them later.
- **Tables a file reads**: every real table the file's queries read, as
  written in the SQL (e.g. `iceberg.sales.orders`, `sales.orders` or
  `orders`).
  - References to a CTE are uses of that CTE, not tables, so they are
    never listed as tables.
  - Aliases are resolved to the table they stand for.
  - Each query and CTE lists the tables it reads directly, so a client
    can tell which part of an example uses which table.
  - At the generic level they are `references`: things the file refers
    to that are defined elsewhere, like imports in other languages.
- **Keywords a file uses**: the distinct keywords used anywhere in the
  file, in upper case and sorted. Keywords inside comments, string
  literals and Superset Jinja aren't counted, and nor are keywords so
  common they say nothing about a file (`SELECT`, `FROM`, `WHERE`,
  `AS`, `AND`, `OR`, `ON` and the like). Each query and CTE lists the
  keywords it uses directly.
- **Functions a file calls**: the distinct functions called anywhere in
  the file, sorted, under the same rules as keywords. Names are as the
  dialect spells them, in upper case for SQL, without any qualifier (a
  schema or package prefix). Built-in and user-defined functions are
  both listed. Each query and CTE lists the functions it calls directly.
- **Search by references, functions and keywords**: a client can find
  the files that read a given table, call a given function or use a
  given keyword, alone or together with a search by path words. This
  makes "examples that use this table" possible, for the panel and for
  context-aware ranking.
  - Names are compared part by part from the right, `.` separating the
    parts, ignoring case and quotes. Parts both names have must be
    equal, so `hr.orders` never matches `sales.orders`.
  - Asking for fewer parts than a file wrote always matches: `orders`
    finds `sales.orders` and `iceberg.sales.orders`.
  - Asking for more parts than a file wrote (`sales.orders` when the
    file says `orders`) matches by default, since examples are often
    written with the schema already selected, so the file probably
    means some schema's `orders`. Among results that otherwise rank the
    same, files that wrote the name in full come first. A client can
    exclude such files instead.
  - Several values, of one kind or several, must all match.
- **Folder descriptions in the tree**: directory entries carry a
  `description`, null when the folder has none. A listing also carries
  the listed folder's own `description`, so the root folder's README is
  available too.
- **Files have no description of their own.** A file may hold several
  queries, and each has its own. A file-level description would repeat
  the single query's, or say no more than the outline already does.
- **Files that can't be fully parsed** still get an outline, with as
  much as could be extracted, such as keywords. The
  outline says that it's partial. An outline never makes reading a file
  fail, including when the analyzer itself fails.

Conventions code-source reads. All of them are optional, and the
sample repository demonstrates them:

- **A query's description** is the comment block above it, between it
  and the previous statement (or the start of the file).
- **A CTE's description** is the comment block directly above the line
  its name is on, with no blank line between.
- So when a query's first line names its first CTE (`WITH monthly AS
  (`), a comment directly above that line describes the CTE. To
  describe the query, leave a blank line between its comment and the
  code; a CTE comment can then sit between them:
  ```sql
  -- Revenue per month, with a running total for each year.

  -- One row per month.
  WITH monthly AS (
  ```
  When the first line doesn't name a CTE, the comment above is the
  query's, blank line or not.
- A comment block is consecutive comment lines (`--` or `/* */`); a
  blank line ends it. Only the nearest block counts, or the nearest two
  when the first describes a CTE. Comments after code on the same line,
  and Superset's Jinja comments (`{# #}`), never count. A comment
  between statements belongs to the statement after it.
- Description text has its comment markers removed and wrapped lines
  joined with spaces. An empty comment line separates paragraphs, which
  are kept apart by a blank line.
- A folder's `README.md` (in any case) describes the folder: its first
  paragraph that isn't a heading, with lines joined and any Markdown
  left as written.
- Names are useful as they are: `snake_case` and similar are split into
  words wherever names are shown or searched.

Dialects to support: Trino (querying Iceberg tables) and Oracle.

## Acceptance criteria

- [x] Every file read through the API, and every file entry in a tree
      listing, comes with an outline that has the generic fields,
      whatever its language. The two outlines are the same
- [x] A SQL file's outline lists its queries, and each query's CTEs,
      with line ranges and which CTEs each query and CTE uses
- [x] Two queries in one file can have CTEs with the same name
- [x] A SQL file's outline lists the real tables it reads, with CTE
      names excluded and aliases resolved, for the file and for each
      query and CTE
- [x] A SQL file's outline lists the keywords it uses, for the file and
      for each query and CTE, ignoring comments, strings, Jinja and
      ubiquitous keywords
- [x] A SQL file's outline lists the functions it calls, for the file
      and for each query and CTE, separately from its keywords
- [x] Statements other than queries are left out, without making the
      outline partial
- [x] A client can find the files that read a given table, whether the
      file names it in full (`catalog.schema.table`) or in part, and can
      choose whether files naming it with fewer parts than asked count
- [x] A client can find the files that call given functions or use given
      keywords, ignoring case
- [x] Reference, function and keyword filters combine with each other,
      with path words and with extensions, all having to match
- [x] Descriptions come from the conventions above, for queries, CTEs
      and folders, including the root folder
- [x] Representative Trino and Oracle examples are outlined correctly,
      including ones that use Superset's Jinja templating
- [x] A file that can't be parsed gets a partial outline, not an error
- [x] If the analyzer crashes, hangs or returns unusable output, the
      refresh still completes, listings and file contents are
      unaffected, SQL files get partial outlines, and the cause is
      logged
- [x] Outlines and descriptions reflect the repository after a refresh

## Out of scope

- Showing outlines in the panel (008) and using CTEs (009)
- Free-text search over outlines: `q` matching descriptions, CTE names
  or tables as words. It would change how existing searches rank, which
  010's relevance set is for judging.
- Column-level lineage, output column types, or anything needing the
  database's schema
- Validating SQL or reporting syntax errors to curators
- Generating descriptions with an LLM (a possible outcome of 010)
- Languages other than SQL, though the analyzer interface must allow them
- SQL statements other than queries, and CTEs nested below the top level
- A long-running Python service. Analysis happens in batches at refresh,
  so a subprocess per refresh is enough.

## Components and API changes

Sketch only.

### code-source (Kotlin)

- An analyzer interface: given files' paths and text, return a
  generic fields and a language-specific object for each. One implementation for SQL, which
  runs the Python analyzer. Analysis happens when the tree is loaded or
  refreshed, before the snapshot is swapped in, and the results are kept
  with it, so adding the outline to a file response is cheap.
- Runs the Python analyzer **once per refresh** with every SQL file in
  the snapshot, not once per file, so Python's start-up cost is paid
  once. Later, only changed files could be sent.
- Treats the analyzer as untrusted and fallible: a time limit, a cap on
  output size, a non-zero exit, unparseable output, or a missing entry
  for a file all fall back to partial outlines for the files affected.
  The analyzer's stderr goes to code-source's log.
- Things that aren't SQL-specific stay in Kotlin, e.g. folder
  descriptions from `README.md`.

### Python analyzer (`code-source/analyzers/sql/`)

- A command-line script plus pinned dependencies (sqlglot). No Superset
  dependency: code-source must stay extractable.
- Runs in a venv that code-source's Gradle build creates from the pinned
  dependencies, and that ships with code-source's installation. Nothing
  outside code-source configures or knows about it. The only thing
  required of the machine is a suitable `python3` to create the venv
  from.
- Masks Superset Jinja (`{{ }}`, `{% %}`, `{# #}`) before parsing, so
  templated examples still parse, and keeps line numbers unchanged.
- Takes keywords from sqlglot's tokenizer rather than its parser, so a
  file that doesn't parse still gets its keywords.
- Analyses each file independently: an exception on one file produces a
  partial result for that file, not a failed run.

### Contract between code-source and the analyzer

Internal: not part of `docs/api.md`, but written down here, because both
sides are tested against it.

- Input on stdin: one JSON document, `{ "dialect": "...", "files":
  [{ "path": "...", "text": "..." }] }`.
- Output on stdout: one JSON object per line, one per input file, each
  carrying its `path` and either an outline or a partial outline with a
  reason. Lines let code-source keep the results it got if the analyzer
  dies part way.
- Each outline is in the API's shape (below), so code-source passes it
  through without reshaping it.
- Exit 0 when every file got a line, whether or not it parsed. Anything
  else means the analyzer failed as a whole.
- Logs and diagnostics on stderr only.
- A `version` field in the output, so a mismatch between the two sides
  is detectable.

### API (add to `docs/api.md`)

- An `outline` field on `GET /api/v1/file`'s response, and on file
  entries in `GET /api/v1/tree`, in the same shape. No new endpoint: the
  outline describes the same resource as the file's content.
- `GET /api/v1/search` gains repeatable `reference`, `function` and
  `keyword` parameters, and `unqualified=include|exclude` (default
  `include`) for references a file wrote with fewer parts than asked.
  `q` may be empty when one of them is given. This amends 006.
- `description` on directory entries in the tree, and on the listing
  itself for the listed folder.

The outline's shape is settled in `docs/api.md` (Outlines). Its first
draft is kept here for the record.

```json
"outline": {
  "language": "sql",
  "partial": false,
  "description": "Revenue per month, with a running total for each year.",
  "keywords": ["GROUP BY", "OVER", "PARTITION BY", "WITH"],
  "functions": ["ROUND", "SUM"],
  "references": ["cleaned_sales_data"],
  "sql": {
    "queries": [
      {
        "range": { "start": 2, "end": 13 },
        "keywords": ["OVER", "PARTITION BY", "WITH"],
        "functions": ["ROUND", "SUM"],
        "tables": [],
        "uses": ["monthly"],
        "ctes": [
          {
            "name": "monthly",
            "range": { "start": 2, "end": 6 },
            "description": null,
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

### dev/ and images

- The code-source dev image (`dev/code-source-dev.Dockerfile`) gains
  Python and the analyzer's dependencies. `code-source-dev.sh` should
  pick up edits to the Python side as well as the Kotlin side.
- There's no production image for code-source yet. If one is added
  during this feature, its size with and without Python is one of the
  experiment's results.

### Order of work

To reach the experiment's pain points early:

1. The subprocess plumbing end to end, with the smallest real outline:
   queries and CTEs with their ranges and uses, tables, keywords and
   functions, in the file response and on tree file entries. Includes the failure handling and the dev and test
   setup.
2. Descriptions from the comment conventions, for queries, CTEs and
   folders, and in tree entries.
3. Search by references, functions and keywords.
4. Trino and Oracle examples, as test resources with tests of their
   outlines. Not in the sample repository, which is SQLite for the dev
   examples database, where they couldn't run.

## Testing

- Through code-source's HTTP API, against real fixture repositories
  and the **real** analyzer, as for every other code-source feature.
  `./gradlew test` builds the venv first, as `run` and `installDist`
  do.
- Failure handling is tested by pointing code-source at stand-in
  analyzers that crash, hang or print rubbish. These are fakes at the
  narrowest seam: the command code-source runs.
- The analyzer on its own is isolated complex logic (parsing, masking,
  table and keyword extraction). `SqlAnalyzerTest` calls the real
  analyzer through `SqlAnalyzer.installed(dialect)`, with no repository
  or HTTP: a thin seam for testing dialects and heuristics. Awkward
  examples found in the wild belong there.

## Open questions

None left open. The ones that can wait are under Follow-ups, in
Implementation notes.

## Decisions

Filled in during planning and implementation: what was decided, and why.

- **code-source may understand languages** (agreed 2026-10-13). Language
  support is pluggable, every outline has the same generic fields, and
  language-specific detail sits in an object keyed by the outline's
  `language`. Recorded in `decisions.md`, with `vision.md` and
  `code-source/AGENTS.md` amended.
- **The outline is part of the file response.** It describes the same
  resource as the content, and the plugin's backend passes the file
  response through, so the panel gets outlines without a new route. A
  separate `/api/v1/outline` endpoint was rejected: its only advantage,
  fetching outlines without content, is speculative, and a parameter or
  a bulk endpoint would serve it better if it's ever needed.
- **No file-level description** (agreed 2026-10-13, replacing the
  draft's "first comment in a file"). Once a file can hold several
  queries, each with its own description, a file description could only
  repeat the single query's, or summarise ("3 queries") what the
  outline already shows. It can come back if a need appears.
  Alternatives considered: the first query's description (misleading
  for a file of several queries); a separate leading comment block when
  there are several queries (more rules for curators to learn).
- **Comment attribution: a comment directly above a CTE's line is the
  CTE's.** Simple to state and to check by eye. It means a comment
  directly above `WITH monthly AS (` describes `monthly`, not the query,
  so a curator who wants a query description leaves a blank line. That
  is a small new habit, accepted for simplicity, and the sample
  repository follows it. Alternatives considered: counting comment
  blocks against the slots available (file, query, CTE), which kept
  today's habit working but was hard to explain; treating a lone block
  above `WITH x AS (` as the query's, an exception to remember.
- **Directory entries always carry `description`**, null when there's
  none, as outlines do for missing values. The listed folder's
  description is on the listing too, so the root folder's README isn't
  lost; whether clients show it is for them (and may matter if
  code-source serves several repositories as folders).
- **Search by outline through `/api/v1/search`**, with `reference`,
  `function` and `keyword` filters, rather than a separate references
  endpoint. They combine with path words, `ext` and each other in one
  query, ranking and count. Several values must all match (unlike
  `ext`, where a file has only one extension). The parameters name
  generic fields, so the API stays language-neutral.
- **Partly qualified names match inclusively by default.** For finding
  examples, a false positive costs a glance and a miss goes unnoticed,
  and a file's `orders` usually means some schema's `orders` that we
  can't know. Files that wrote the full name rank first among otherwise
  equal results, and `unqualified=exclude` drops the rest. Alternatives
  considered: exclusive by default (hides many relevant examples); a
  default catalog and schema per repository or folder (more for
  curators to configure).
- **The filters live in the Lucene index**, as fields on each file's
  document, as the 2026-10-12 decision foresaw. A reference is indexed
  both by each of its suffixes (`orders`, `sales.orders`,
  `iceberg.sales.orders`) and as written, so both matching modes are
  term queries. Alternative considered: filtering outlines in Kotlin and
  intersecting with path search, which would need its own ranking,
  limit and total. Free-text search over outline content is left for
  later (see Out of scope).
- **Tree listings include each file's full outline.** 008 decorates the
  tree with descriptions, tables and CTEs, and this way a folder's
  listing has all of it, with no call per file. Listings are one folder
  at a time, so the extra size is bounded by the folder: a few hundred
  bytes to a couple of KB per file. A description-only field in the tree
  was rejected: expanding a file would then need the file response,
  content included, just for its outline. An opt-out parameter can be
  added if a client ever needs lean listings.
- **SQL outlines are queries containing CTEs, not a flat list of
  symbols.** A `WITH` clause belongs to one statement, so CTE names are
  only unique within a query, and queries have no names at all. A flat,
  generic symbol list (name, kind, range, dependsOn) was rejected: it
  would need null or invented names for queries, and its dependencies by
  name would be ambiguous when two queries define CTEs of the same name.
  The generic fields keep language-neutral clients working.
- **Language-specific detail is keyed by the language's name**
  (`"sql": {...}`). It gives every language one predictable place, and
  generic and language fields can't clash as long as generic field names
  are never language names. A fixed key such as `"detail"` was
  considered: no clash is possible, but it says less about its contents.
  `language` stays as an explicit field, since a recognised language
  with no analyzer has no object to key.
- **Only queries are analysed.** Other statements are left out, the way
  files in languages without an analyzer are, and the shape allows them
  to be added.
- **Dialect from `SQL_DIALECT`.** One repository-wide setting (`trino`,
  `oracle`, ...). Unset means sqlglot's generic dialect. Per-folder or
  per-file overrides can come later.
- **Ubiquitous keywords are left out.** A fixed list, kept with the SQL
  analyzer, of keywords found in nearly every query. Listing everything
  would make every file's keywords look alike, and leave each client to
  filter them. A curated list of constructs was rejected: it would
  silently drop any construct nobody thought to add.
- **Functions are separate from keywords.** They're different things,
  and keeping them apart lets a client combine them or not. A client
  can't split them once they're merged.
- **The analyzer's venv is internal to code-source's build.** Gradle
  creates it, and nothing outside code-source configures the analyzer.
  A configurable interpreter path was rejected: it would only add
  decoupling that isn't needed, since sqlglot is an implementation detail
  of code-source.
- **sqlglot pinned for code-source alone** (30.22.0, the latest when
  this was built), not matched to Superset's 28.10.0. code-source is to
  be extracted and mustn't follow Superset's releases. Dialect
  behaviour may differ slightly between the two; nothing here depends
  on them agreeing.
- **sqlglot, run as a subprocess.** sqlglot has dialects for Trino and
  Oracle and does the scope analysis outlines need (CTE references
  versus tables, alias resolution). The JVM has no equivalent. Of the
  ways to use it from code-source, a subprocess per refresh is the
  simplest: analysis already happens in batches at refresh, so it
  doesn't need a long-running service. If the experiment shows the
  pattern works, record it in `decisions.md` as the way code-source uses
  Python tools, since 010 or later features may want it too.
  Alternatives considered:
  - JVM parsers (JSqlParser, ANTLR grammars-v4, Trino's parser,
    tree-sitter): the parser spike that compared them is replaced by
    this decision, on the strength of research done outside this
    project.
  - Rewriting code-source in Python: the only Python-only need on the
    roadmap is sqlglot, which doesn't justify replacing working code
    and its tests.
  - A long-running sidecar service over HTTP: another process to run,
    monitor and secure, for calls that happen once per refresh.

## Implementation notes

Filled in when done: what changed and where, known limitations, follow-ups.

### Step 1: the subprocess, outlines in file and tree responses

What changed:

- `code-source/analyzers/sql/analyze.py`: the analyzer, pinned to
  sqlglot 30.22.0 in `analyzers/requirements.txt`. Ranges, and which
  query or CTE something belongs to, come from token and identifier
  offsets, since sqlglot's other nodes don't record positions. Keywords
  and functions come from tokens, checked against the parse tree where
  there is one (a keyword used as a name, such as a `date` column, isn't
  a keyword). Function names are as written, not sqlglot's canonical
  names (it would report Oracle's `NVL` as `COALESCE`).
- `SqlAnalyzer.kt` runs it; `Outline.kt` is the outline, decoded
  strictly from the analyzer's output, so anything off-contract counts
  as no outline. `GitCodeSource` builds outlines with each snapshot.
- `build.gradle.kts`: `createAnalyzerVenv` for tests and `run`;
  `installAnalyzerVenv` gives an installation its own venv.
- The plugin backend's two pass-through tests compared the forwarded
  body with a copy of code-source's response, so they broke when that
  response gained `outline`, though the route's contract hadn't changed.
  They now compare it with what code-source itself answers.

What the experiment has shown so far:

- **Call cost**: about 100 ms per refresh, almost all of it Python
  starting and importing sqlglot. The 65 sample files take 0.17 s in
  the analyzer, and 0.7 s from a cold start. Every refresh of a tree
  with SQL in it pays this, including in tests: 65 analyzer runs in
  the code-source suite take 7.7 s in all (2 s of it the hang test's
  time limit).
- **Failure handling** works as specified, and is tested with stand-in
  commands. One gap, found from the stuck analyzers below and fixed: an
  analyzer that doesn't exit even when killed used to hang refresh for
  good. It's now abandoned 10 s after being killed. Its test fakes the
  process, since no real one can be made to ignore SIGKILL. The dev environment tested it by accident: a dev image
  without Python left code-source serving partial outlines, logging why.
- **Packaging, the first real pain point**: a venv can't simply be
  copied into an installation. Gradle's copy follows the venv's symlink
  to Python and copies the binary itself. On macOS, launches of that
  copy hung in the kernel (state `UE`), unkillable, and each later
  launch of the same file stuck behind them. The Jest suite left 33
  such processes, which only a restart clears. Fixed by creating the
  installation's venv in place, so its `python` is a symlink like any
  venv's. Consequences: an installation needs the same `python3` at
  run time as at install time, and `distZip`/`distTar` carry no venv,
  so an image has to run `installDist` (or create the venv) itself.
- **Dev loop and images**: the dev image gains `python3` and
  `python3-venv`, and the watcher restarts on edits to `analyzers/`. A
  running dev stack needs its image rebuilding to pick this up.
- **Tests**: `./gradlew test` needs `python3` on the PATH and creates
  the venv once (a few seconds, then up to date). No other setup.
- **Dialect behaviour** differed from what we expected; see Open
  questions (Dialects, Oracle specifics).

### Step 2: descriptions

What changed:

- The analyzer finds comments in the gaps between tokens, so text in
  strings is never mistaken for one, and Jinja comments are already
  masked out. Consecutive comment lines on lines of their own form a
  block; `describe()` in `analyze.py` gives blocks to queries and CTEs
  by the rules under Conventions.
- `Readme.kt` reads a folder's description from its README; a refresh
  keeps them with the snapshot. Tree listings carry the listed folder's
  description and each directory entry's.
- The outline lost its file-level `description`, and queries gained
  one. `OutlineTest`'s expected outlines changed with the spec: the
  first test's fixture has its comment directly above `WITH monthly AS
  (`, so that comment now describes the CTE, not the query.
- Sample repository: a blank line under the description of the 23
  files that start with a CTE, CTE descriptions in two files, a file
  with two queries (`sales/problem_orders.sql`), READMEs for three
  folders, and the conventions in its README. The dev service parses it
  as SQLite (`SQL_DIALECT`), the dialect it's written for; all 66 files
  parse fully.

The curators' cost of the attribution rule, measured on the sample
repository: 23 of its 66 files start with a CTE, and each needed a
blank line to keep its description on the query.

### Step 3: search by references, functions and keywords

What changed:

- `PathIndex` (now more than paths, but not renamed) indexes each file's
  references, functions and keywords. A reference is indexed by each of
  its suffixes and as written (`ReferenceName.kt` splits and normalises
  names), so `unqualified=exclude` is one term query, and `include` adds
  a lookup of the shorter names given against what files wrote. Full
  matches add 0.001 to the score: enough to rank first among otherwise
  equal results, too little to reorder path matches, whose scores differ
  by whole numbers.
- `CodeSource.search` takes a `SearchFilters`, which now holds the
  extensions too.
- New tests in `SearchByOutlineTest`; 006's tests are unchanged.

Limitations and follow-ups:

- The plugin's search route forwards only `q`, `limit` and `ext`, so the
  panel can't use the filters yet. That belongs with whichever feature
  shows them.
- A quoted name containing a `.` (`"my.table"`) is split at it.

### Step 4: Trino and Oracle examples

- Eight everyday examples, four per dialect, written for this (no real
  ones were available): Jinja templating, `UNNEST`, `FILTER`, `ROLLUP`,
  named windows, two queries in a file; `CONNECT BY`, `(+)` joins,
  `DECODE`, `LISTAGG`, `FETCH FIRST`, `MINUS` and a database link. They
  live in `src/test/resources/sql-examples/<dialect>/`, and
  `SqlExamplesTest` checks each one's outline. All parse fully.
- They turned up one fix: Oracle's `START WITH` was listed as `START`
  and `WITH`, so a hierarchical query looked as though it had a CTE. The
  analyzer now joins it into one keyword (`COMPOUND_KEYWORDS`).

Known limitations they show:

- A database link stays part of the reference as written
  (`dw.dim_product@reporting_link`), so `reference=dw.dim_product`
  doesn't find it.
- Some multi-word clauses come out as separate keywords (`FETCH`,
  `FIRST`, `ROWS`; `CROSS`, `JOIN`).
- Pseudo-columns and functions called without parentheses (`LEVEL`,
  `ROWNUM`, `SYSDATE`) are neither keywords nor functions.

### Experiment conclusions

The pattern works and is worth keeping: a Python tool, run by
code-source as a subprocess once per refresh, behind a written contract.
Recorded in `decisions.md` (2026-10-13). On each question the experiment
set out to answer:

- **Call cost**: about 100 ms per refresh, almost all Python starting.
  65 files take 0.17 s in the analyzer. Negligible at this size;
  chunking can come if a repository proves it necessary.
- **Failure handling**: every failure tried is contained (won't start,
  crashes, hangs, can't be killed, prints rubbish, wrong contract
  version, unknown dialect): the refresh completes, SQL files get
  partial outlines, and the log says why. The one gap found, waiting
  without limit for a killed process, is fixed.
- **Packaging**: the real cost. A venv can't be copied, so an
  installation creates its own in place and needs the same `python3`
  at run time. On macOS a copied Python binary hung unkillably; that was
  never explained, and isn't needed to be, since production runs in a
  Linux container (011), as the tests do. 011 measured the image:
  Python adds about 59 MB (36 MB of `python3`, 23 MB of venv) to
  428 MB.
- **Dev loop**: one more package in the dev image, and the watcher
  restarts on Python edits too. A running stack needs its image
  rebuilding once.
- **Tests**: `./gradlew test` needs `python3` on the PATH and nothing
  else; the venv is created once. `SqlAnalyzer.installed()` proved a
  good seam for analyzer tests without HTTP.
- **Two codebases**: manageable. The Python is short; understanding
  sqlglot matters more than knowing Python. Upgrading sqlglot is the
  likeliest source of breakage, and `SqlAnalyzerTest` and
  `SqlExamplesTest` are there to catch it.

### Follow-ups

Left open, none needed now:

- **Built-in versus user-defined functions.** sqlglot parses functions
  it doesn't know as "anonymous", which isn't a reliable built-in list
  (Oracle's is incomplete), so every function is listed, unmarked.
- **Dialects beyond one per repository.** sqlglot doesn't detect
  dialects. Its generic one is more lenient than expected (it parses
  Oracle's `(+)` joins, `CONNECT BY` and `table@link`), but not
  everything (`MINUS`, `MATCH_RECOGNIZE`). For mixed repositories: a
  per-folder or per-file override, or trying each configured dialect
  and keeping the best parse.
- **Columns** referenced on each table, for matching against the
  analyst's schema. Harder to get right (unqualified columns,
  `SELECT *`).
- **Oracle specifics**: synonyms and table functions are listed as
  written. Database links stay part of the name, so searching for the
  table without the link misses it. Oracle's alternative quoting
  (`q'[...]'`) can't be tokenised by sqlglot 30.22, so such files get
  partial outlines; masking those strings, as Jinja is, would fix it.
- **The plugin's search route** forwards only `q`, `limit` and `ext`;
  whichever feature shows the new filters should add them.
- **Free-text search over outlines** (descriptions, CTE names, tables),
  to judge with 010's relevance set.
