# 008: Richer information in the tree

**Status:** done

## Purpose

Help analysts judge an example from the tree, before opening it: what
it's for, what it reads, and whether it's current. Uses what 007
extracts, plus a last-modified date. This feature is about what the tree
*shows*. Doing more with the parts of a file, such as loading a query up
to one of its CTEs, is 009.

### This feature is iterative

Much of it is about what feels right in the panel, so the look is
expected to change as we try it in the dev stack. Behaviour below marked
*(to try)* is a starting point, not a commitment; what we settle on is
recorded under Decisions. Tests assert what a user can find and do
(text, accessible names, actions), not layout, so changing the look
shouldn't change them.

## Behaviour

### Hovering

Hovering a row's name shows a tooltip to the right of the panel, after a
short delay (about half a second), so moving the pointer down the tree
doesn't flash a tooltip on every row. Action buttons keep their own
tooltips, and hovering them doesn't show the row's.

The keyboard shows the same tooltips (2026-10-13). When the arrow keys
make a row active, in the tree or in search results, its tooltip opens
after the same delay, and closes when the keyboard moves on, when the
tree or results lose focus, or when the mouse is used. A click makes a
row active too, but doesn't open its tooltip: the pointer is already
there to hover.

- **Folders:** the folder's description (its README's first paragraph,
  from 007), with its inline Markdown rendered. No tooltip when it has
  none.
