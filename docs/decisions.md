# Decisions

Decisions that affect more than one feature. Newest at the bottom. One entry
each: what was decided, why, and what was rejected.

<!--
## YYYY-MM-DD: Short title

Context: ...
Decision: ...
Alternatives considered: ...
-->

## 2026-10-10: One repository, extract code-source later

Context: code-source will eventually be reused outside Superset.
Decision: Start as one repo with `plugin/` and `code-source/` as separate
components, connected only through `docs/api.md`.
Alternatives considered: two repos from the start. Rejected for now because
the API will change often early on, and changes across both sides are easier
to make and review in one commit.

## 2026-10-10: The server is a code source, not a SQL or git API

Context: the server's main long-term use is semantic search over code
repositories for AI agents. The Superset panel is its first client.
Decision: the API is modelled as a read-only source of code: list/browse
files, fetch file contents, and (later) search. Git is the first backend but
stays behind the repository-access interface. Version-control concepts
(commits, branches, history, diffs) are not part of the API. SQL-specific
behaviour, such as interpreting file contents, belongs in the plugin, or at
most in generic, content-agnostic parameters (e.g. filter by extension).
Alternatives considered:
- A SQL-examples API (e.g. `/examples`, `/queries`). Rejected because it
  would tie the server to this client and make extraction harder.
- A git-shaped API (refs, trees, commits). Rejected because clients want to
  find and read code, not manage a changing history. It would also lock the
  API to git as a backend.

## 2026-10-10: code-source in Kotlin, Ktor, Gradle

Context: code-source starts as a small read-only HTTP service, and later grows
text and semantic search for agents.
Decision: Kotlin on the JVM, Ktor for HTTP, Gradle with the Kotlin DSL. JGit
for git access. Lucene is the expected route for text and vector search when
they arrive. MCP, if needed, via the official Kotlin SDK.
Alternatives considered:
- Python + FastAPI: richer ML ecosystem for quick experiments, but nothing
  here strictly needs it. Embeddings will probably come from an external
  model API, and the maintainer reviews Kotlin more fluently. Heavy ML work
  can run as a sidecar behind the repository-access interface if ever needed.
- TypeScript/Node: same language as the plugin, but sharing code would work
  against clean extraction, and its search/ML options are weaker.
- Go: good single-binary story, but no advantage over the JVM for git or
  search here.
- Spring Boot / http4k / Javalin: Ktor chosen as lightweight and
  Kotlin-native. Spring is more than this service needs.

## 2026-10-10: One file tree per code-source instance

Context: future clients (agents) may want code from more than one repository.
Decision: the API exposes a single file tree, and repository identity doesn't
appear in API paths. If several repositories are needed later, either the
server presents them as top-level directories of that one tree (a backend
concern, invisible to the API), or several instances are run.
Alternatives considered: namespacing by source from the start
(`/api/v1/sources/{id}/...`). Rejected for now. It complicates every
endpoint and the plugin's config for a need that may never arise, and the
directory approach can handle multiple repos without an API change.

## 2026-10-10: code-source clones and refreshes the repository itself

Context: code-source needs a local copy of the repository and has to keep it
current.
Decision: remote URL, credentials and ref come from environment variables.
code-source clones on start, fetches on a configurable interval, and also
fetches when asked through a refresh endpoint. It always serves the
configured ref. The refresh endpoint is operational (it triggers a
fetch). It doesn't expose version-control concepts to clients.
Alternatives considered:
- Mounted volume kept current by something else: simpler, but needs an
  extra moving part in deployment and makes freshness someone else's
  problem.
- Webhook-triggered fetch only: needs the git host to reach the server. The
  on-demand endpoint can serve as a webhook target later if wanted.

## 2026-10-10: Plugin reaches code-source only through per-operation backend routes

Context: the browser must never call code-source directly (002), and
code-source has no auth.
Decision: the plugin's backend (a Superset extension backend, served under
`/extensions/quillfern/sql-examples/`) has one route per code-source
operation the panel uses. Each forwards to code-source at the Superset
config key `SQL_EXAMPLES_CODE_SOURCE_URL`. Every route requires `can_read`
on `SQLLab`, so anyone who can use SQL Lab can use the panel and nobody
else can. The check is our own decorator, not FAB's `@protect()` on a
`SQLLab`-named API, because that would make FAB delete SQL Lab's other
permissions (see 002).
Alternatives considered: a generic proxy (exposes every code-source
endpoint, including operational ones, to SQL Lab users); a permission of
our own (only Admin gets it by default).

