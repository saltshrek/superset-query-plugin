// What a user can do with a file wherever the panel shows one (004): open
// it in a new tab, or copy it. The same for one query of a file that has
// several (012), and for a CTE (009).
import React, { useEffect, useRef, useState } from 'react';
import { Spin, Tooltip } from 'antd';
import { CopyOutlined, PlusCircleOutlined } from '@ant-design/icons';
import { sqlLab, theme } from '@apache-superset/core';
import { Entry, FileResult, Query, fetchFile, queriesOf, queryRange } from './api';
import { copyText } from './clipboard';
import { cteSql } from './cte';
import { linesOf } from './lines';

type FileAction = 'open' | 'copy';

// How long the copy button's tooltip says "Copied".
const COPIED_MS = 2000;

/** How the panel names a file (016) and titles its tab (004): its name
 * without `.sql`. */
export const shownName = (name: string) => name.replace(/\.sql$/i, '');

/** What a query row is called: its place in the file. */
export const queryName = (index: number) => `Query ${index + 1}`;

/** The tree's key for the row of a file's `index`th query. Not a path, so
 * never taken for a file or folder. */
export const queryKey = (path: string, index: number) =>
  `${path}\u0000query${index}`;

/**
 * What an action uses: a whole file, its `query`th query, or the CTE called
 * `cte` in that query. A one-query file's CTE has no `query`: it's in the
 * file's only query, which isn't named.
 */
type Use = { entry: Entry; query?: number; cte?: string };

/** The tree's key for the row of the CTE called `name`, in a file's
 * `query`th query, or in its only query when `query` is undefined. */
export const cteKey = (path: string, query: number | undefined, name: string) =>
  `${query === undefined ? path : queryKey(path, query)}\u0000cte:${name}`;

const keyOf = ({ entry, query, cte }: Use) =>
  cte !== undefined
    ? cteKey(entry.path, query, cte)
    : query === undefined
      ? entry.path
      : queryKey(entry.path, query);

// The parts after the file's name, as errors and tab titles show them.
const partsOf = ({ query, cte }: Use) => [
  ...(query === undefined ? [] : [queryName(query)]),
  ...(cte === undefined ? [] : [cte]),
];

// What errors and tab titles call a file, query or CTE.
const nameOf = (use: Use) =>
  [shownName(use.entry.name), ...partsOf(use)].join(' › ');

const GONE =
  "It's no longer in the file. Reload to see the file as it is now.";

/** The text an action uses from a fetched file, or why there's none. */
function usedText(
  result: FileResult,
  { query, cte }: Use,
): { text: string } | { message: string } {
  if (!result.ok) return { message: result.message };
  // The fetched file's outline, not the tree's, so what's cut matches the
  // content (012).
  let text: string | null;
  if (cte !== undefined) {
    const queries = (result.outline as any)?.sql?.queries;
    text = cteSql(result.content, queries?.[query ?? 0], cte);
  } else if (query !== undefined) {
    const range = queryRange(result.outline, query);
    text = range && linesOf(result.content, range);
  } else {
    text = result.content;
  }
  return text === null ? { message: GONE } : { text };
}

/** The CTEs shown under a row: its query's, for a query row or a one-query
 * file's row (009). */
export function ctesOf(entry: Entry, query?: number): Query['ctes'] {
  const queries = queriesOf(entry);
  if (query !== undefined) return queries[query]?.ctes ?? [];
  return queries.length === 1 ? queries[0].ctes : [];
}

export type FileActions = ReturnType<typeof useFileActions>;

