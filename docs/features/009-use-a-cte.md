# 009: Use a CTE

**Status:** done

## Purpose

Let analysts take one data shape from an example, not the whole file.
Comes after 012, which gives query rows their actions. 008 didn't show
CTEs in the tree; it settled that they'd live behind a quiet trigger
among a row's action buttons, opening a menu of that query's CTEs. 009
first built that menu, then replaced it with rows (see Decisions).

## Behaviour

### CTE rows

- A query's CTEs are rows one level under the row of their query: a
  one-query file's row, or a query row (012). So in a file of several
  queries they're two levels under the file. They're in the order
  they're written, with a CTE icon, named as in the file.
- A row with CTE rows under it is collapsed at first. Clicking it, or →
  on the keyboard, shows them; clicking it again, or ←, hides them, as
  for folders. A small caret after its name shows that it opens, and
  whether it's open *(to try)*. Query rows are still always shown (008).
- A CTE row is like a query row: its description (007) in a tooltip,
  the real tables its SQL reads as pills (its own and those of the CTEs
  it depends on, see The CTE's SQL), no date, and the same actions as a
  file (004) on hover and on the active row: "Open in new tab" and "Copy
  to clipboard", with the same icons, tooltips, spinners, "Copied"
  confirmation and errors.
- Double-click and Enter on a CTE row open it in a new tab. On a row
  with CTEs, they still open its file or query.
- Reload collapses CTE rows with everything else.

### The CTE's SQL

Either action produces runnable SQL: the CTE together with the CTEs it
depends on (and nothing else), followed by `SELECT * FROM <cte>`. The
analyst sees the shape of data that CTE produces. SQL Lab's own row
limit applies when it runs.

- The CTEs it depends on are those its `uses` names, and theirs, and so
  on. A recursive CTE uses itself, which changes nothing.
- They come in the order they're written in the file, so each is
  defined before it's used.
- Each keeps its original text: comments and Jinja within it, and the
  comment describing it (007, Descriptions) above it. The query's own
  description doesn't come, since it describes the query.
- `WITH RECURSIVE` is kept when the query's `WITH` had it.
- No `;` at the end.

For `video_games/publisher_market_share.sql`, `ranked` gives:

```sql
-- Global sales per publisher, in millions of units.
WITH by_publisher AS (
  SELECT publisher, SUM(global_sales) AS sales
  FROM video_game_sales
  GROUP BY publisher
),
-- Publishers ranked by sales, best first.
ranked AS (
  SELECT *, RANK() OVER (ORDER BY sales DESC) AS rnk
  FROM by_publisher
)
SELECT * FROM ranked
```

Jinja on lines of its own above the query (such as `{% set %}`) doesn't
come with it, as in 012.

### Fresh contents

As in 012: contents are fetched each time an action is used, and the
CTE is cut using the outline that comes with the fetched file. The CTE
used is the one with the row's name, in the query at its query row's
position in the file as code-source serves it now. It's found by name
rather than position, so CTEs added before it don't change which one is
used.

If that query, or a CTE of that name in it, is no longer there, the
action fails with an error, as in 012: "Couldn't use two.sql › Query 2
› monthly: It's no longer in the file. Reload to see the file as it is
now." It never falls back to the query or the file.

### Tab title

`<file name without .sql> › <cte>` for a one-query file, e.g.
`monthly_revenue › monthly`. When the file has several queries, the
query comes too, since CTE names are only unique within a query:
`two › Query 2 › monthly`. Errors name the CTE the same way, with the
file's full name.

### SQL of a CTE

An isolated piece of the plugin frontend. Given a file's content, one
query of its outline, and a CTE name, it gives the SQL above, or nothing
when the query has no CTE of that name, or a range it needs is outside
the content (and the caller says the CTE is gone).

- A part is one CTE: its description's lines, if it has a description
  (whole lines, as in 012's Lines of a file), then its body.
- A body is its `range` from `startColumn` on its first line to
  `endColumn` on its last: from its name to its closing `)`. When
  indentation (only whitespace) comes before the name on its line, the
  body starts at the start of that line instead, keeping the
  indentation as written.
- The SQL is: the first part's description lines; `WITH` (or
  `WITH RECURSIVE`), followed by a space, or by a line break when the
  body starts with indentation; the first body; then, for each
  further part, `,` and a line break, its description lines and its
  body; then a line break, `SELECT * FROM <cte>` and a line break.
- Line breaks it adds are `\n`. Text taken from the file keeps its own.

### Text of a range

An addition to 012's Lines of a file, with its promises unchanged. Given
a file's content and a range with columns, it gives the text from
`startColumn` on the first line to `endColumn` on the last, inclusive.
Columns are counted in code points, as the API gives them, not in
JavaScript's UTF-16 units. A range outside the content gives nothing.

## Acceptance criteria

- [x] A one-query file's row, and each query row, whose query has CTEs,
      has a collapsed row under it for each, in order, which clicking
      or the arrow keys show and hide; other rows don't
- [x] A CTE row shows its description in a tooltip, and the tables its
      SQL reads
- [x] Opening a CTE row (button, double-click or Enter) creates a tab
      with SQL that runs on its own, containing the CTEs it depends on
      and no others, followed by `SELECT * FROM <cte>`, named as in Tab
      title
- [x] Copying a CTE row copies the same SQL, and confirms it
- [x] The CTEs keep their original text, comments included, and each
      one's description comes with it
- [x] The SQL is right when the chosen CTE or one it depends on is the
      first in the `WITH` clause, is followed by another, is recursive,
      or has its name on a line of its own
- [x] If the file changed since the tree was loaded, the CTE used is the
      one of that name, in the query at that position, as code-source
      serves the file now
- [x] If that CTE is no longer in the file, an error says so, naming the
      file, query (when the file has several) and CTE
- [x] Other failures are shown as in 004, named the same way
- [x] code-source gives the columns of each query's and CTE's range, and
      whether each query's `WITH` is recursive

## Out of scope

- Inserting a CTE into the current editor's query, or merging it into an
  existing `WITH` clause. It's a natural follow-up, but a much harder one.
- Actions on tables (e.g. "other examples that use this table")
- CTEs nested inside a CTE or subquery (007 doesn't list them)
- CTEs in search results (search results don't show query rows)
- Quoted CTE names that need quoting to be used (`"Monthly Rev"`):
  outlines give names unquoted, so `SELECT * FROM` would break. Rare,
  and a known limitation.
- Comments after a CTE's closing `)` on the same line (`),  -- note`):
  they're outside its range and don't come.

## Components and API changes

### API (add to `docs/api.md`)

- On every `range` in an outline's `sql` field (queries and CTEs, not
  `descriptionRange`): `startColumn` and `endColumn`, the 1-based,
  inclusive columns, in code points, of its first and last characters,
  on its `start` and `end` lines. For a CTE, from its name to its
  closing `)`.
- On each query: `recursive`, true when its `WITH` says `RECURSIVE`.

### code-source

The SQL analyzer gives the columns it already knows (its parts' offsets
come from tokens, and Jinja masking keeps every offset) and whether the
`WITH` is recursive. The analyzer's output version stays 1, as in 012.

### plugin backend

Nothing new: the file route already forwards the outline.

### plugin frontend

- Text of a range, beside Lines of a file in `lines.ts`.
- SQL of a CTE.
- CTE rows, their actions sharing 004's busy, copied and error state,
  keyed per CTE.

### dev

`smoke-test.sh` needs nothing new.

## Testing

- **code-source:** columns and `recursive` in `SqlAnalyzerTest`, with a
  first CTE on the `WITH` line, one after `),`, a name on a line of its
  own, a column list, a non-ASCII character before a CTE on its line,
  and `WITH RECURSIVE`; and in the HTTP outline test.
- **Text of a range, SQL of a CTE:** narrow tests of the promises
  above, with no fakes, using the cases above.
- **plugin frontend:** the panel against a real code-source, as in 012.
  A fixture with a one-query file and a multi-query file with CTEs
  covers the acceptance criteria, and changing it and refreshing
  code-source covers fresh contents and a CTE that's gone.

## Order of work

1. code-source and `docs/api.md`: columns and `recursive`.
2. Text of a range and SQL of a CTE, with their tests.
3. Panel: the CTE menu and its actions. (Done, then replaced by 5.)
4. Spec notes and decisions.
5. Panel: CTE rows in place of the menu, and spec notes.

## Decisions

- **Ranges gain columns.** Whole lines aren't enough: a first CTE's
  line starts with `WITH`, and its last line often ends `),` or goes on
  `) SELECT`. This is the extra detail the 2026-10-13 decision on
  cutting parts anticipated, added without changing ranges' shape.
  Rejected: the plugin stripping `WITH` and separators itself (it would
  have to tokenise SQL, and comments after `),` defeat a simple
  approach), and code-source serving the cut SQL (fixes one client's
  idea of a part into the API, as rejected before). Columns go on
  queries' ranges too, so every `range` means the same thing.
- **`recursive` comes from code-source** rather than the plugin reading
  the `WITH` line, for the same reason descriptions do: one place
  understands SQL.
- **Columns are 1-based, inclusive, in code points**, to match lines
  (1-based, inclusive) and search's offsets (code points). Clients in
  UTF-16 languages convert.
- **A CTE is found by name**, within the query at the row's position.
  Names are unique within a query and survive CTEs being added before
  it; positions don't.
- **Each CTE's description comes with it**, as for queries in 012; the
  query's doesn't.
- **Tab title `two › Query 2 › monthly`** for multi-query files, and
  `file › monthly` otherwise, matching the rows. 012 suggested
  `two › monthly`, but that's ambiguous when two queries have CTEs of the
  same name.
- **No row for the final query** among the CTEs: the query row already
  opens it (012).
- **Jinja is kept as written**, as for files and queries.
- **CTEs belong under their query's row**: a one-query file's row and
  query rows, not a multi-query file's row, whose CTEs belong to its
  queries.
- **No `;`** after `SELECT * FROM <cte>`: nothing follows it, and SQL
  Lab runs it either way.

- **`WITH` goes on a line of its own only when the name is indented**
  (2026-10-13, while implementing). The first wording ("when the body
  starts at the start of its line") also caught a name at column 1, such
  as `ranked AS (` after `),`, and gave `WITH\nranked AS (`.
- **The panel's `WITH` is its own**, so a lowercase `with` comes out as
  `WITH`. Keeping the file's own spelling would mean finding the keyword
  in the text, for no gain to the analyst.
- **CTE rows, not a menu** (2026-10-13, after trying the menu in SQL
  Lab). The menu's items had no hover state and no description on
  hover, and a tooltip on a popover on a row stacked too many layers.
  Rows get hover, the active row, tooltips, pills and the keyboard from
  the tree, as query rows do. This replaces 008's decision that CTEs
  live behind a trigger among a row's actions.
- **CTE rows start collapsed**, unlike query rows (008): most examples
  have CTEs, and always showing them would roughly double the tree.
  Rejected: always shown.
- **A CTE row's tables include its dependencies'**: they're what its SQL
  reads, as a query row's include its CTEs'. Rejected: its own only
  (a CTE built on another would show none).

## Implementation notes

- code-source: the analyzer gives each range's columns from the offsets
  it already had (`Lines.column`), and `recursive` from sqlglot's `With`.
  Kotlin has `TextRange` for `range`; `descriptionRange` stays a
  `LineRange`. A quoted CTE name's range starts at its opening quote. The
  analyzer's output version stays 1, as in 012.
- plugin frontend:
  - `lines.ts`: `textOf`, Text of a range, beside `linesOf`.
  - `cte.ts`: `cteSql`, SQL of a CTE. It reuses `api.ts`'s `lineRange`,
    now exported.
  - `api.ts`: `queriesOf` gives each query its CTEs: name, description,
    and tables with their dependencies' (`ctesWithTables`).
  - `fileActions.tsx`: `open` and `copy` take an optional CTE name, and
    what an action uses is a `Use` (file, query, CTE), keyed, named and
    titled from one place. `cteKey` is a CTE row's key; `ctesOf` gives
    the CTEs under a row. `FileActionButtons` takes an optional `cte`.
  - `Panel.tsx`: a one-query file with CTEs, and a query row with CTEs,
    are non-leaf nodes whose children are CTE rows (`cteNodes`), so the
    tree's own expansion shows and hides them, by click and by arrow
    keys. `partRows` finds query and CTE rows for Enter and
    double-click. Expanding a node loads it only if it's a folder
    (`directories`); before, anything that wasn't a file was taken for
    one.
  - `rowLabel.tsx`: `CteLabel` is a CTE's row, built like `QueryLabel`.
    Rows with CTEs show a caret after their name (`expanded`), since the
    tree hides rc-tree's own switchers.
  - Keydowns on a row's buttons no longer reach the tree, so Enter on a
    focused button doesn't also open the row. Added for the menu, and
    kept.
- The menu (step 3) was built, tried in SQL Lab and replaced by rows
  (see Decisions). Its tests were rewritten for rows, since the spec
  they tested changed; the tests of SQL of a CTE and Text of a range
  were unaffected.
- Tests: in the code-source tests that pin ranges (two in
  `SqlAnalyzerTest`, two in `OutlineTest`), the ranges gained columns, as
  the API changed. `OutlineTest`'s whole-outline test also gained
  `recursive`. No plugin test from other features changed.
- Checked against the dev stack: `smoke-test.sh` passes, and code-source
  serves `video_games/publisher_market_share.sql` with the columns the
  spec's example needs. CTE rows weren't checked by eye: the caret, the
  `BlockOutlined` CTE icon and the two-level indent in multi-query files
  are still to be tried in SQL Lab.
- Follow-ups: CTEs in search results; quoted CTE names in `SELECT *
  FROM`.