- **Files with one query:** that query's description, if it has one.
- **Files with several queries:** "3 queries".
- **Every file:** then the full date and time it last changed ("Changed
  12 March 2025, 14:03"), in the browser's locale and time zone.
- **Query rows** (see Files with several queries): the query's
  description.

There's no file-level description: 007 decided it would repeat the
query's or say no more than the outline does.

### Last modified

Each file row shows when the file last changed in the repository, at
the right of the row, in the secondary text colour and a smaller size:

- Under 30 days ago, relative: "just now", "5 minutes ago", "3 hours
  ago", "yesterday", "12 days ago".
- Older, month and year: "Mar 2025".

The full date is in the tooltip. While a row's action buttons show
(hovered or active, as in 004), they take the date's place. The two
share one space at the right of the row, as wide as the wider of them,
so swapping them never moves the name. A long name is cut short with an
ellipsis rather than pushing the date or buttons aside. A file whose
date code-source doesn't know shows none.

A row's tooltip opens beyond the right end of the row, for files and
folders alike, so it never covers the row's actions.

"Last changed" is the committer date of the newest commit on the served
ref's first-parent history that changed the file: when the change
reached the branch. A change made on a branch and merged counts from the
merge.

### Tables

The real tables a query reads, its CTEs' included, are shown on its row
as small pills (Superset's `Tag`) after the name, for an analyst who knows the data to
recognise what's inside ("this uses `orders`"). They're decoration: no
actions in 008.

Pills take whatever room is left between the name and the
date. The name is never squeezed out; pills that don't fit collapse
into a "+3" pill, and every table is in that pill's tooltip. Tables are
sorted. A query's tables are worked out from the query itself (its own
`tables` and its CTEs'), not the file's `references`, so a one-query
file and each query row of a multi-query file get them the same way.

### Files with several queries

A file with one query (or none, or no outline) is one row, as in 004.

A file with several queries also has a row for each query, one level
of indentation below it, with a query icon, and always shown: no
expanding or collapsing. They're expected to be rare, so they don't
need hiding. Most of the decoration moves from the file row to the
query rows:

- The file row keeps only what belongs to the whole file: its name, its
  date and its actions. No pills, and its tooltip says how many queries
  it has, then the date.
- Each query row is named by its place in the file ("Query 1", "Query
  2"), and shows its own tables as pills. Its tooltip is its full
  description.
- Query rows have no actions in 008, and keyboard navigation moves
  through them like any other row. Enter or double-click on a query row
  does nothing yet (009 may give it a meaning). 012 later gave query rows
  a file's actions.

### CTEs

Not shown in 008; using them is 009. What 008 settles for 009 is where
they'll live: behind a quiet trigger among the row's action buttons,
opening a menu of the query's CTEs. (Superseded by 009: CTEs are rows under
their query's row, collapsed at first.) Since a menu of CTEs only makes
sense with something to do with them, 008 doesn't build it.

### Folder READMEs

README files stay out of the tree itself, as in 003. A folder's README
gives only its tooltip's description; there's no way to read the whole
README from the panel. A Preview of it was planned, and is deferred
until analysts are known to want it (see Decisions). The root folder's
README isn't shown at all: it has no row.

### Files without an outline

A file with no outline, or a partial one, shows whatever it has (perhaps
only a date), and otherwise behaves exactly as in 004. Search results
(006) are unchanged.

## Acceptance criteria

- [x] Hovering a folder with a description shows it, with its inline
      Markdown rendered
- [x] Hovering a file shows its query's description, or how many
      queries it has, and the full date it last changed
- [x] Moving to a row with the keyboard, in the tree or in search
      results, shows its tooltip, and moving on or using the mouse hides
      it (2026-10-13)
- [x] Each file row shows when it last changed, relatively under 30 days
      and as month and year after
- [x] A file's date is the committer date of the newest first-parent
      commit that changed it, so a merged change counts from the merge
- [x] A file's tables are shown on its row, or on each query's row when
      it has several queries, and none are lost when they don't fit
- [x] A file with several queries shows a row for each, with its
      description and tables, and keeps its file actions on its own row
- [x] Double-click, Enter and the Open action still open a file
- [x] After code-source refreshes and the panel reloads, descriptions,
      tables, queries and dates reflect the new contents
- [x] Files without outlines or dates work exactly as before
- [x] code-source gives each file's `modified` in tree listings and file
      responses, and null when it can't be worked out

## Out of scope

- CTEs in the panel, and any actions on queries or tables (009 and
  later)
- Expanding and collapsing files (009 later lets a file with CTEs expand
  to show them)
- Sorting or filtering the tree by date
- Who changed a file, commit messages or any other history
- Previews of a file's contents
- Reading a folder's whole README from the panel (deferred, see
  Decisions)
- Dates, descriptions or tables in search results
- The root folder's README
- Dates on folders
- Relative links and images in READMEs (they'd need resolving against
  the repository)

## Components and API changes

### code-source

- **`modified` for each file**, worked out once per refresh, before the
  snapshot is swapped in, and kept with it. One walk down the served
  ref's first-parent history, diffing each commit with its first parent
  and recording its committer date against each path first seen
  changed. The walk stops once every file in the tree has a date. Kept
  behind the repository-access code; nothing about commits reaches the
  API.
- If the walk fails, or the history is incomplete (a shallow clone),
  files it didn't reach get null and the cause is logged. A refresh
  never fails because of it, as with outlines.
- Cost to be measured on the sample repository and on a large public
  history, and recorded in Implementation notes.

### API (`docs/api.md`)

- `modified` on file entries in `GET /api/v1/tree`, and on
  `GET /api/v1/file`'s response: when the file last changed, as an ISO
  8601 UTC timestamp to the second (`"2025-03-12T14:03:00Z"`), or null
  when unknown. Always present, as `description` is on directories.
  Described as a plain file attribute, like a filesystem's modification
  time.

### plugin

- **Backend:** no change. The tree and file routes pass code-source's
  responses through, so `modified` and outlines already reach the panel;
  the existing pass-through tests cover it.
- **Frontend:** dates, pills and tooltips on rows, and query rows.
- **Markdown:** `markdown-it`, pinned and bundled with the extension,
  with HTML disabled and links limited to `http(s)`. The fork's
  extension API has no Markdown renderer, and Superset's `SafeMarkdown`
  isn't shared with extensions.

### Order of work

Data first, then the panel in steps small enough to try by eye after
each:

1. code-source: `modified` in tree and file responses, `docs/api.md`,
   tests, and the cost measured.
2. Panel: dates on file rows, and tooltips for files and folders.
3. Panel: table pills.
4. Panel: rows for queries in files with several.
5. Panel: Preview for folders' READMEs (deferred).
6. Smoke test, settle the *(to try)* items, and notes.

## Testing

- **code-source:** through the HTTP API, against fixture repositories
  whose commits have set committer dates (and different author dates, to
  show which is used), including a merge of a branch that changed a
  file, a file changed several times, and a refresh that brings a newer
  change.
- **Panel:** rendered against a real code-source over a fixture repo, as
  before. Fixture commits are dated relative to now (3 days ago, 400
  days ago), so dates are checked without faking the clock. Relative
  date formatting is isolated logic and gets a small table of cases
  against a fixed "now", with no mocking.
- **Pills that don't fit:** jsdom does no layout, so the panel tests
  check that every table can be found (on the row or in the "+N" pill's
  tooltip) and the overflow itself is checked by eye.
- **README rendering:** what a folder's tooltip shows for HTML, unsafe
  links and images, through the rendered panel.
- 003, 004 and 006's panel tests should pass unchanged. Any that doesn't
  is reported, not patched.

## Decisions

Agreed 2026-10-13, before work started:

- **No expanding files.** Only files with several queries need more
  than one row, and they should be rare enough to show their query rows
  always. Replaces the draft's "a SQL file can be expanded".
- **No file-level description**, as 007 decided. A one-query file's
  tooltip is its query's description; a file of several says how many.
- **Tables are pills on the row**, decoration for analysts who know the
  data, rather than rows of their own.
- **A file of several queries gets an indented row per query**, with a
  query icon, and the per-query decoration (pills, description) moves
  there. The file row keeps its name, date and actions.
- **The date and the action buttons share the right of the row**: the
  buttons replace the date while they show.
- **CTEs wait for 009**, behind a quiet trigger among the row's actions.
  A CTE menu with nothing to do from it isn't worth building now.
- **Previews in a popover first**, for READMEs and for files' contents,
  with a dialog as the fallback if a popover feels too ephemeral.
  (Superseded while trying it: no file previews, and the README preview
  deferred; see below.)
- **Committer date on first-parent history.** It's when a change
  reached the served ref. The author date can be much earlier after a
  rebase, and a walk of all parents would date a merged file by when the
  branch commit was made, not when it arrived.
- **`modified` on the file response too**, not only in listings, so the
  file resource looks the same through both endpoints, as outlines do.
- **The full README is fetched with the existing routes**: the folder's
  listing, to find its README by name (in any case), then the file. A
  README path in the API was rejected as one client's convenience.
- **markdown-it, with HTML off, rather than react-markdown.** Safe by
  construction, CommonJS so it works under Jest as it is, and small.
  react-markdown 8 with rehype-sanitize (Superset's own combination) is
  ESM-only, which needs Jest reconfiguring, and pulls in the unified
  ecosystem.
- **Dates only on files.** A folder's date (its newest file) is easy to
  add later, if wanted.

Settled while trying it:

- **No preview of a file's contents** (2026-10-13). The tooltip's
  description is the right level of detail for judging a file from the
  tree. (The fork's `editors` API wouldn't have given us SQL Lab's
  editor for one anyway: it only returns editors extensions register.)
- **The date and actions swap places**, in one space as wide as the
  wider of them, so nothing moves as they swap, and long names are cut
  short with an ellipsis instead. The actions lie over that space rather
  than taking turns in the layout, so they can keep being shown and
  hidden with `display`: hiding them with `visibility`, the obvious way,
  also blanks their accessible names in jsdom, which the 004 tests find
  them by.
- **A cut-short name isn't shown in full anywhere** (2026-10-13).
  Resizing the panel shows it. Adding it to the tooltip only when cut
  short would mean measuring the name on hover, which the panel tests
  can't check (jsdom does no layout); adding it always would repeat the
  name for most rows.
- **No README preview for now** (2026-10-13). Revisited once analysts
  are known to want it. If it comes back, the plan was a Preview action
  on folders with a description, opening a popover (or a dialog, if a
  popover felt too ephemeral) with the README rendered by
  `markdown.ts`, which already renders HTML as text, keeps only
  absolute `http(s)` links and shows images' alt text.
- **Folder descriptions stay as they are for now** (2026-10-13). Inline
  Markdown renders, but a README that opens with a list or a badge
  arrives flattened into one line. Whether that's worth improving in
  code-source, and whether READMEs earn the Preview, is decided later.
- **Table pills are small, borderless and tinted** (2026-10-13): 11px
  text, 14px high, in the action buttons' hover colour as a light tint
  (`colorPrimary` on `colorPrimaryBg`). The theme's small text (12px)
  crowded the row; its next size down (8px) is unreadable.
- **Rows can't be selected** (2026-10-13). Clicking a row used to paint
  it grey as selected, which nothing used, and the grey stayed behind
  while the keyboard moved on. The keyboard's active row, which a click
  also moves, keeps its hover-coloured highlight, so it shows where
  keyboard navigation starts.
- **Query rows are named "Query 1", "Query 2"** (2026-10-13), not by
  their description, which stays in the tooltip, as a one-query file's
  does. A description in the name's place read as though it were the
  file's name.
- **Tooltips open beyond the row's right end**, anchored to the whole
  row, so files' tooltips line up with folders' and clear the actions.
  Hovering the actions themselves still shows only their own tooltips.
- **The keyboard opens tooltips too** (2026-10-13, found while trying
  009). rc-tree keeps focus on its own hidden input, so the active row
  never has focus, and nothing opened its tooltip. Rows are told when
  they're the keyboard's active row and open their tooltip as hovering
  does. Clicks don't count, and using the mouse closes it, so a keyboard
  tooltip and a hover tooltip never show at once. Rejected: one shared
  tooltip that follows the active row (a second tooltip mechanism beside
  the rows' own).

## Open questions

None left open. Follow-ups are in Implementation notes.

## Implementation notes

Filled in when done: what changed and where, known limitations, follow-ups.

### Step 1: `modified` in code-source

What changed:

- `LastModified.kt` walks the served commit's first-parent history,
  diffing each commit with its first parent, until every file has a
  date. `GitCodeSource` now resolves the ref to a commit, not only a
  tree, and keeps the dates with the snapshot. A refresh that finds the
  same commit reuses everything; a new commit with the same tree reuses
  the outlines and index and redates the files.
- `modified` is on tree file entries and the file response, and in
  `docs/api.md`, worded without git: "when its newest change arrived".
- `FixtureRepo.commit` takes committer and author times, a second parent
  for merges, and a starting commit for a new branch. All defaults keep
  existing tests as they were.
- Tests in `ModifiedTest`. No existing test changed.

Cost, measured with the walk on its own against Superset's repository
(7,501 files, 16,956 first-parent commits, warm JVM): about 2.2 s, 3.4 s
on the first run. Only a new commit pays it. An examples repository
will be far smaller; the sample repository's is too quick to measure.

Limitations and follow-ups:

- The null case (a shallow history, or a failed walk) isn't reachable
  through the API, since code-source always makes full clones; it's
  handled and logged, but untested.
- A refresh redates every file from scratch. Walking only the new
  commits and keeping older dates would make it proportional to what
  changed, if large repositories ever need it.

### Step 2: dates and tooltips in the panel

What changed:

- `rowLabel.tsx` draws a row's name, a file's date and the tooltip for
  files and folders; `dates.ts` formats dates; `markdown.ts` renders
  READMEs' Markdown safely (`markdown-it` 14.1.0).
- Rows are named by the entry's name alone (`aria-label`) and described
  by their date (`aria-describedby`), so a screen reader reads "top.sql,
  3 days ago", and decoration never becomes part of a row's name.
- The date hides while the row's actions show, including while an
  action is busy (a `:has()` selector).
- Relative dates are brought up to date once a minute.

Test changes: two helpers in `Panel.test.tsx`, `rows()` and `fileRow()`,
read a row's text as its name, which stopped being true once rows show
dates (17 tests failed on that alone). They now use rows' accessible
names. No test body changed. `FixtureRepo.commit` takes a date.

Dev environment: `sample-repo-init.sh` takes `SPREAD_DATES=1` (set for
the dev sample, not the backend tests' fixture), which commits the
sample's SQL files over eleven backdated commits, from two years ago to
now, each file's age picked from its path. Four folders' READMEs show
richer Markdown: emphasis, code, links, an autolink, a badge image, a
relative link, and one that starts with a list.

Costs: `markdown-it` and its dependencies add about 100 KB, minified, to
the panel's bundle (41 KB to 148 KB). It could be loaded lazily if that
matters.

### Step 3: table pills

What changed:

- `queriesOf` (`api.ts`) gives each query its tables: its own and its
  CTEs', distinct and sorted.
- `tablePills.tsx` shows them between the name and the date. The pills
  take only the room the name leaves (a flex basis of 0), so a long name
  is never squeezed by them. Pills are measured on mount and on resize
  (`ResizeObserver`); those that don't fit are hidden, and a "+N" pill,
  whose tooltip lists them, takes their place. How many fit is
  `fittingCount` (`fit.ts`), tested on its own with a table of cases,
  since jsdom does no layout: the panel tests see every pill.
- A row's description is now its tables, then its date ("customers,
  orders just now"), from a visually hidden list of every table, so a
  screen reader hears the tables that didn't fit too. The pills and the
  "+N" pill are hidden from assistive technology.
- Multi-query files show no pills on their own row; their query rows
  come in step 4.

The tooltip tests' `act(...)` warnings were already there in step 2.

### Step 4: rows for queries

What changed:

- A file of several queries is a tree node with a row per query under
  it, so the tree indents them. The panel adds such files to the tree's
  expanded keys whatever the tree asks, so a click or the Left arrow
  can't collapse them, and folder loading skips files. The file row
  keeps a file icon (`DirectoryTree` gives any node with children a
  folder's).
- `rowLabel.tsx` has one `RowContents` for every row (name, pills, date
  and actions, tooltip), with `RowLabel` for folders and files and
  `QueryLabel` for queries. A query row is named by its place ("Query
  2"), described by its tables, and its
  tooltip is the whole description. Its icon is antd's
  `ConsoleSqlOutlined`.
- Query rows' keys aren't paths (`<path>\0query<n>`), so Enter,
  double-click and the actions never mistake them for files.

### Done

All acceptance criteria are met; the README preview was taken out of
scope rather than built. Checked by eye in the dev stack throughout:
dates, tooltips, pills at narrow widths, and query rows.

Follow-ups, none needed now:

- **Query rows have no actions.** Opening or copying one query of a
  multi-query file is the next feature (012), before CTEs (009).
- **Search results show none of this** (no dates, descriptions or
  pills); 013 reworks search.
- **README preview**, if analysts want it (see Decisions).
- **Folder descriptions flatten** a README that opens with a list or a
  badge; improving that is code-source's choice of description.
- **Dates are redone from scratch** on every new commit; an incremental
  walk if large repositories need it.
- **`modified: null`** is handled but unreachable through the API, so
  untested.
- **`markdown-it`** adds about 100 KB to the bundle; it could be loaded
  lazily.
- **Tooltip tests print `act(...)` warnings** from antd's motion; they
  pass, and the warnings are harmless, but noisy.

### Keyboard tooltips (2026-10-13)

- `rowLabel.tsx`: rows take `keyboardActive`, which opens their own
  tooltip after the hover delay. Row tooltips are now destroyed when
  closed (`destroyOnHidden`) rather than kept hidden, so a closed one is
  gone for tests too, and the DOM doesn't keep one per row ever hovered.
- `Panel.tsx`: `keyboardKey` is the active row when the arrow keys moved
  it there, known from a keydown in the tree (captured before rc-tree
  handles it) followed by `onActiveChange`. A click, any mouse movement
  over the tree, the tree losing focus, or Reload clears it.
- `SearchResults.tsx`: the same, with `byKeyboard`, set when the
  keyboard moves the active result and cleared by the mouse or blur.
- Tests: the tree and search results each show the active row's tooltip
  and hide it on mouse movement; a click shows none.

