# 012: Use one query of a file

**Status:** done

## Purpose

Let analysts take one query from a file that holds several, rather than
the whole file and deleting the rest. 008 shows those queries as rows
under their file but gives them nothing to do. This comes before 009
(CTEs), which puts its CTE menu among the same row actions, so the
query rows' actions are settled first.

## Behaviour

- Each query row (008) has the same actions as a file (004), on hover
  and on the active row: "Open in new tab" and "Copy to clipboard", with
  the same icons, tooltips, spinners and errors. This replaces 008's "query
  rows have no actions".
- Double-click and Enter on a query row open it in a new tab, as for a
  file.
- The file row keeps its own actions, which still use the whole file.
- One-query files are unchanged: they have no query rows, and their
  actions use the whole file.

### The query's text

Either action uses the query's lines, as written: from the first line of
its description's comment to the last line of the query (see Lines of a
file). So the text includes:

- the comment describing the query, and the comment describing its first
  CTE when that sits above the query's first line (007, Descriptions),
  and anything between them, such as a blank line;
- Jinja and comments within the query;
- a trailing `;` when it's on the query's last line, as it usually is. A
  `;` on a line of its own after the query is left out.

Whole lines are taken, so anything else written on those lines comes too,
such as a second statement on the query's last line. That's rare, and
accepted.

Jinja on lines of its own above the query (such as `{% set %}`) isn't
part of the query and doesn't come with it.

### Fresh contents

Contents are fetched each time an action is used, never cached, as in
004. The query's lines are taken from the outline that comes with the
fetched file, not the one in the tree, so a refresh between listing and
using can't cut the wrong lines. The query used is the one at the row's
position in the file as code-source serves it now.

If the fetched file has fewer queries than the row's position, the
action fails with an error, as in 004: "Couldn't use two.sql › Query 3:
It's no longer in the file. Reload to see the file as it is now." It
never falls back to the whole file.

### Tab title

`<file name without .sql> › Query <n>`, e.g. `two › Query 2`. 009 can
name CTE tabs the same way (`two › monthly`).

### Lines of a file

An isolated piece of the plugin frontend, which 009 will reuse to cut
CTEs. Given a file's content and a range of lines (1-based, inclusive),
it gives the text of those lines:

- Lines end at `\n`. A final `\n` doesn't start another line, so `a\nb\n`
  and `a\nb` both have two lines, and empty content has none.
- The text runs from the start of the first line to the end of the last,
  including the last line's ending if it has one. Line endings are kept
  as written, `\r\n` included.
- A range that starts before line 1, ends before it starts, or ends past
  the last line gives nothing, and the caller says the part is gone.

## Acceptance criteria

- [x] Opening a query row (button, double-click or Enter) creates a tab
      with just that query's text, named `<file> › Query <n>`
- [x] Copying a query row copies just that query's text, and confirms it
- [x] The query's text includes its description's comment and the first
      CTE's comment above it, and the `;` on its last line
- [x] The file row's actions still use the whole file
- [x] If the file changed since the tree was loaded, the query used is
      the one at that position in the file as code-source serves it now
- [x] If the file no longer has a query at that position, an error says
      so, naming the file and query
- [x] Other failures are shown as in 004, naming the file and query
- [x] code-source gives the line range of each query's and CTE's
      description

## Out of scope

- CTEs (009)
- Running the query, or inserting it into the current editor
- Actions on query rows of search results (search results don't show
  query rows)
- Taking part of a line: ranges are whole lines

## Components and API changes

### API (add to `docs/api.md`)

On each query and CTE in an outline's `sql` field, `descriptionRange`:
the lines of the comment block its `description` came from, as a range
like `range`. Null when `description` is null.

### code-source

The SQL analyzer keeps the comment block a description came from, and
gives its lines. Nothing else changes.

### plugin backend

Nothing new: the file route already forwards the outline.

### plugin frontend

- Actions, double-click and Enter on query rows, sharing 004's busy,
  copied and error state.
- Lines of a file, as above.
- The file response's outline is read by the actions, not only the
  tree's.

### dev

`smoke-test.sh` needs nothing new: the file route is unchanged.

## Testing

- **code-source:** `descriptionRange` in `SqlAnalyzerTest` beside the
  description cases, and in the HTTP outline test.
- **Lines of a file:** narrow tests of the promises above, with no
  fakes.
- **plugin frontend:** the panel against a real code-source, as in 004.
  A fixture file with several queries, with descriptions, a first CTE's
  comment and `;`s, covers the acceptance criteria. Changing the file and
  refreshing code-source between listing and using covers fresh contents
  and a query that's gone.

## Order of work

1. code-source and `docs/api.md`: `descriptionRange`.
2. Lines of a file, with its tests.
3. Panel: actions, double-click and Enter on query rows, and the error
   for a query that's gone.
4. Spec notes and decisions.

## Decisions

- **Ranges are whole lines.** Enough for whole queries. 009 will need
  more (a first CTE's line starts with `WITH`, and a CTE's last line
  often starts the next), which it can add to ranges without changing
  their shape.
- **The description's comment is included**, so copied text says what
  it's for, and a first CTE's comment above the query isn't lost.
  code-source gives where the comment is (`descriptionRange`), rather
  than the plugin finding it again: that would repeat 007's rules for
  which comment describes what, and the two would drift. 009 needs the
  same for CTEs.
- **A query that's gone is an error**, not the whole file: the analyst
  asked for one query, and quietly getting something else is worse than
  being told to reload.
- **The `;` is kept as written**, by taking whole lines. The whole-file
  action already gives SQL Lab files with `;`.
- **Tab title `two › Query 2`.** Rejected `two (Query 2)` (longer) and
  `two_2` (cryptic). 009 can follow it for CTEs.

## Implementation notes

- code-source: the analyzer keeps the comment block a description came
  from (`described_by`) rather than only its text, and gives its lines as
  `descriptionRange`. The analyzer's output version stays 1. Both sides
  ship together, and an analyzer without the field is still contained: its
  lines don't parse, so SQL files get partial outlines. Bumping it would
  have meant editing three tests that pin the version, for no gain.
- plugin frontend:
  - `lines.ts` is Lines of a file. `api.ts`'s `queryRange` picks a query's
    lines from an outline: the smallest start of the query's range, its
    `descriptionRange` and its first CTE's `descriptionRange`, to the end
    of its range.
  - `fileActions.tsx`: `open` and `copy` take an optional query index,
    and busy and copied state are keyed by the row's key (`queryKey`), so
    a query row's spinner and "Copied" are its own. The text is cut from
    the fetched file's outline (`fetchFile` now returns it).
  - `queryName` moved from `rowLabel.tsx` to `fileActions.tsx`, which
    `rowLabel.tsx` already imports from, to avoid a circular import.
  - `Panel.tsx`: query rows get `FileActionButtons`, and Enter and
    double-click find query rows as well as files.
- Tests: 008's "query rows have no actions, opening one does nothing"
  was removed, since 012 changes that behaviour. Its check that the
  file's own actions still work is in 012's tests. The HTTP outline test
  gained `descriptionRange`, as the API changed.
- Checked against the dev stack's `sales/problem_orders.sql`: Query 2
  takes lines 11 to 28, its description and first CTE's comment included.
  The buttons on query rows weren't checked by eye.
- For 009: a CTE's text needs more than whole lines (see Decisions).
  `linesOf` and `descriptionRange` are the pieces it can reuse.