## 2026-10-10: How the plugin is tested

Context: the plugin's frontend runs inside Superset's page, and its
backend runs inside Superset's Flask app. Neither runs on its own (003).
Decision: test each half against a real code-source and real git fixture
repos, and split at our own backend route.
- Backend: pytest in a Superset-based container (Compose `test`
  profile), with the built extension loaded by a real Superset app.
- Frontend: Jest on the host renders the registered panel. Our backend
  route is replaced at `fetch` by a forwarder that implements the route's
  contract from the feature spec. `@apache-superset/core` is faked at the
  module boundary. Both halves test the route against the same contract,
  so it must stay written down in the spec.
- End to end: `dev/smoke-test.sh` only, plus checks by eye.
Alternatives considered: Playwright against the Compose stack for panel
behaviour (slow, and it would turn the few end-to-end checks into a full
suite); frontend tests only (would leave the backend's error mapping and
permission gate untested).

## 2026-10-11: Secrets reach code-source as files

Context: code-source needs credentials for its remote (005), and will
likely need more secrets later (e.g. an embeddings API key for semantic
search).
Decision: a secret is never an environment variable's value. A `_FILE`
variable names the file that holds it, which suits k8s secrets mounted as
volumes. code-source reads the file each time it needs the secret, so a
rotated secret takes effect without a restart. Non-secret settings (e.g.
`REPO_USERNAME`) stay plain variables. This refines the 2026-10-10
decision that credentials come from environment variables.
Alternatives considered: secrets as variable values, or both forms
(rejected: environment values show up in `env`, process listings and
crash dumps, and two ways to configure is one too many); a fixed mount
path such as `~/.ssh` (rejected: ties deployments to particular file
names, see 005).

## 2026-10-12: Search indexes live with the served tree

Context: 006 adds search over paths. 007 will add outlines to search,
and 010 may add embeddings.
Decision: code-source keeps a Lucene index alongside each snapshot of
the served tree. It's built during refresh, before the snapshot is
swapped in, so the tree and its index always match, and searches during
a refresh use the previous pair. One document per file, with separate
fields per kind of thing searched (file name and folders for now), so
later features add fields rather than new indexes. Word splitting is one
generic function (letters and digits, split at case changes) shared by
indexing, queries and match ranges. The index is in memory for now.
Alternatives considered: an on-disk index (state to manage across
restarts for no gain while only paths are indexed; 010 may need it,
since embeddings are expensive to recompute); filtering and searching
in the browser (007, 010 and agent clients need search on the server).

## 2026-10-12: Panel tests keep antd's styles out of the document

