# 004: Use a file

**Status:** done

## Purpose

Get an example from the repository into the analyst's hands with minimal
friction.

## Behaviour

For a SQL file in the tree, the user can:

- **Open in a new editor tab**: a new SQL Lab tab opens, becomes the
  active tab, and contains the file's contents. The tab is named after
  the file, without its `.sql` extension (`top_products.sql` →
  `top_products`). Its database and schema are whatever SQL Lab gives any
  new tab (inherited from the active tab).
- **Copy to clipboard**: the file's contents are copied, and the panel
  confirms it.

How the actions are reached, matching the schema browser above the panel
(which shows icon buttons with tooltips on a row when it's hovered):

- Hovering a file row shows two icon buttons at its right: "Open in new
  tab" (a plus in a circle, SQL Lab's own new-tab icon) and "Copy to
  clipboard" (two overlapping sheets, the schema browser's copy icon).
  Their tooltips say what they do. They're also shown on the active row,
  so keyboard users can see what's available.
- Double-clicking a file row opens it in a new tab.
- With the keyboard, Enter on the active file row opens it in a new tab.
- Folders have no actions, and behave as in 003.

While a file is being fetched, the button that was used shows a spinner.
The panel stays usable meanwhile.

Confirmation and errors:

- After copying, the copy button's tooltip reads "Copied" for a couple of
  seconds.
- If the file can't be loaded, or the clipboard refuses the write, an
  error alert appears above the tree. It names the file and the reason,
  and can be closed. Trying the action again is the retry.

Contents are fetched each time an action is used, never cached, so an
action after Reload always gets what code-source currently serves.

## Acceptance criteria

- [x] "Open in new tab" (button, double-click, or Enter) creates a SQL Lab
      tab with the file's contents, named after the file without `.sql`
- [x] "Copy to clipboard" copies the file's contents and confirms it
- [x] Failure to load the file is shown to the user, naming the file, for
      both actions
- [x] Folders offer no file actions
- [x] code-source returns a file's contents by canonical path, and rejects
      directories, missing paths and non-canonical paths
- [x] A user without access to SQL Lab can't use the file route

## Out of scope

- Inserting into, or replacing, the current editor's contents
- A preview of the file, or using part of a file
- Setting the new tab's database or schema from the example
- Editing the file or writing back to the repository
- Caching contents in the browser or the plugin backend
- Copying when Superset is served over plain HTTP on a host other than
  localhost. Browsers only offer the Clipboard API on secure contexts
  (HTTPS, or HTTP on localhost).

## Components and API changes

### code-source

- Reads the blob at `path` from the same commit the tree is served from.
  Like the tree, nothing is checked out and no filesystem path is built
  from request input.
- Path validation is 003's, unchanged.

### API (add to `docs/api.md`)

`GET /api/v1/file?path=<file>` returns one file's contents.

- `path` (string): a canonical repository path (see 003). Absent or empty
  means the root, which is a directory.
- 200:
  ```json
  { "path": "orders/top_products.sql", "content": "SELECT ...\n" }
  ```
  `content` is the file's bytes decoded as UTF-8, unchanged otherwise
  (line endings and a trailing newline are kept, a BOM is dropped).
- 400 `invalid_path`: `path` isn't canonical.
- 400 `not_a_file`: `path` names a directory (including the root).
- 404 `not_found`: nothing at `path`. Symlinks and submodules count as
  nothing, since the tree doesn't list them.
- 413 `too_large`: the file is over 1 MiB.
- 422 `not_text`: the file isn't valid UTF-8.
- 503 `not_ready`.

The name is content-agnostic: code-source doesn't know or care that the
plugin only asks for `.sql` files.

### plugin backend

`GET /extensions/quillfern/sql-examples/code-source/file?path=<file>`
forwards to `GET /api/v1/file`. It's gated by `requires_sql_lab_read`
like the tree route, and maps responses the same way:

- 200 `{"result": <code-source body>}`
- 400, 404, 413, 422, 503 from code-source: the same status with
  `{"message": <code-source message>, "code": <code-source code>}`
- 502 when code-source is unreachable or answers anything else
- 500 when `SQL_EXAMPLES_CODE_SOURCE_URL` isn't configured

### plugin frontend

- New tabs through `sqlLab.createTab({ sql, title })` from
  `@apache-superset/core`. The 6.1.0 fork declares and implements it, and
  the new tab becomes active, so no fallback is needed.
- Clipboard through `navigator.clipboard`. Error messages for the file
  route follow the tree's (unreachable, not ready, no access), plus
  `too_large` and `not_text`.

### dev

- The sample repository gains nothing new. Its existing files are enough.
- `smoke-test.sh` checks that the file route returns a sample file's
  contents and is gated like the tree route.

## Testing

As 003 set up, with no new kinds of fake:

- **code-source:** HTTP tests against fixture repos for contents, each
  error, and new contents after `POST /refresh`.
- **plugin backend:** pytest for forwarding, error mapping and the
  permission gate.
- **plugin frontend:** the panel against a real code-source.
  `sqlLab.createTab` is added to the `@apache-superset/core` fake, which
  records the tabs created. That's the narrowest seam, since SQL Lab's
  store doesn't run outside Superset. `navigator.clipboard` (absent in
  jsdom) is faked at the browser API. The test forwarder gains the file
  route.

