# 013: Search by tables, functions and keywords

**Status:** done

## Purpose

Let analysts find examples by what they use, not only by their names:
the tables they read ("show me queries over `orders`"), and the
functions and keywords they use ("how do people use `QUALIFY`", "an
example of `LISTAGG`"). code-source already answers these searches
(007); the panel's search box can only search names (006). While
search results are being reworked, they also gain 008's tables and
dates, so a result can be judged as a tree row can.

## Behaviour

### Suggestions

- As the analyst types, the word being typed (the text after the last
  space) is looked up among the tables, functions and keywords the SQL
  examples use. Up to 5 matches are suggested above the results, in a
  group of their own, each with its kind and how many examples it would
  find: "Table `orders` · 12", "Function `ROUND` · 4", "Keyword
  `ORDER BY` · 9".
- A table is suggested when the word starts it, or starts any shorter
  name made of its last parts: `ord` suggests `orders`; `sales.or`
  suggests `sales.orders`. Functions and keywords are suggested when
  they start with the word. Case is ignored.
- Suggestions are ordered by how many examples each finds, most first,
  then alphabetically. The number is how many results choosing it alone
  would give.
- Functions and keywords stay separate kinds. When a name is both, both
  are suggested.
- Name results show below the suggestions as in 006, for the whole text
  as typed, so an analyst who wasn't looking for a table loses nothing.

### Filters

- Choosing a suggestion (click, or Enter on it) turns the word into a
  filter: the word leaves the box, and a chip for it appears under the
  box, saying its kind and value ("Table: orders"). Focus returns to the
  box.
- Results are the SQL files that match every filter and every word left
  in the box, as code-source matches them (007): a table filter for
  `orders` finds files that wrote `orders`, `sales.orders` or
  `iceberg.sales.orders`; one for `sales.orders` also finds files that
  wrote just `orders`, ranked below those that wrote it in full.
- Filters with no words in the box are a search in their own right:
  results show, ranked as code-source ranks them.
- Each chip has a remove button ("Remove table orders"). Backspace in an
  empty box removes the last chip. Choosing the same suggestion twice
  doesn't add a second chip.
- Escape clears the words and the filters, and brings back the tree, as
  in 006. Clearing the box with its clear button removes the words only.

### Results

- Results behave as in 006: the same actions, keyboard use, limit of
  50 and messages.
- Each result shows what a file's row shows in the tree (008), beside
  its highlighted name and folder: its tables as pills (for a file with
  one query), and when it last changed, sharing the row's right end
  with the actions. Hovering it shows the tree's tooltip: the query's
  description or how many queries it has, and the full date.
- Results don't mark which of their tables matched a filter. Every
  result matches every chip, so the chips say why it's there.

### Keyboard

- Down from the box moves into the suggestions, then on into the
  results, as one list. Up from the first suggestion (or the first
  result, when there are no suggestions) goes back to the box.
- Enter on a suggestion chooses it. Enter on a result opens it, as in
  006.
- The search box and its chips stay at the top of the panel while the
  panel scrolls, so the box is never scrolled out of sight. Moving with
  the keyboard keeps the active row in view below them, never under
  them.

## Acceptance criteria

- [x] Typing part of a table's name suggests it, whether the word starts
      the name as written or a shorter name made of its last parts
- [x] Typing part of a function or keyword suggests it, ignoring case,
      as separate kinds
- [x] Each suggestion says how many examples choosing it would find, and
      suggestions are ordered by that number
- [x] Choosing a suggestion, by pointer or keyboard, turns the word into
      a chip and shows only the examples that match it
- [x] A table chip finds examples that wrote the table in full or in
      part, those in full first
- [x] Chips combine with each other and with words in the box: every
      one must match
- [x] Chips can be removed one at a time, by their button or by
      Backspace in the empty box; Escape clears everything
- [x] Results show their tables and last-changed date, with the tree's
      tooltip
- [x] Keyboard movement never hides the active row under the search
      box and chips (checked by eye: jsdom has no layout)
- [x] The search route forwards the new parameters, and the
      suggestions route is gated like the others

## Out of scope

- Examples for the database or schema in use in SQL Lab (a different
  feature: the panel would need the editor's database from Superset,
  and code-source would need to know which tables live where)
- Prefix syntax in the box (`table:orders`), and a separate filter
  control. Suggestions are the one way in.
- Suggestions narrowed by the filters already chosen (only values that
  occur alongside them). Counts are for each value alone.
- Marking matched tables in results
- Free-text search over descriptions or CTE names (a 007 follow-up, for
  010's relevance set to judge)
- Ranking by what's in the analyst's editor or schema
- Changing what code-source matches in search, or how it ranks
- Query rows (008) under results with several queries. Results are
  files, as in 006.

## Components and API changes

### API (add to `docs/api.md`)

**Search results carry outlines.** Each result of `GET /api/v1/search`
gains `outline` and `modified`, as a tree listing's file entries have
them. The panel needs them for pills, dates and tooltips; the
alternative, a tree or file request per result, would be up to 50
requests per keystroke.

**New: `GET /api/v1/suggestions`**

Purpose: values that a search's outline filters (`reference`,
`function`, `keyword`) can take, starting with what a client has typed,
with how many files each would find. For clients that help users build
a search. It lists outline values, not SQL concepts.

Query params:
- `q` (string): what's typed. Matched as a prefix, ignoring case
  (keywords also ignore runs of spaces). Absent or blank means no
  suggestions. Over 500 characters is 400 `invalid_parameter`.
- `field` (string, optional, repeatable): which of `reference`,
  `function`, `keyword` to suggest values of. Absent means all three.
  Anything else is 400 `invalid_parameter`.
- `ext` (string, optional, repeatable): as for search. Counts only
  files with one of these extensions, and leaves out values only other
  files have.
- `limit` (integer, optional): most suggestions, 1 to 100. Default 10.

Response 200:
```json
{
  "suggestions": [
    { "field": "reference", "value": "orders", "files": 12 },
    { "field": "function", "value": "ROUND", "files": 4 }
  ]
}
```

- `field` is the search parameter the value goes in, so a client can
  pass `<field>=<value>` straight to search.
- `value` is in the form search compares: references with quotes
  removed and lower case, parts joined by `.`; functions and keywords
  upper case, keywords with single spaces.
- For references, `q` is matched against each name a reference can be
  searched by (`orders`, `sales.orders`, `iceberg.sales.orders` for a
  file that wrote `iceberg.sales.orders`), and those are the values
  suggested.
- `files` is the `total` a search with only that value (and the same
  `ext`) would give, `unqualified` at its default.
- Ordered by `files`, most first, then `field` in the order above, then
  `value` in code-point order. At most `limit` of them.
- Like search, files under a dot-entry don't count.

Errors: 400 `invalid_parameter` as above, naming the parameter in
`message`; 503 `not_ready`.

### code-source

- `Hit` gains the file's outline and last-modified time, filled from
  the snapshot, as listings are.
- `CodeSource` gains `suggest`. `PathIndex` finds candidate values by
  enumerating its outline fields' terms with the prefix, and counts each
  with the same query search uses, so a count can't disagree with the
  search it leads to.

### plugin backend

- The search route forwards `reference`, `function`, `keyword` and
  `unqualified` too, each repeated as given.
- New route `GET /extensions/quillfern/sql-examples/code-source/suggestions`
  forwards `q`, `field`, `ext` and `limit` to `GET /api/v1/suggestions`,
  gated by `requires_sql_lab_read` and mapping responses as the search
  route does (006).
- The panel, not the backend, asks for `ext=sql` and `limit=5`.

### plugin frontend

- `api.ts`: `searchFiles` takes the filters; new `suggest`.
- The search box keeps its words and a list of chosen filters. The
  panel is searching while either is non-empty.
- Suggestions are fetched with the same pause and overtaking rule as
  search (006), alongside it.
- Results reuse the tree row's contents (008), with the highlighted
  name and folder in place of the plain name.
- `test/backend.ts` gains the suggestions route, and forwards only the
  parameters each route's contract names, as the real backend does.

### dev

- `smoke-test.sh` checks the suggestions route finds a sample table and
  is gated, and that a search by that table finds a sample file.

## Testing

As 006 and 008 set up, with no new kinds of fake:

- **code-source:** HTTP tests against fixture repos. Suggestions:
  prefix matching per field, references by any of their names, keyword
  spacing, counts equal to search totals (including unqualified
  references), `ext`, ordering, `limit`, dot-entries, each
  `invalid_parameter` case, `not_ready`, and values added and removed
  by a refresh. Search: results carry outlines and dates.
- **plugin backend:** pytest for the new search parameters (repeated),
  and the suggestions route's forwarding, error mapping and gate.
- **plugin frontend:** the panel against a real code-source: suggestions
  for tables (as written and by shorter name), functions and keywords
  with counts; choosing by pointer and keyboard; chips filtering,
  combining with words and each other; removing chips by button and
  Backspace; Escape clearing all; results showing tables, dates and
  tooltips.
- **Expected test changes:** 006's `results()` helper reads each
  result's text content, which would now include dates and pills. It
  reads results as the tree helpers do (accessible names) instead. The
  006 tests themselves don't change.

## Decisions

- **"Databases" means the tables an example reads** (agreed
  2026-10-13): the outline's references. Examples for the database in
  use in SQL Lab are a different feature (Out of scope).
- **Suggestions, turned into chips** (agreed 2026-10-13). Discoverable
  by anyone who types, with no syntax to learn. Rejected: prefixes in
  the box (`table:orders`; quick for those who know them, invisible to
  everyone else), both (more to build and test for a second way to do
  the same thing), and a filter control beside the box (more clicks,
  and it needs the same values endpoint).
- **Functions and keywords stay separate, labelled kinds** (agreed
  2026-10-13), matching the API. Merging them would need an OR in the
  API or two searches merged in the panel, for a distinction analysts
  can simply ignore.
- **Results gain 008's tables and dates, without marking matches**
  (agreed 2026-10-13). Every result matches every chip, so marking
  would repeat the chips.
- **Outlines in search results**, rather than one request per result.
  It mirrors tree listings, which carry outlines for the same reason
  (007).
- **A suggestions endpoint, not a list of all values.** The panel could
  fetch every value once and filter in the browser, but repositories
  with thousands of tables would make that a large download, and agent
  clients want the same help server-side. Counts come from the search
  query itself, so they always equal the results the suggestion leads
  to.
- **Suggestions for the word being typed, not the whole text.** Table
  names have no spaces, and keywords with spaces (`ORDER BY`) are found
  by their first word.
- **Chips under the box, not inside it.** The panel is narrow, and chips
  inside an input crowd out the text. Expected to be revisited by eye,
  as 008's look was.

- **Suggestions and results are one listbox, in two groups**
  ("Suggestions", "Examples"), so Down and Up move through both without
  a second focus stop. Rejected: two listboxes with focus handed between
  them (more keyboard code, and a screen reader would announce two
  lists).
- **A suggestions request runs alongside each search, and both are
  shown together**, after the same pause, so the list changes once per
  pause rather than twice. When the search fails, no suggestions are
  shown: the error replaces the list, as in 006. A failed suggestions
  request alone shows no suggestions and no error, since they're only a
  help.
- **Results are named by their path** (`aria-label`), and described by
  their tables and date, as tree rows are named by their name. The
  folder line is visible text only.
- **The test forwarder passes on only each route's parameters.** It
  forwarded the whole query string before, so a frontend test could pass
  while the real backend dropped a parameter. That's what the real
  route does (006's `_forward`), so the contract stays the same.

- **The search box sticks to the top of the panel** (agreed
  2026-10-13, after review). In a long list, Up to the first suggestion
  scrolled that row to the top of the panel, which carried the box out
  of sight. Keeping the box in view fixes that and helps generally. The
  rows' scroll margin is the sticky part's height, measured as the chips
  wrap, because scrolling a row into view ignores what's stuck over it.
  The panel's title and Reload still scroll away, so the stuck part
  stays small. Rejected: just scrolling the box back into view when
  moving to the first row (fixes the symptom only); a list that scrolls
  inside the panel (a second scrollbar in a narrow panel).

## Implementation notes

- **code-source:** `PathIndex.suggest` enumerates the outline fields'
  terms (`reference_suffix`, `function`, `keyword`) from the prefix and
  counts each with the query `search` builds (now `PathIndex.query`), so
  counts equal search totals. `Hit` carries the outline and date, which
  `GitCodeSource.search` fills from the snapshot. Route
  `GET /api/v1/suggestions` in `Application.kt`, which now shares `ext`
  and `limit` parsing with search. Tests: `SuggestionsTest.kt`, and a
  test in `SearchByOutlineTest.kt` that results match their listing.
- **plugin backend:** `search` forwards the outline filters; new
  `suggestions` route. Fixture repo gains `queries/by_region.sql`, which
  reads `sales.orders`. Tests: `test_search.py`, `test_suggestions.py`.
- **plugin frontend:** `filters.tsx` (chips, field names, the word being
  typed), `SearchResults.tsx` (suggestions group, results reusing
  `RowLabel`), `Panel.tsx` (filter state, Backspace and Escape),
  `api.ts` (`suggest`, filters in `searchFiles`). `RowLabel` and
  `describedBy` take a `rowKey`, so a result's ids don't clash with its
  file's row in the hidden tree, and `RowLabel` takes the highlighted
  name.
- **Tests changed:** the backend's `test_returns_code_sources_results`
  spelled out code-source's search response, which gained fields; it
  now compares with code-source's own body, as the file route's test
  does. The panel's `results()` helper read results' text content,
  which now includes dates and pills, and every option in the list; it
  now reads the accessible names of the "Examples" group. No 006 test
  body changed.
- **dev:** `smoke-test.sh` checks that the suggestions route suggests
  `cleaned_sales_data`, that a search by it finds
  `orders/top_products.sql`, and that the route is gated.
- **Checked by eye** in the dev stack: chips, suggestions, and
  results' pills and dates.
- **Follow-up: the sticky search bar needs a fork change**, deferred
  until the fork is next revisited. The fork's
  `ContentWrapper` (around `sqllab.leftSidebarBottom` in
  `SqlLab/components/AppLayout`) has `overflow: auto` but no bounded
  height, so it grows with the panel and antd's Splitter panel scrolls
  instead. Sticky elements stick to the nearest ancestor with
  `overflow` set, here the wrapper that never scrolls, so the bar
  scrolls away. Giving `ContentWrapper` `height: 100%` makes it the
  element that scrolls. Until then, rows still stop below the bar's
  height when scrolled into view, but the bar itself doesn't stick.
- **Known limitations:**
  - Suggestion counts are for each value alone. With chips chosen, a
    suggestion can lead to fewer results than it says (see Out of
    scope).
  - Keywords with spaces are suggested by their first word only:
    `group b` looks up `b`.
  - A one-letter word counts every value starting with it, one count
    query each. That's fast for the sample repository; not measured on
    a large one.
  - Result names truncate with an ellipsis, like tree rows, where 006
    wrapped them. Highlights past the cut aren't seen.
