# 006: Search by file name

**Status:** done

## Purpose

Let analysts find an example by name without knowing which folder it's
in. Matching only file and folder names keeps the first version small,
so this feature can focus on the search interaction in the panel. Later
features add richer things to search over (007), and perhaps semantic
matching (010), behind the same interaction.

## Behaviour

- A search box sits above the tree. Typing in it replaces the tree with a
  list of matching SQL files. Clearing it brings the tree back as it was,
  with the same folders expanded.
- Results update as the user types.
- Matching is on words in the file's path, ignoring case:
  - A word is a run of letters and digits. Anything else (`_`, `-`, `.`,
    `/`, spaces, …) separates words, and so does a change from lower to
    upper case (`newCustomers` → `new`, `customers`; `HTTPServer` →
    `http`, `server`). Letters and digits next to each other stay one
    word (`q4_sales` → `q4`, `sales`).
  - What the user types is split into words the same way, so `new cust`,
    `new_cust` and `newCust` are the same search.
  - Every word typed must match a word in the path. The last word
    matches as a prefix, so results appear before a word is finished.
    The others must match whole words.
  - So `new cust` finds `customers/new_customers_by_month.sql`.
- Ranking:
  - A file whose name matches a word ranks above one that matches that
    word only in its folders.
  - Otherwise, results that match more closely rank higher. Ties are in
    path order, so the same search always gives the same list.
- Each result shows the file name with the matched parts highlighted,
  and its folder path in smaller text below it (matched parts highlighted
  there too). Results at the top level show no folder path.
- Results offer the same actions as files in the tree (004): the hover
  buttons for "Open in new tab" and "Copy to clipboard", double-click to
  open, and the buttons shown on the active result.
- At most 50 results are shown. When there are more, the list ends with
  "Showing 50 of N. Add words to narrow the search."
- A search that finds nothing says "No SQL examples match", and suggests
  fewer words.
- If the search fails, an error replaces the results, worded as the tree's
  errors are (unreachable, not ready, no access), with Retry.
- Reload, while searching, runs the search again. (Reload already
  re-reads the tree from code-source; see 003.)
