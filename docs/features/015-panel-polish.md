# 015: Panel polish

**Status:** done

## Purpose

Small fixes to how the panel looks and feels, found by using it next to
SQL Lab's schema browser. Each one amends an earlier feature, and none is
worth a spec of its own. They're grouped here so each change is
reviewed against what it replaces.

## Behaviour

### Down from the search box into the tree (amends 006, 013)

Today Down in the search box moves into the results, but only while
searching. With the box empty the tree is shown, and Down does nothing.

- With the box empty and no chips, Down in the box moves into the tree
  and makes its first row active, as Down does for the results. The
  active row shows its tooltip, as any row the keyboard moves to does
  (008).
- Up on the tree's first row goes back to the box, as Up from the first
  result does. Today it wraps to the tree's last row.
- So the box and the tree make one loop: Up in the empty box moves into
  the tree with its last row active, and Down on the tree's last row
  goes back to the box. Today Down there wraps to the first row.
- With nothing in the tree to move to (still loading, an error, no
  examples), Down does nothing.

### Icons for files and queries (amends 008, 012)

A query looks the same wherever it appears. Today a one-query file has a
file icon, and each query of a file of several has a query icon
(`ConsoleSqlOutlined`), so the same thing has two icons.

- A file of one query has the query icon. So does a file with no
  queries or no outline: what an analyst takes from it is SQL.
- A file of several queries keeps the file icon. That's what marks it
  out as holding more than one, with its query rows under it.
- Query rows and CTE rows are unchanged. Search results are unchanged:
  their file rows have no icon.

### The panel's heading (amends 003)

"SQL Examples" is plain body text right against the top of its area,
under the splitter's collapse handle.

- The heading has space above it, the same as the schema browser has
  above its database selector (10px), so it no longer sits under the
  handle.
- It's in Superset's strong weight (`fontWeightStrong`), at the same
  size as now, so it reads as a heading.

### Space at the sides (amends 003)

The panel runs edge to edge of the sidebar. The schema browser above it
has 10px on each side (the fork's `StyledSidebar`), so ours looks wider
than it, most noticeably on the right, where the search box and the row
hover reach the sidebar's edge.

- The panel has the schema browser's space on both sides, so its heading,
  search box, tree and results line up with the schema browser's.
- Tree rows line up with the schema browser's rows: their icons start at
  the left edge of the search box, as the schema browser's do, and their
  hover background spans the panel's width.

## Acceptance criteria

- [x] With the search box empty, Down moves into the tree with its
      first row active, and Up from that row goes back to the box
- [x] With the search box empty, Up moves into the tree with its last
      row active, and Down from that row goes back to the box
- [x] A one-query file's row has the query icon, and a file of several
      queries has the file icon
- [x] The heading is in the strong weight, with the schema browser's
      space above it (checked by eye)
- [x] The panel's heading, search box and tree line up with the schema
      browser's at both sides (checked by eye)

The last two are layout, which jsdom doesn't do, so they're checked by
eye in the dev stack rather than tested.

## Out of scope

- Moving the Reload button to the left, as the schema browser's toolbar
  is.
- A file icon on search results' file rows.
- Remembering where the keyboard was in the tree when coming back down
  from the box.
- The same loop for search results (Up from the box to the last result,
  Down from the last result to the box). Results keep 006's behaviour.

## Components and API changes

- plugin: `Panel.tsx` and `searchBar.ts`.
- code-source: none.
- API: none.

## Open questions

None.

## Decisions

- **Down into the tree goes to its first row,** as for results, not to
  the row last active. Predictable, and the tree's active row is often
  wherever a click left it.
- **Up and Down loop through the box** rather than wrapping within the
  tree, so the box is always one key away from either end.
- **Heading: strong weight and space above, same size.** It was
  cramped rather than too small, and at 16px it would outweigh the
  schema browser, which has no heading.
- **Tree rows lose antd's left padding and centred icon square** rather
  than the panel taking space on the right only. Space on the right
  alone was rejected: the heading and search box would still have
  started 10px left of the schema browser's.

## Implementation notes

- Keyboard (`Panel.tsx`): the panel lists the tree's rows top to bottom
  (`rowKeys`, walking the tree data through its expanded keys, as rc-tree
  does). Down and Up in the empty box focus rc-tree's hidden input and
  make the first or last row active, marked as the keyboard's so its
  tooltip shows. Leaving from either end is caught in the capture phase
  on the tree's container, before rc-tree would wrap. Rows that are
  loading or failed count as rows, as they do for rc-tree.
- Icons: a one-query file's node sets `ConsoleSqlOutlined` explicitly;
  `DirectoryTree` would otherwise give a leaf its file icon.
- Spacing: `panelGutter` in `searchBar.ts` is the schema browser's
  10px (`sizeUnit * 2.5`). The panel is padded by it at the sides and
  the heading at the top. The sticky search bar reaches across the
  gutters with a negative margin, so scrolled rows don't show beside it.
  The tree's row padding and icon width overrides need antd's own
  selector depth to win.
- Checked by eye and measured in the dev stack: the heading, search box
  and row icons start at the schema browser's left edge and end at its
  right, and rows' hover spans the same width.
- Tests: two new ones in `Panel.test.tsx`. No existing test changed.
