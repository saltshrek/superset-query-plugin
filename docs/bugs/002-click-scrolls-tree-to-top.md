# 002: Clicking a row scrolls the tree back to the top

**Status:** fixed
**Found:** 2026-10-13, while trying 009's CTE rows in SQL Lab
**Area:** plugin frontend (`plugin/frontend/src/Panel.tsx`)

## Symptom

Scrolled down the panel, clicking a row (to expand a folder, or a file
or query with CTEs) jumps the panel back to the top of the tree.

## Cause

So the arrow keys work straight after a click (003, f8084a2), a click
focuses rc-tree's hidden input, which takes the tree's keyboard input.
That input is rendered before the tree's rows, at its top, and a
browser scrolls whatever it focuses into view.

Introduced with that change, not by 009. 009 made it more noticeable,
since clicking a row now also expands files and queries.

## Fix

The click focuses the input with `preventScroll: true`, so nothing moves.

## Testing

No automated test: jsdom doesn't scroll on focus, so a test can't see
the symptom, and asserting the `focus` call's options would test the
code rather than the behaviour. Checked by eye in SQL Lab.