- With the keyboard:
  - `Ctrl+Shift+S` focuses the box from anywhere in SQL Lab, including
    the editor, as long as the panel is shown. The box's placeholder
    names the shortcut: "Search examples (Ctrl+Shift+S)". (Ctrl on every
    platform, like SQL Lab's own shortcuts.)
  - Down arrow in the box moves into the results. Up and down move
    between results, and Up from the first result goes back to the box.
  - Enter on the active result opens it in a new tab, as in the tree.
  - Escape, in the box or the results, clears the search, brings back the
    tree, and leaves focus in the box.
- What can be found is what the tree shows: SQL files, nothing under a
  dot-entry. Folders aren't results in their own right, but their names
  are searched.

## Acceptance criteria

- [x] Typing words shows the SQL files whose path contains all of them,
      with the last word matched as a prefix; non-SQL files and anything
      under a dot-entry are never shown
- [x] Words are split at non-alphanumeric characters and lower-to-upper
      case changes, in both paths and what's typed, ignoring case
- [x] Matches in the file name rank above matches only in folder names
- [x] Each result highlights the parts of its path that matched
- [x] Results can be opened in a new tab or copied, as in 004
- [x] A search with no results says so; a search with more than 50 says
      how many there are
- [x] Clearing the search restores the tree, with its expanded folders
- [x] Search can be used with the keyboard alone: the shortcut focuses
      the box, arrows reach the results, Enter opens, Escape clears
- [x] After code-source refreshes, search finds files that were added and
      no longer finds files that were removed
- [x] code-source search returns paths, not SQL-specific results, and
      takes no SQL-specific parameters
- [x] A user without access to SQL Lab can't use the search route

## Out of scope

- Searching file contents, descriptions or anything else inside a file
  (later, see 007)
- Typo-tolerant (fuzzy) matching, synonyms or semantic matching (010)
- Query syntax for users (quotes, `field:value`, boolean operators)
- Search history or saved searches
- Recording searches, including ones that found nothing. Whether 010
  needs that evidence, and where it would be kept, is for 010 to decide.
- Searching folders as results in their own right
- Showing the shortcut in SQL Lab's keyboard shortcuts list. The
  extension API has no way to add to it.
- Opening the panel with the shortcut when the left panel is collapsed

## Components and API changes

### code-source

- `CodeSource` gains `search`. The HTTP layer stays unaware of Lucene.
- An in-memory Lucene index of the file paths in the served tree (files
  only, following the same rules as listings: no symlinks or
  submodules). It's built during refresh, before the new snapshot is
  swapped in, so the tree and its index always change together and
  searches during a refresh use the previous one. It's rebuilt only when
  the served tree has changed.
- One document per file, with the path split into separate fields: the
  file name (boosted) and its folders. That's what puts name matches
  first, and the document has room for more fields later (contents,
  descriptions, symbols from 007).
- Word splitting, as specified in Behaviour, is one function used for
  indexing, for the query and for working out the matched ranges. It is
  generic: it isn't about SQL, and it suits code identifiers in general.
- Dependency: `lucene-core` only. The analyzer is our own, so
  `lucene-analysis-common` isn't needed.

### API (add to `docs/api.md`)

`GET /api/v1/search?q=<text>&limit=<n>&ext=<extension>`

Purpose: find files by words in their paths.

Query params:
- `q` (string): the search text, split into words as above. Absent,
  empty or with no words means no results (200, empty list), so clients
  that search as the user types don't need a special case. Over 500
  characters is 400 `invalid_parameter`.
- `limit` (integer, optional): most results to return, 1 to 100. Default
  20. Anything else is 400 `invalid_parameter`.
- `ext` (string, optional, repeatable): only files whose name ends in
  `.<ext>`, ignoring case. Given without a dot (`ext=sql`). Several mean
  any of them. Empty, or containing `.` or `/`, is 400
  `invalid_parameter`.

Response 200:
```json
{
  "total": 3,
  "results": [
    {
      "path": "customers/new_customers_by_month.sql",
      "type": "file",
      "score": 2.31,
      "matches": [
        { "start": 0, "end": 4 },
        { "start": 10, "end": 13 },
        { "start": 14, "end": 18 }
      ]
    }
  ]
}
```

- `results` are ranked best first, at most `limit` of them. `total` is how
  many files matched in all.
- `type` is always `file` for now. It's there so clients are ready for
  other kinds of result later.
- `score` is only meaningful for comparing results in the same response.
- `matches` are the parts of `path` that matched a word of `q`, as
  `[start, end)` offsets in code points, in order, not overlapping. For a
  prefix match, only the matched prefix is included.
- Paths with any segment starting with `.` are never returned, like
  hidden files in most search tools. Listings still show them.

Errors: 400 `invalid_parameter` as above, naming the parameter in
`message`; 503 `not_ready`.

### plugin backend

`GET /extensions/quillfern/sql-examples/code-source/search` forwards `q`,
`limit` and `ext` (repeated as given) to `GET /api/v1/search`. It's gated
by `requires_sql_lab_read` and maps responses like the other routes:

- 200 `{"result": <code-source body>}`
- 400, 503 from code-source: the same status with
  `{"message": <code-source message>, "code": <code-source code>}`
- 502 when code-source is unreachable or answers anything else
- 500 when `SQL_EXAMPLES_CODE_SOURCE_URL` isn't configured

The panel, not the backend, asks for `ext=sql` and `limit=50`, so the
route stays a plain forwarder like the others.

### plugin frontend

- Search box: Superset's `Input` with a search icon and a clear button,
  styled like the schema browser's inputs.
- Searches wait until typing pauses briefly (about 150 ms), and a
  response that arrives after a newer search has started is ignored.
- The tree stays mounted but hidden while results are shown, so its
  expanded folders and loaded contents survive a search.
- Result rows reuse the tree's file actions from 004.
- The shortcut is a `keydown` listener on the document, in the capture
  phase so the editor doesn't swallow it. Neither SQL Lab nor its editor
  (Ace) binds `Ctrl+Shift+S`.

### dev

- The sample repository gains `customers/churn/monthlyChurnRate.sql`, so
  case-change splitting and a deeper folder can be seen by eye.
- `smoke-test.sh` checks that the search route finds a sample file and is
  gated like the others.

## Testing

As 003 and 004 set up, with no new kinds of fake:

- **code-source:** HTTP tests against fixture repos: word splitting (in
  paths and in `q`), every-word and last-word-prefix matching, name
  matches ranking above folder matches, tie order, `matches` ranges,
  `ext`, `limit` and `total`, dot-entries excluded, each
  `invalid_parameter` case, `not_ready`, and added and removed files
  after `POST /refresh`. Word splitting is tested through the API,
  where `matches` shows it directly, rather than on its own.
- **plugin backend:** pytest for forwarding (including repeated `ext`),
  error mapping and the permission gate.
- **plugin frontend:** the panel against a real code-source: results and
  highlighting, the no-results and too-many messages, actions on
  results, the tree restored after clearing, and the keyboard path from
  the shortcut to opening a file. The test forwarder gains the search
  route.

## Decisions

- **Search on the server.** With only paths, the panel could fetch them
  all once and filter in the browser. But 007 and 010 need a server-side
  index anyway, agent clients need search in the API, and a browser
  version would be thrown away.
- **In-memory index, rebuilt on refresh.** Paths for a repository of
  thousands of files index in milliseconds, so there's nothing to gain
  from keeping the index on disk yet. Rejected: an on-disk index (state to
  manage across restarts and clone directories, for no speed-up now).
  010 may change this, since embeddings are expensive to recompute.
- **Filtering on the server, through a generic `ext` parameter.** 003
  filtered in the plugin because listings are small. With a `limit`, that
  would return fewer results than asked, or none, when the top ones are
  non-SQL. `ext` is content-agnostic, as `docs/decisions.md` already
  allows. Rejected: filtering in the plugin and over-fetching.
- **Dot-entries never returned by search**, rather than a parameter to
  include them. Most search tools skip hidden files by default, and no
  client wants them yet. A parameter can be added when one does.
  Rejected: an `includeHidden` parameter now (surface for a guess), and
  returning them for the plugin to filter (breaks `limit`, as above).
- **Folder names match, ranked below file names.** Analysts often know
  the area ("orders") before the name. Rejected: names only (misses
  that), and equal weight (folder matches would crowd out the file the
  user named).
- **Highlight ranges from the server.** Only the server knows how words
  were split and matched. Highlighting in the browser would mean a second
  copy of the word-splitting rule that could drift. Offsets are in code
  points, which suit most clients' languages. Rejected: UTF-16 offsets
  (JavaScript- and JVM-shaped).
- **No search recording in 006.** Searches run as the user types, so
  logging each would mostly record word fragments. 010 can design what
  it needs.
- **`Ctrl+Shift+S` as the shortcut.** It follows SQL Lab's Ctrl-based
  shortcuts and isn't bound by SQL Lab or Ace (checked against the fork's
  Ace bindings). Checked by eye in Chrome. Other browsers may bind it
  (possibly Edge's web capture, Firefox's screenshots); accepted as is. Rejected: `/` (types into the editor),
  `Ctrl+K` and `Ctrl+Shift+F` (bound by Ace and SQL Lab), `Ctrl+Shift+O`
  (bookmarks in Chrome and Firefox).