/** The actions, and what they're doing, for every file in the panel. */
export function useFileActions() {
  // Files and queries with an action running, by row key.
  const [busy, setBusy] = useState<Record<string, FileAction>>({});
  // The last file action that failed, shown until closed.
  const [error, setError] = useState<{
    name: string;
    message: string;
  } | null>(null);
  // The row just copied, by key, whose copy button's tooltip says so.
  const [copied, setCopied] = useState<string | null>(null);
  const copiedTimer = useRef<ReturnType<typeof setTimeout>>();
  const mounted = useRef(true);

  useEffect(
    () => () => {
      mounted.current = false;
      clearTimeout(copiedTimer.current);
    },
    [],
  );

  // Runs one of a file's, query's or CTE's actions. `run` resolves to an
  // error message if it failed.
  const runFileAction = async (
    use: Use,
    action: FileAction,
    run: () => Promise<string | undefined>,
  ) => {
    const key = keyOf(use);
    if (busy[key]) return;
    setBusy(b => ({ ...b, [key]: action }));
    const message = await run();
    if (!mounted.current) return;
    setBusy(({ [key]: _, ...rest }) => rest);
    setError(message ? { name: nameOf(use), message } : null);
  };

  /** Opens the file, its `query`th query, or a CTE, in a new tab. */
  const open = (entry: Entry, query?: number, cte?: string) =>
    runFileAction({ entry, query, cte }, 'open', async () => {
      const used = usedText(await fetchFile(entry.path), { entry, query, cte });
      if ('message' in used) return used.message;
      try {
        await sqlLab.createTab({
          sql: used.text,
          title: nameOf({ entry, query, cte }),
        });
      } catch {
        return "Couldn't open a new tab.";
      }
      return undefined;
    });

  /** Copies the file, its `query`th query, or a CTE. */
  const copy = (entry: Entry, query?: number, cte?: string) =>
    runFileAction({ entry, query, cte }, 'copy', async () => {
      let loadError: string | undefined;
      // Handed to the clipboard still pending (see clipboard.ts).
      const content = fetchFile(entry.path).then(result => {
        const used = usedText(result, { entry, query, cte });
        if ('text' in used) return used.text;
        loadError = used.message;
        throw new Error(used.message);
      });
      try {
        await copyText(content);
      } catch {
        return loadError ?? "The browser didn't allow copying to the clipboard.";
      }
      if (mounted.current) {
        clearTimeout(copiedTimer.current);
        setCopied(keyOf({ entry, query, cte }));
        copiedTimer.current = setTimeout(() => setCopied(null), COPIED_MS);
      }
      return undefined;
    });

  return {
    open,
    copy,
    busy,
    copied,
    error,
    closeError: () => setError(null),
  };
}

/** How wide the action buttons are, so a row can keep room for them (008). */
export const actionButtonsWidth = (t: { sizeUnit: number; fontSize: number }) =>
  2 * t.fontSize + t.sizeUnit * 1.5 + 2 * t.sizeUnit;

// A file's actions sit at the right of its row. Hidden until the row
// containing them shows them (on hover, when active, or while busy). Spaced
// as the schema browser's are: it has a 2px gap plus a 4px right margin on
// each button, so 6px between buttons and 4px after the last. (Two buttons
// of `fontSize`, a gap and the padding: `actionButtonsWidth`.)
const Buttons = theme.styled.span`
  display: none;
  align-items: center;
  gap: ${({ theme }) => theme.sizeUnit * 1.5}px;
  margin-left: auto;
  padding: 0 ${({ theme }) => theme.sizeUnit}px;
  &[data-busy='true'] {
    display: flex;
  }
`;

// Looks like Superset's ActionButton (the schema browser's row actions),
// but a real button, so the keyboard can use it.
const ActionButton = theme.styled.button`
  display: flex;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
  color: ${({ theme }) => theme.colorIcon};
  font-size: ${({ theme }) => theme.fontSize}px; /* Superset's iconSize="m" */
  &:hover,
  &:focus-visible {
    color: ${({ theme }) => theme.colorPrimary};
  }
`;

/** A file's action buttons, its `query`th query's, or those of its CTE
 * called `cte` (in its only query when `query` is undefined). Rows show
 * them by showing `.file-actions`. */
export const FileActionButtons = ({
  entry,
  query,
  cte,
  actions,
}: {
  entry: Entry;
  query?: number;
  cte?: string;
  actions: FileActions;
}) => {
  const key = keyOf({ entry, query, cte });
  const running = actions.busy[key];
  // Clicks on an action mustn't also select or open the row, nor keys
  // used on one move through the tree.
  const stop = (e: React.SyntheticEvent) => e.stopPropagation();
  return (
    <Buttons
      className="file-actions"
      data-busy={running ? 'true' : undefined}
      onClick={stop}
      onDoubleClick={stop}
      onKeyDown={stop}
    >
      <Tooltip title="Open in new tab">
        <ActionButton
          type="button"
          aria-label="Open in new tab"
          onClick={() => actions.open(entry, query, cte)}
        >
          {running === 'open' ? <Spin size="small" /> : <PlusCircleOutlined />}
        </ActionButton>
      </Tooltip>
      <Tooltip
        title={actions.copied === key ? 'Copied' : 'Copy to clipboard'}
      >
        <ActionButton
          type="button"
          aria-label="Copy to clipboard"
          onClick={() => actions.copy(entry, query, cte)}
        >
          {running === 'copy' ? <Spin size="small" /> : <CopyOutlined />}
        </ActionButton>
      </Tooltip>
    </Buttons>
  );
};