## Decisions

- **Actions on hover, like the schema browser.** Rejected a right-click
  context menu: Superset's schema browser doesn't use one, and it's
  harder to discover.
- **Icons:** `PlusCircleOutlined` for "Open in new tab", matching SQL
  Lab's own New tab button, and `CopyOutlined` for copy, matching the
  schema browser. Rejected `SelectOutlined` (suggests leaving the page),
  `FileAddOutlined` (suggests writing to the repository) and
  `ExportOutlined` (Superset uses it for exports and downloads).
- **Tab title** is the file name without `.sql`. Rejected the full path
  (too long for a tab) and keeping the extension (adds noise).
- **Copy confirmed in the button's tooltip.** Extensions get no toast API
  in the 6.1.0 fork. Rejected antd's static `message`, which renders
  outside the host's theme context.
- **No preview in v1, whole file only**, keeping v1 minimal as the vision
  asks.
- **`too_large` at a fixed 1 MiB.** It can become configurable if another
  client needs bigger files.
- **Contents as JSON `{path, content}`**, not raw bytes, to match the tree
  endpoint and suit future agent clients.
- **Size and text checks live behind the code-source interface**, not in
  the HTTP layer. Git knows a blob's size without reading it, and any
  later backend should apply the same rules.
- **Enter on a folder isn't changed.** The agreed spec said Enter expands
  a folder "as in 003", but in 003 rc-tree's Enter only selects it. That
  line was corrected to "behave as in 003" during implementation, rather
  than adding behaviour.

## Implementation notes

- code-source: `CodeSource.read` sits beside `list`. Both find the entry
  at a path with one shared lookup, so symlinks and submodules are absent
  in the same way for both. Invalid UTF-8 is rejected, never replaced.
- plugin backend: the tree and file routes share one forwarding method.
  The passed-through statuses (now also 413 and 422) apply to both
  routes, though code-source's tree never answers 413 or 422.
- Backend test fixture: a file over 1 MiB and a Latin-1 file are generated
  when the fixture repository is built (`tests/fixture-repo-generate.sh`,
  run by a hook in `dev/sample-repo-init.sh`), so neither is committed.
- Frontend:
  - The actions are part of each file node's title. They're hidden with
    `display: none` except on hover, on rc-tree's active row
    (`.ant-tree-treenode-active`) and while running. Hidden buttons stay
    out of the tab order, and Tab from the tree reaches the active row's
    buttons.
  - Spacing and size match the schema browser's, checked by eye: 6px
    between buttons and 4px after the last (theirs is a 2px flex gap
    plus a 4px right margin on each button; ours is the same in flex
    alone), and 14px icons (`theme.fontSize`, Superset's `iconSize="m"`).
  - The buttons look like Superset's `ActionButton` but are real
    `<button>`s. Superset's is a `span`, which the keyboard can't
    activate.
  - Enter is handled in the tree's `onKeyDown`, because rc-tree only
    selects on Enter. Double-click uses the tree's `onDoubleClick`.
  - `clipboard.ts` follows Superset's `copyTextToClipboard`, which
    extensions can't import. Safari gets a `ClipboardItem` holding the
    pending fetch, since it only allows a write during the click. Other
    browsers use `writeText`. Jest exercises the `writeText` path, and
    the Safari path was checked by eye. Outside secure contexts there's
    no Clipboard API, and copying shows the "didn't allow copying" error.
    There's no `execCommand('copy')` fallback, unlike Superset's helper,
    since that case is out of scope.
- Tests: the `@apache-superset/core` fake gains `sqlLab.createTab`, which
  records the tabs asked for. The clipboard is user-event's own stub on
  `navigator`. The test forwarder maps both routes. antd's Tooltip and
  the alert's close animation log `act()` warnings from timers that fire
  after a test's last step. They're harmless, and left alone rather than
  suppressed.
