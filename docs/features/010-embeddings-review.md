# 010: Review: whether and where to use embeddings

**Status:** draft

This is a review, not a feature. Its outcome is a decision recorded in
`decisions.md`, and either new feature specs or a reasoned "not yet".

## Purpose

By now, search by name (006), outlines (007), richer information in the
tree (008) and using single CTEs (009) are in use. Take a fresh look at what they do well and where they fall
short, then decide whether semantic search with embeddings would close
the gap, and where it would sit. The aim is to get as far as possible
without LLM calls before reaching for them.

## Questions to answer

- **Where does the current search fail?** Look at real searches (if 006
  records them), at what analysts say, and at the size and shape of the
  repository by then (aiming for several hundred examples).
- **Could cheaper steps close the gap?** For example:
  - search over descriptions, CTE names and tables (tables in 013;
    descriptions and CTE names in 014)
  - typo-tolerant matching
  - synonyms
  - context-aware ranking from the SQL Lab tab's database and schema and
    the tables in the editor. That needs table matching, not embeddings.
- **If embeddings would help, what do we embed?** Raw SQL embeds poorly
  with small models. Most likely we'd embed a description assembled from
  the outline (name, description, tables, grouping, columns) for each
  file and each CTE.
- **Where would they be used?**
  - free-text semantic search, mixed with lexical results
  - "related examples" for a file or CTE
  - matching the analyst's current query
- **Local or remote model?** Running a small model in code-source looks
  feasible: an ONNX model of about 30M parameters, run through ONNX
  Runtime for Java, with vectors in Lucene. That means no network calls
  and no secrets, at the cost of a larger image. Confirm quality on our
  data and the cost to the image's size.
- **Is it time for LLM-written descriptions?** They could improve
  lexical and semantic search alike. If yes, decide when to generate
  them and where to keep them, given that the repository is read-only to
  us.

## How to decide

- Build a small relevance set: realistic queries an analyst might type,
  each with the examples it should find. Use it to compare the current
  search with any candidate. Keep it, since future search changes can be
  checked against it.
- Prefer the smallest change that clears the bar.

## Out of scope

- Building anything. Implementation goes in the specs this review leads
  to.

## Decisions

Filled in at the review.