Context: jsdom's selector engine caches 1,000 compiled selectors.
Superset's antd styles exceed that once a few components are used (006
added `Input`), and every `getComputedStyle` call then recompiles every
selector. Testing Library calls it for accessible names.
Decision: `renderPanel` renders inside antd's `StyleProvider` with a
detached container, so antd's CSS never reaches jsdom's document. Panel
tests mustn't depend on antd's styles. Our own styles (Emotion) and the
`hidden` attribute still apply. This refines "How the plugin is tested".
Alternatives considered: patching nwsapi (its limit isn't configurable);
fewer accessible-name queries in tests (test churn, partial fix).

## 2026-10-13: code-source may understand languages, through pluggable analyzers

Context: 007 needs code-source to understand SQL (tables, CTEs, keywords,
functions) so that the panel, search and agent clients can share one
outline of each file.
Decision: language support is pluggable. Language-specific code lives only
in analyzers behind one interface, SQL being the first. Every outline has
the same language-neutral generic fields (language, keywords, functions,
references), so a client can use them without
knowing the language. Language-specific detail sits in an object keyed by
the outline's `language` (e.g. `"sql": {...}`), and generic field names
are never language names.
Nothing Superset-specific, as before. This replaces "nothing SQL-specific
in code-source's API or code" from the 2026-10-10 decision that the server
is a code source, and the matching line in `vision.md`.
Alternatives considered: analysing SQL in the plugin (Superset already
ships sqlglot), which keeps code-source language-blind but leaves agent
clients and server-side search without outlines.

## 2026-10-13: Python tools run as subprocesses of code-source

Context: 007 needed sqlglot, which has no JVM equivalent, from Kotlin.
Later features (embeddings in 010, other analyzers) may want Python
libraries too.
Decision: code-source stays Kotlin and runs Python tools as
subprocesses: once per refresh, with all the work in one call (JSON on
stdin, one JSON line per item on stdout, diagnostics on stderr), behind
a contract written in the feature's spec and versioned. The Python
lives in `code-source/analyzers/`, with pinned dependencies in a venv
that code-source's Gradle build creates in place, never copies; nothing
outside code-source configures it. code-source treats the tool as
fallible: a time limit, a cap on output, bounded waits after killing
it, and output decoded strictly, so any failure means partial results,
logged, never a failed refresh or request. 007's Implementation notes
record what the experiment found.
Alternatives considered:
- Rewriting code-source in Python: replaces working code and its tests
  for the sake of one library.
- A long-running sidecar service over HTTP: another process to run,
  monitor and secure, for work that happens once per refresh.
- JVM-only parsers: none matched sqlglot's dialects and scope analysis.

## 2026-10-13: Parts of a file are cut by the outline's line ranges

Context: 012 takes one query of a file, and 009 will take CTEs. Both cut
text out of a file's contents.
Decision: clients cut parts by the outline's line ranges, from the
outline that comes with the fetched content, never a listing's. A part
includes the comment its description came from, which code-source gives
as `descriptionRange`. Whole lines are taken, as written. In the plugin,
`lines.ts` does the cutting. Where whole lines aren't enough (a CTE's
first line can start with `WITH`), ranges gain more detail without
changing their shape.
Alternatives considered: the plugin finding description comments itself
(repeats 007's attribution rules, which would drift); code-source serving
the cut text (fixes one client's idea of a part into the API); character
offsets now (more than whole queries need).

## 2026-10-13: Ranges carry columns

Context: 009 cuts CTEs out of files, and whole lines aren't enough: a
first CTE's line starts with `WITH`, and its last line often ends `),`
or goes on `) SELECT`.
Decision: every `range` in an outline gains `startColumn` and
`endColumn`: 1-based, inclusive, in code points, like lines and search's
offsets. `descriptionRange` stays whole lines. Clients cut a part from
its range's columns, and its description's whole lines. This refines
"Parts of a file are cut by the outline's line ranges", as that
decision anticipated.
Alternatives considered: the plugin stripping `WITH` and separators
itself (it would have to tokenise SQL); code-source serving cut text
(rejected before); offsets from the start of the file (harder to use
alongside line ranges, and for clients to check).

## 2026-10-14: Search ranks by where words matched, then by relevance

Context: 014 searches descriptions, which are prose, alongside names.
010 and later search work (typos, synonyms, embeddings) will add more
ways to match.
Decision: a search score is layered. Where each word matched decides
first, in whole numbers (file name, part name, description, folder;
whole words above prefixes), so a better place to match always ranks
higher. Below that, a fraction under 0.5: how much descriptions are
about the words (BM25), scaled from its known bound so it can never
reach the next whole number. Below that, smaller tie-breaks (013's
references written in full). New signals take a layer of their own,
rather than being added to the same one. Search changes are checked
against the relevance set (`code-source`'s `RelevanceTest`), which
asserts what's found and what ranks above what, never exact orders or
scores.
Alternatives considered: Lucene's BM25 across all fields with boosts
(can reorder places, and harder to explain); places alone (prose ties
fall into path order).

## 2026-10-14: Files have parts, for search

Context: 014 searches CTE names and descriptions, which live in the
SQL-specific part of an outline, but code-source's search must stay
language-neutral.
Decision: a file's *parts* are the pieces of it that may have a name
and a description (for SQL, queries and their CTEs), with the range
its outline gives them. Each language's outline type says what its
parts are; search indexes and reports parts without knowing the
language. In the API, parts appear only in search results, identified
by `range`, with their name and description. They join the outline
when a client needs them.
Alternatives considered: search reading `sql` outlines directly
(SQL in the generic search); parts in every outline now (an API
change no client needs yet); identifying parts by SQL-shaped ids
(`query 0 / monthly`).

## 2026-10-29: The panel supports Chrome 100 and later

Context: analysts use the panel in Chrome as old as 100. A `:has()`
selector, which Chrome supports from 105, hid nothing there (bug 003).
Decision: the panel's CSS and JS work in Chrome 100 and later. The JS is
compiled to ES2019, so that's CSS's concern: no selector, property or
unit newer than Chrome 100 (e.g. `:has()`, container queries, `dvh`,
`color-mix()`), unless a rule can fail without losing anything. A
selector list fails as a whole, so a newer selector never shares one
with older ones. Checked by eye, as jsdom can't tell.
Alternatives considered: a stylelint check against a browserslist (a
new toolchain for styles written in template literals; worth it if this
recurs); no floor (bug 003 happens again unseen).
