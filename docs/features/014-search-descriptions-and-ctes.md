# 014: Search descriptions and CTE names

**Status:** done

## Purpose

Let analysts find an example by what it's for and by the parts it's
built from, not only by its file name: "running total" should find the
query whose description says so, and "monthly" the CTE named
`monthly`. Descriptions and CTE names are where curators already say
what an example does (007), so search should use them. Results then
need to say *where* a search matched. They take more of the tree's
shape, so that a matching CTE shows as a row that can be used straight
from the results.

This is the first of 010's "cheaper steps", taken from trying the panel
rather than from 010's relevance set. 010 stays a review, run later
against this.

## Behaviour

### What a search matches

- Words typed are matched, as in 006, against:
  - the file's name
  - the names of its CTEs
  - the descriptions of its queries and CTEs (007)
  - its folders
- Words are split as in 006, in descriptions too. Every word typed must
  match somewhere in the file. Different words may match in different
  places, e.g. one in the file name and one in a CTE's description. The
  last word matches as a prefix, as in 006.
- Folder descriptions (READMEs) aren't searched.

### Ranking

- Each word typed counts by the best place it matched, from best to
  worst:
  1. a whole word of the file name
  2. the start of a word in the file name
  3. a whole word of a CTE name
  4. the start of a word in a CTE name
  5. a whole word of a description
  6. the start of a word in a description
  7. a whole word of a folder name
  8. the start of a word in a folder name
- A file's rank is the sum over the words typed. A word that matches
  better always counts for more than one that matches worse, so for
  one word typed, a file name match always beats a CTE name match,
  which always beats a description match.
- Among files that rank the same, those whose matching descriptions are
  more *about* the words rank higher: the words are rarer in the
  repository's descriptions, and make up more of a short description
  than of a long one. Then, as in 013, those that wrote a table filter
  in full. Then path order, as before.

### Results

Results are still files, ranked, at most 50, as in 006 and 013. Each
shows what it matched in place:

- **The file row** is the tree's file row (008), on one line, with
  matched words of its name highlighted as in 006.
- **The folder is in the row's tooltip**, not on the row (agreed
  2026-10-14): its first line, in small secondary text, above what the
  tree's tooltip says (the description or number of queries, and the
  date). A result at the top level shows no folder. Words that matched
  in the folder are highlighted there more quietly than other matches,
  in bold without the primary colour *(to try)*. Blue on a row then
  always means a match that counts for a lot.
- **Parts that matched are shown under the file row**, as the tree
  shows them (009, 012), and only those:
  - A CTE whose name or description matched gets a CTE row, its name
    highlighted where it matched.
  - In a file of several queries, a query row is shown for each query
    whose description matched, or that has a CTE that matched, with its
    matching CTE rows under it.
  - In a one-query file, matching CTE rows sit directly under the file
    row, as in the tree.
  - Parts are in the order they're written. Parts that didn't match
    aren't shown. Results don't expand or collapse.
- **A description match shows as a snippet** under the row it belongs
  to (the file row for a one-query file's query, or a query or CTE
  row), in small secondary text, with the matched words highlighted
  *(to try)*. It's at most two lines, cut as Snippet of a description
  says. It shows that the description matched, and at least its first
  match. Later matches may be cut off; the full description is still
  the row's tooltip (008). A part whose name matched but whose
  description didn't shows no snippet.
- Part rows behave as they do in the tree: the same names, pills,
  tooltips and actions ("Open in new tab", "Copy to clipboard"), and
  double-click or Enter opens that query or CTE (009, 012).
- The keyboard moves through file rows and part rows as one list, as
  in the tree.
- A search by chips alone (013) has no words to place, so it shows file
  rows only, as now.

### Snippet of a description

An isolated piece of the plugin frontend. Given a description and where
words matched in it (code points, as the API gives them), it gives the
text to show, whether text before it was left out, and where the
matches are in it.

- Runs of whitespace, line breaks and blank lines between paragraphs
  included, become single spaces, and there are none at the start or
  end.
- When the first match ends within the first 60 characters, the text
  starts at the start of the description. Otherwise it starts at the
  start of the third word before the word the first match is in, or at
  the start of the description if there are fewer words before it, and
  text before it was left out. A word here is a run of characters other
  than whitespace, so the start never falls inside one.
- The text runs to the end of the description. The panel shows at most
  two lines of it, ending with "…" when there's more, and shows "…"
  before it when text before it was left out.