- **50 results in the panel; API default 20, maximum 100.** More than 50
  wouldn't fit in the panel, and showing the total tells the user to
  narrow the search.

- **Constant scores per word, not BM25.** Each word typed scores by
  the best way it matched: whole word in the name 4, prefix in the name
  3, whole word in a folder 2, prefix in a folder 1. A file's score is
  the sum. That makes "name above folder" a guarantee rather than a
  tendency (BM25's term rarity and field length could reorder results),
  and keeps the order easy to explain. Rejected: Lucene's default
  scoring with a name boost.
- **Matched words in the primary colour, bold**: the colour the action
  buttons turn on hover. Changed after review: bold alone was too subtle
  to see. Rejected: a background highlight like a browser's find, which
  would look foreign next to the schema browser.
- **Our own word splitter, fed to Lucene as tokens**, rather than an
  analyzer built from `lucene-analysis-common`'s word-delimiter filter.
  The same Kotlin function splits paths when indexing, splits `q`, and
  finds the match ranges, so the three can't disagree, and the rule is
  exactly the one specified.
- **Panel tests keep antd's styles out of jsdom's document** (found while
  building). With the search box, antd injects over 1,000 selectors.
  jsdom's selector engine (nwsapi) caches 1,000 compiled selectors in an
  LRU, and `getComputedStyle` walks every rule in the same order, so
  every lookup missed and tests slowed sevenfold. `renderPanel` now puts
  antd's styles in a detached element through `StyleProvider`. No test
  depended on them. Rejected: patching nwsapi's limit (not configurable),
  and rewriting the tree tests' helpers to avoid accessible names (test
  churn, and only a partial fix).

