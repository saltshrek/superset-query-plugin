# Vision

## Problem

Analysts vary in expertise along several independent axes: SQL skill,
knowledge of the datasets, and understanding of performance pitfalls. Someone
strong on one axis is often weak on another. That knowledge exists in the
community, but there's no low-friction way, from inside SQL Lab, for it to
reach the analyst writing a query right now. Query quality ends up depending
on who happens to know whom.

The aim is to let analysts who are strong in any of these areas raise the
quality of everyone else's queries, by sharing good examples in a form the
rest of the community can find and use without leaving their workflow.

Examples are worth finding at more than one level. A finished analysis,
and what it produces (a chart, a model, a table), is worth finding when
it answers the question at hand or shows how to answer one like it.
The steps along the way are worth finding too: a new analysis often
starts from some other analysis's early steps (orders cleaned to their
latest status, revenue per customer per month), then goes in a
different direction. Those steps are harder to find: they're named for
the analysis they were part of, and seldom described.

## Users

- Primary: analysts writing queries in SQL Lab who want to find and reuse a
  good example, whole or a step of it.
- Future (shapes the server design, not built now): other tools analysts
  work in, such as a Jupyter extension or an analyst chat bot, and tools
  not yet imagined. Some of these will be AI agents. They all serve
  analysts, so what they need from the server is analyst examples, not
  code search in general. The Superset panel is the server's first
  client, not its defining one.

## What it is

Two systems developed side by side:

- A panel in SQL Lab that browses and searches a git repository of
  example SQL, and inserts a chosen query, or one of its CTEs, into the
  editor.
- A standalone code source service that the panel uses. Its API is about
  finding and reading code in a repository (browse, fetch, search), not
  version-control operations. It can understand the languages files are
  written in (SQL first) through pluggable analyzers, but its API's
  generic shape stays language-neutral. Git is the first and only backend
  for now, but it's an implementation detail. The service can later be
  extracted and serve other analyst tools.

## Goals

v1 was deliberately minimal: a navigable folder structure in SQL Lab,
and moving code from the repository into the editor. It has shipped,
along with search by name, by what examples read and use, and by their
descriptions and CTE names. What comes next is chosen from using it.

## Directions we're exploring

Not goals yet. These are ideas being tried out, to be settled in feature
specs or a decision before anything is built.

- **Steps as reusable starting points.** Alongside whole examples and
  their outputs, treat the steps inside an example (a CTE in SQL) as
  things to find and reuse in their own right, by what they start from,
  the grain they produce ("one row per customer per month") and what
  they mean, not only by their names.
- **Python and notebooks.** The other languages analysts write examples
  in. Their counterparts to SQL's tables and CTEs: the data sources they
  read (`read_parquet`, `spark.table`), and the intermediate DataFrames
  they build. They also contain SQL (`spark.sql(...)`, `%%sql` cells),
  which could be outlined just as a `.sql` file's queries are. They make
  outputs explicit (charts, models, tables, which a notebook may even
  have saved), and techniques visible (the library calls that say how
  something is done).
- **A generic shape in analyst terms.** Outlines' generic fields are
  language-neutral but programming-shaped (keywords, functions,
  references). Analyst tools might be better served by data sources,
  steps, outputs and techniques, with SQL, pandas and notebooks as
  different ways of writing them, so a client could find a step without
  caring which language it's in.
- **Matching by meaning.** Two steps that produce the same thing are
  often named and written differently, and words alone can't tell
  they're alike. This is where embeddings may earn their place (010):
  over a description of each step built from its outline, rather than
  over raw code.
- **Matching from the analyst's work in progress.** What's in the
  editor, or a notebook's kernel, is a pipeline partly written. Examples
  whose early steps resemble it, and where those examples went next,
  could be offered from there.

## Non-goals (for now)

- Contributing to the repository in any form (editing, committing, proposing
  changes). Contributors use their own git workflow outside this project;
  the tool is read-only.
- Code search for its own sake: indexing languages or code that analysts
  don't write examples in.

## Constraints

- Must run as a Superset extension, targeting a lightly modified fork of
  Superset 6.1.0. The fork's relevant change lets an extension share SQL
  Lab's left panel with the built-in schema panel, which is where this
  panel lives. Upstream 6.1.0 doesn't support that.
- code-source must be usable by clients other than Superset, and must be
  cleanly extractable into its own repository. Nothing Superset-specific
  in its API or code. Language-specific code (e.g. SQL) lives only in
  pluggable analyzers, and the API's generic shape doesn't depend on any
  one language.

## Open questions

- Networking and auth. Working assumption: the plugin's frontend calls
  Superset's backend (through a backend part of the extension), which calls
  code-source over the private network. code-source is never exposed to
  browsers and has no auth in v1. To be confirmed in a feature spec,
  including whether the 6.1.0 fork's extension API supports a backend
  proxy, and how Superset's own permissions gate access.
- What a step is where the language doesn't mark one. A CTE has a name
  and boundaries; a pandas step might be a function returning a
  DataFrame, a method chain, a notebook section, or a variable's state at
  a line.
- Where a notebook's locations and language belong: line ranges in a
  notebook's JSON mean nothing to a reader, and one notebook can hold
  Python and SQL.