- Every match is kept, moved to its place in the text.
- Characters are counted in code points, as the API counts them.

## Acceptance criteria

- [x] Words typed find files by their CTE names and by their queries'
      and CTEs' descriptions, as well as by path, with every word
      matching somewhere in the file
- [x] For one word typed, a file name match ranks above a CTE name
      match, which ranks above a description match, which ranks above a
      folder match
- [x] Among files whose descriptions match the same way, one whose
      description is about the words ranks above one that mentions them
      in passing
- [x] A matching CTE shows as a row under its file's result (under its
      query's row in a file of several queries), with its name
      highlighted, and can be opened or copied from there
- [x] Queries and CTEs that didn't match aren't shown under a result
- [x] A description match shows a snippet with the matched words
      highlighted, under the row it describes, starting near the first
      match when it's far into a long description
- [x] A result's folder is in its tooltip, not on its row, with words
      that matched there highlighted more quietly than other matches
- [x] The keyboard moves through result and part rows, and Enter opens
      the active one
- [x] A search by chips alone shows file rows only
- [x] code-source reports matches in parts in language-neutral terms

## Out of scope

- Folder descriptions (READMEs) in search
- Plurals, stemming, typo tolerance and synonyms (010)
- Requiring every word to match within one part
- Parts as results in their own right, ranked separately from their
  files
- Expanding a result to show the parts that didn't match
- Marking which tables matched a filter (013 left this out too)
- Suggestions (013) for CTE names or description words
- Highlights in tooltips

## Components and API changes

### API (update `docs/api.md`)

`GET /api/v1/search` matches `q` against named parts and descriptions
too, as Behaviour describes, with no new parameters. Its text says
which places it searches, how they rank, and that each language's
analyzer decides what its parts are.

Each result gains `parts`: each part of the file where a word of `q`
matched its name or description, in the order they're written in the
file:

```json
"parts": [
  {
    "range": { "start": 4, "end": 8, "startColumn": 6, "endColumn": 1 },
    "name": "monthly",
    "nameMatches": [{ "start": 0, "end": 7 }],
    "description": "One row per month.",
    "descriptionMatches": [{ "start": 12, "end": 17 }]
  }
]
```

- `range` is the part's range, as in its outline, which is how a client
  finds the part there. For SQL, a query or a CTE.
- `name` is null for a part without one (a SQL query).
  `description` is null when it has none.
- `nameMatches` and `descriptionMatches` are like `matches`: `[start,
  end)` offsets in code points into `name` and `description`, in order
  and not overlapping. Empty when nothing matched there.
- Empty when `q` has no words.
- `matches` stays as it is: matches in `path`.

### code-source

- The analyzer interface gains *parts*, which are the generic idea
  behind search: each has a range, an optional name and an optional
  description. The SQL analyzer gives queries (no name) and CTEs.
  Nothing outside the SQL analyzer knows what a CTE is.
- `PathIndex` adds fields for part names and part descriptions, split
  into words by the same function as paths. A word's query gains those
  fields in the tiers above, which stay constant scores, as 006 decided.
- Description relevance is BM25 on the descriptions field, scaled
  into a fraction small enough never to overturn a tier. Tiers differ
  by at least 1, as now. The full-reference score from 013 is smaller
  still.
- Part matches are worked out after the search, for the hits returned
  only, by the same word-splitting function, as path `matches` are.

### plugin backend

No change: the search route forwards the response as it is.

### plugin frontend

- `api.ts`: `SearchHit` gains `parts`.
- `SearchResults.tsx`: each result renders its file row, then a row
  for each matching part, found in the hit's outline by `range`, with
  the tree's query and CTE rows (from `Panel.tsx`) reused, as 013
  reused `RowLabel`. A part whose range matches nothing in the outline
  is left out. Both come from the same snapshot, so that would be a
  code-source bug, not a race.
- A snippet function: given a description and its matches, the text to
  show, with where it was cut. An isolated piece with its own promises
  (Behaviour, snippet) and its own tests.

### dev

- `smoke-test.sh` checks that a search for a sample CTE's name finds
  its file, with that part in `parts`.

## Testing

As 006 and 013 set up, with no new kinds of fake:

- **code-source:** HTTP tests against fixture repos: matching by CTE
  name and by query and CTE descriptions; words matching in different
  places; each tier ranking above the next; description relevance
  breaking ties; `parts` with ranges, names, descriptions and match
  offsets, in file order; no `parts` for filter-only searches; a part
  added by a refresh being found.