## Implementation notes

- **code-source:** `Words.kt` (word splitting), `PathIndex.kt` (Lucene
  index and queries, `lucene-core` 10.5.1), `search` on `CodeSource`, and
  the `GET /api/v1/search` route in `Application.kt`. `GitCodeSource`
  builds the index during refresh, before swapping in the new snapshot,
  and reuses the previous index when the tree hasn't changed. Tests:
  `SearchTest.kt`.
- **plugin backend:** a `search` route in `entrypoint.py`. `_forward` now
  takes the names of the parameters to pass on, and passes each repeated
  as given. Tests: `test_search.py`.
- **plugin frontend:** file actions moved from `Panel.tsx` to
  `fileActions.tsx` (`useFileActions`, `FileActionButtons`), as a
  separate refactor with no test changes. `SearchResults.tsx` has the
  result list, and `Panel.tsx` the box, the shortcut, and the hidden but
  mounted tree. `searchFiles` in `api.ts` asks for `ext=sql` and
  `limit=50`.
- **Tests changed:** two tree keyboard tests tab once more to reach the
  tree, because the search box now comes before it in tab order (a
  behaviour change from this spec). `renderPanel` wraps the panel in
  `StyleProvider` (see Decisions). `setup.ts`'s comment on hidden
  elements was updated, since the tree is now hidden while searching.
- **dev:** `smoke-test.sh` searches for
  `customers/churn/monthlyChurnRate.sql` and checks the route's gate.
  After review, the sample repository grew to 65 queries over Superset's
  example tables (births, sales, flights, video games, World Bank health,
  the Slack community data, the freeCodeCamp survey, and a few others),
  so search can be tried on something realistic. They use joins, unions,
  CTEs (one recursive), window functions and aggregations, in SQLite's
  dialect, and each one runs against the dev examples database. The
  earlier sample files, written against made-up `orders` and `customers`
  tables, were rewritten over `cleaned_sales_data` at the same paths.
- **Checked by eye:** `Ctrl+Shift+S` in Chrome. Not checked: Firefox and
  Edge.
- **Existing flaky test, not from 006:** "folders offer no file actions"
  (004). Filed as `docs/bugs/001-folder-double-click-test.md`.
- **Known limitations:**
  - The word rule splits a run of capitals before its last capital when
    lower case follows, so `HTTPs` is `htt`, `ps`. That's rare in file
    names, and the rule is what's specified.
  - A digit followed by a capital doesn't start a word (`q4Sales` is one
    word), as specified ("lower-to-upper").
  - `q` over 500 characters shows code-source's message as the error.
  - Old indexes aren't closed explicitly. They're in memory and freed
    with the snapshot, so a search still running on one is safe.

### Later changes

- 007 (step 3) adds `reference`, `function` and `keyword` filters and
  `unqualified` to `/api/v1/search`. `q` may then be empty: with no
  words and no filters there are still no results, but filters alone
  find files. See `docs/api.md`.
- 013 adds each file's outline and date to search results, shows them
  on result rows as the tree does, and adds suggestions for those
  filters, chosen as chips under the search box. Results are now named
  by their path for assistive technology.
- 014 searches parts' names and descriptions too, ranks matches by
  where they are, shows the queries and CTEs that matched under each
  result, and moves a result's folder from below its name into its
  tooltip.
