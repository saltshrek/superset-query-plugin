# 016: Quieter rows

**Status:** in progress

## Purpose

Rows carry more than an analyst needs at a glance. Found by using the
panel: the table pills, the `.sql` on every file name, and the small
caret all add noise. Each change amends an earlier feature. They're
grouped, as in 015, so each is reviewed against what it replaces.

## Behaviour

### Tables as one pill (amends 008, 009, 013)

Today a row shows its tables as pills after its name, and those that
don't fit collapse into a "+3" pill (008).

- A row with tables shows one pill: Superset's table icon
  (`TableOutlined`) and how many tables there are. Its tooltip lists
  them, sorted, as the "+3" pill's does now.
- It's the same pill in the same place (after the name and its
  chevron), with the same tint, whatever the panel's width. A row with
  no tables has no pill.
- The row is still described by all its tables, as now, so a screen
  reader hears "customers, orders, sales.returns" rather than "3".
- Which rows have tables is unchanged: one-query files, query rows,
  CTE rows; a file of several queries has none of its own. The same in
  search results (013).

### Names without `.sql` (amends 003, 004, 006, 009, 012)

- Wherever the panel names a SQL file, the name drops its `.sql`
  (any case): tree rows, search results, and errors ("Couldn't use
  top › Query 2: …"). New tabs' titles already drop it (004).
- Folders are named as now. A file and a folder of the same name in
  one folder are told apart by their icons and by folders coming first.
- In search results, words matched in the file name are highlighted as
  now. A word matched in the extension (search splits paths at `.`, so
  `sql` is a word of `top.sql`) isn't shown, as the extension isn't.
- Search itself is unchanged: `top.sql` still finds `top.sql`.

### The open/close chevron (amends 009)

Today a file or query row with CTEs has a small caret after its name
(▸ closed, ▾ open), in the small text size.

- It's the chevron the splitters use: right (`RightOutlined`) when
  closed, down (`DownOutlined`) when open.
- At the size of the action buttons' icons (`fontSize`), in the
  secondary text colour, with `sizeUnit * 2` either side, so it isn't
  crowded by the name or the tables pill.
- Still after the name, still decoration (hidden from screen readers;
  the row's `aria-expanded` says whether it's open), and still only on
  rows that have CTEs. Folders still show open and closed by their icon.

## Acceptance criteria

- [x] A row with tables shows one pill with their count, whose tooltip
      lists them, whatever the panel's width
- [x] The row is still described by all its tables
- [x] Tree rows and search results name files without `.sql`
- [x] A failed action's error names the file without `.sql`
- [x] A search result doesn't show a word matched in the extension
- [ ] Rows with CTEs show the right chevron when closed and the down
      chevron when open (checked by eye, with spacing)
- [ ] The pill's look and the chevron's size and spacing (checked by eye)

## Out of scope

- Hiding the date, or moving the chevron to the right end.
- Making the tables pill do anything but show its tooltip.
- Dropping extensions other than `.sql` (the panel shows no other files).

## Components and API changes

- plugin: `tablePills.tsx` (one pill; the fitting and measuring go, with
  `fit.ts` and its test), `rowLabel.tsx` (chevron, shown name),
  `fileActions.tsx` (names in errors), `SearchResults.tsx` (highlight
  only the shown name).
- code-source: none.
- API: none.

## Open questions

None.

## Decisions

- **A table icon and a count,** rather than "3 tables" (wider, and
  words to pluralise) or "+3" (reads as "and 3 more" when none are
  shown).
- **The chevron stays after the name,** rather than at the right end
  like the schema browser's toggle: our right end is where the date and
  the actions already take turns.
- **Right closed, down open,** as a tree's arrows usually are, and as
  the caret it replaces was. Down closed and up open, as the schema
  browser's table toggle, was tried first and changed after review
  (2026-10-29).
- **`.sql` dropped everywhere a file is named,** not only on rows, so
  an error names the file as the row does.

## Implementation notes

- Tables (`tablePills.tsx`): one antd `Tag` with `TableOutlined` and the
  count, in a `Tooltip` listing the tables. The visually hidden list
  that describes the row is unchanged. `fit.ts`, the ResizeObserver
  and the measuring are gone.
- Names: `shownName` in `fileActions.tsx` (was the tab title's
  `tabTitle`) strips `.sql`, any case. `entryName` in `rowLabel.tsx`
  applies it to files only. The tree uses it for rows' names, their
  accessible names and sorting, so files sort as they read. Errors and
  tab titles now share one name (`nameOf`). A search result's name is
  its path without `.sql`, highlighted only up to there.
- Chevron (`rowLabel.tsx`): `RightOutlined` closed, `DownOutlined` open, at
  `fontSize`, `sizeUnit * 2` on its left only. The pill, or the date
  slot, brings its own space on the right, and flex margins don't
  collapse, so space on both sides would have put 16px before the pill.
- Not yet checked by eye in the dev stack: the pill, the chevron, and
  bug 003's fix in Chrome 100.

### Tests

These changed existing tests, because the specs they assert changed:

- Every test naming a file row, result or error by `x.sql` names it `x`;
  fixtures keep their real file names. A mechanical change, in the same
  commit as the code, since the suite fails either way without it.
- The results helper told files from the query and CTE rows under them
  by their names ending in `.sql`. It now uses what a user sees: only
  those rows have icons (015).
- The two tests asserting each table is a visible pill
  (`a file's row shows the tables…`, `each result shows its tables…`)
  assert the count pill and its tooltip instead. Their
  accessible-description assertions are unchanged. The CTE row's test
  asserted only its description, so it didn't change.
- `fit.test.ts` went with `fit.ts`: what it specified no longer exists.

New tests: an error names the file without `.sql`; a word matched in
the extension isn't highlighted. The chevron isn't tested (decoration,
`aria-hidden`; whether the row is open is `aria-expanded`, already
covered).