- **Relevance set (a start on 010's):** 15–20 searches an analyst might
  type, over a fixture repo copied from `dev/sample-repo`, each
  asserting what it should find and what should rank above what, never
  exact orders or scores, so tuning doesn't churn them. It stays for
  010 and later search changes to check against.
- **plugin frontend:** the panel against a real code-source: CTE and
  query rows under results, only those that matched; highlights in
  names; snippets; the folder in the tooltip, with quieter highlights;
  actions and Enter on part
  rows; chips alone showing file rows only. The snippet function gets
  its own tests.
- **Expected test changes:** the panel's `results()` helper reads every
  option in the "Examples" group. With part rows in the group, it
  should read file rows only, and a new helper reads part rows. If a
  006 or 013 fixture file's CTE names or descriptions now match those
  tests' words, their expected results change. That follows from this
  spec, and each case will be listed.

## Open questions

None left. Those settled by eye are under Decisions.

## Decisions

- **A feature of its own, not 010** (agreed 2026-10-13). 010 stays the
  review, and judges this one later.
- **Folders keep matching, at the lowest tier** (agreed 2026-10-13).
  `video share` finds `video_games/publisher_market_share.sql` only
  through its folder. What changes for folders is how they're shown.
  Rejected: dropping folder matches (loses such searches).
- **The folder moves from under the name into the tooltip** (agreed
  2026-10-14). It's supplementary: it says where a file is, and the
  hierarchy it implies sits above the name, not below it. In the
  tooltip it's there on hover and on the keyboard's active row, and the
  row is one line, like the tree's. This changes 006's "folder path in
  smaller text below". Rejected: showing it above the name on hover
  (the row would grow under the pointer, moving every row below it, and
  keyboard users would need the same); dropping it (results with the
  same name, which the sample repository has, couldn't be told apart).
- **Part rows are options in the same flat list as file rows**
  (agreed 2026-10-14), indented to show which file they're under. ARIA
  doesn't allow a group of options within the "Examples" group, and one
  list keeps 006's keyboard handling. A screen reader announces a CTE
  row by its name alone, straight after its file's row. Rejected: the
  results as a `tree` with levels (a rewrite of the results list, for
  context that the order already gives).
- **Tiers, with BM25 only to break ties.** Tiers keep 006's guarantee
  that a better place to match always ranks higher, and keep the order
  explainable. BM25 within a tier stops common description words from
  leaving large ties in path order. Rejected: BM25 over all fields with
  boosts (006 rejected it, since it can reorder tiers); tiers alone
  (prose ties become arbitrary).
- **Matching per file, not per part.** Words may match in different
  parts. Requiring them in one part is more precise, but much stricter
  and harder to explain when a word of the file name is typed alongside
  a word of a description.
- **Results are files with their matching parts under them, ranked**
  (agreed 2026-10-13). Rejected: a filtered tree (loses ranking, and
  50 results across folders is mostly folder rows); parts as results of
  their own (one file several times, and against 013's "results are
  files").
- **Snippets for description matches, to try first** (agreed
  2026-10-13). Descriptions live in tooltips, where a highlight can't
  be seen without hovering. Alternative kept in reserve: a blue icon on
  the row, with matches highlighted in the tooltip.
- **Parts are identified by range, and carry their text.** Ranges are
  already generic and in every outline, so a client finds the part
  without knowing the language. Carrying `name` and `description`
  means a client reading only generic fields can show a match without
  knowing the language's outline shape. Rejected: ids like
  `query 0 / monthly` (SQL's structure in a generic API); offsets alone
  (a generic client couldn't place them).
- **Common words in descriptions are searched like any other**
  (agreed 2026-10-13). Every word typed must match, so skipping "of"
  or "by" in descriptions would make a search for "share of sales"
  depend on whether they're skipped in the query too. They also appear
  in file names (`top_products_by_month`). To revisit with 010's
  relevance set.
- **Parts stay inside search, not in the outline** (agreed
  2026-10-13). A generic `parts` list in every outline would let any
  client read a file's named pieces, but nothing needs that yet. Search
  results are where parts appear until a client does.

- **Settled by eye** (2026-10-14): the folder's bold-without-blue
  highlights in the tooltip are quiet enough; two-line snippets fit the
  panel, so the blue icon isn't needed; and results with the same name
  looking alike until hovered is left as it is. It's expected to be
  rare, and hovering one shows why. Showing the folder on the row for
  such results stays the fallback.
- **Snippets aren't read by screen readers** (agreed 2026-10-14). A
  row's accessible name is set explicitly, so text inside it isn't
  read. Adding the snippet to the row's description was the
  alternative; it isn't needed for now.
- **Parts are worked out from the outline, not in the analyzer.**
  `Outline.parts()` gives them for any language, and `SqlOutline.parts()`
  is the only SQL-specific piece. code-source has no analyzer interface
  of its own: `SqlAnalyzer` produces outlines. They aren't serialised,
  so outlines in responses are unchanged.
- **Scores are layered.** Each word scores 1 to 8 by where it matched,
  in whole numbers. Description relevance is BM25, scaled so its sum
  stays under 0.5: Lucene's BM25 scores a word below its idf, which is
  at most ln(1 + N) for N files with descriptions, so each word is
  scaled by 0.5 / (words × ln(1 + N)). References written in full (013)
  add a share of 0.0001 in all, where they added 0.001 each, so they
  stay under relevance. Relevance that differs by less than that counts
  as tied. Rejected: transforming scores with `FunctionScoreQuery`
  (needs `lucene-queries`, which 006 kept out); sorting on separate
  keys (Lucene sorts by one score).
- **Only whole words count for relevance.** A last word still being
  typed adds relevance once it's complete. Scoring prefixes with BM25
  means expanding to every matching term, for ties that change as the
  word is typed.
- **One description field per file.** BM25 sees all of a file's
  descriptions as one text, so each mention in a file with many
  described CTEs counts a little less.
- **The relevance set is a copy of the dev sample repository**
  (`code-source/src/test/resources/relevance/`), not the sample itself,
  so editing the sample can't break code-source's tests, and
  code-source stays extractable. The two may drift; the copy is what
  the set was written against.

## Implementation notes

- **code-source:**
  - `Outline.kt`: `Part` and `Outline.parts()`, from
    `SqlOutline.parts()` (each query, then its CTEs).
  - `PathIndex.kt`: `part_name` and `part_description` fields; eight
    constant scores per word; BM25 relevance on descriptions as `SHOULD`
    clauses (`relevance`); `FULL_REFERENCES` shared among a search's
    references. `PathIndex` keeps the outline lookup it's built with, and
    works out `parts` for the hits it returns, with the same `matches`
    function as paths.
  - `CodeSource.kt`: `Hit.parts`, a list of `PartHit`. `Application.kt`
    serialises them, ranges as outlines have them.
  - Tests: `SearchPartsTest.kt` (matching, tiers, relevance, `parts`) and
    `RelevanceTest.kt` (18 searches over the copy of the sample
    repository).
- **plugin frontend:**
  - `api.ts`: `SearchHit.parts`, and `partHitsOf`, which finds each part
    in the hit's outline by range.
  - `SearchResults.tsx`: a result is a list of rows (`resultRows`): its
    file's, then its matching queries' and CTEs', with the tree's
    `QueryLabel` and `CteLabel`, icons and indentation, and snippets.
    The keyboard, Enter and double-click work on every row.
  - `snippet.ts`: Snippet of a description. Tests: `snippet.test.ts`.
  - `rowLabel.tsx`: `CteLabel` takes a highlighted name, and `RowLabel`
    a folder to head its tooltip.
- **Tests changed:**
  - The panel's `results()` helper reads file rows only, by their names
    ending in `.sql`; `resultRows()` reads every row (as Testing
    planned).
  - 006's "each result highlights the parts of its path that matched"
    finds the folder's highlight in the tooltip, not a folder line, as
    this spec changed.
  - A step 5 test of 014's own counted every highlight in a CTE row as
    its name's; with snippets it counts the snippet's too.
  - No other existing test changed. 013's reference tests pass with the
    smaller full-reference score.
- **dev:** `smoke-test.sh` checks that a search for `ranked` finds
  `video_games/publisher_market_share.sql`, with `ranked` among its
  parts.
- **Checked by eye** in the dev stack: part rows, snippets, the folder
  in the tooltip.
- **Known limitations:**
  - A part is listed when any word typed matched it, common words
    included, so `revenue per` lists every part whose description says
    "per".
  - Snippets show the first match and what follows it in two lines;
    later matches in a long description may be cut off.
  - Results with the same name look alike until hovered (see Decisions).
  - Scores are floats. With hundreds of words typed, the
    full-reference layer would lose precision.
