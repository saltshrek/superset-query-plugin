// Search results (006): SQL files found by words in their paths and by the
// filters chosen (013), shown in place of the tree while searching. Each
// shows what its tree row would, with rows for the queries and CTEs that
// matched under it (014), and suggestions for filters come first.
import React, { useEffect, useState } from 'react';
import { Button, Empty, Spin } from 'antd';
import { BlockOutlined, ConsoleSqlOutlined } from '@ant-design/icons';
import { components, theme } from '@apache-superset/core';
import {
  Cte,
  Entry,
  Filter,
  Match,
  SearchHit,
  Suggestion,
  partHitsOf,
  queriesOf,
  searchFiles,
  suggest,
} from './api';
import {
  FileActionButtons,
  FileActions,
  cteKey,
  queryKey,
  queryName,
  shownName,
} from './fileActions';
import {
  CteLabel,
  QueryLabel,
  RowLabel,
  describedBy,
  queryDescribedBy,
} from './rowLabel';
import { fieldName, wordBeingTyped } from './filters';
import { SEARCH_BAR_HEIGHT } from './searchBar';
import { Snippet, snippet } from './snippet';

type SearchState =
  | { status: 'loading' }
  | { status: 'loaded'; total: number; hits: SearchHit[] }
  | { status: 'error'; message: string };

// How long typing must pause before searching.
const SEARCH_DELAY_MS = 150;

// Each instance's own prefix for its rows' ids (aria-activedescendant).
let instances = 0;

// As the tree indents its levels (003).
const INDENT = 16;

// Rows look like the tree's (003): row height and hover colours from the
// same tokens, actions shown on hover and on the active row.
const List = theme.styled.div`
  &:focus {
    outline: none;
  }
  [role='option'] {
    display: flex;
    align-items: center;
    min-height: 28px;
    padding: ${({ theme }) => theme.sizeUnit / 2}px ${({ theme }) => theme.sizeUnit}px;
    border-radius: ${({ theme }) => theme.borderRadiusSM}px;
    cursor: pointer;
    /* Scrolled into view below the sticky search bar, not under it. */
    scroll-margin-top: var(${SEARCH_BAR_HEIGHT}, 0px);
  }
  [role='option']:hover {
    background: ${({ theme }) => theme.colorBgTextHover};
  }
  &:focus [aria-selected='true'] {
    background: ${({ theme }) => theme.colorBgTextActive};
  }
  [role='option']:hover .file-actions,
  [aria-selected='true'] .file-actions {
    display: flex;
  }
  /* The actions take the date's place, as in the tree (008). */
  [role='option']:hover .entry-date,
  [aria-selected='true'] .entry-date,
  [role='option'][data-busy='true'] .entry-date {
    visibility: hidden;
  }
  .result-text {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  small {
    display: block;
    overflow-wrap: break-word;
    color: ${({ theme }) => theme.colorTextSecondary};
    font-size: ${({ theme }) => theme.fontSizeSM}px;
  }
  /* Suggestions (013): kind, value, then how many examples, quietly. */
  .suggestions {
    padding-bottom: ${({ theme }) => theme.sizeUnit}px;
    margin-bottom: ${({ theme }) => theme.sizeUnit}px;
    border-bottom: 1px solid ${({ theme }) => theme.colorSplit};
  }
  .suggestion-field,
  .suggestion-files {
    flex: none;
    color: ${({ theme }) => theme.colorTextSecondary};
    font-size: ${({ theme }) => theme.fontSizeSM}px;
  }
  .suggestion-field {
    width: ${({ theme }) => theme.sizeUnit * 15}px;
  }
  .suggestion-files {
    padding: 0 ${({ theme }) => theme.sizeUnit}px;
  }
  /* A part's row: its icon, as the tree shows it, then its label. */
  .part-line {
    display: flex;
    align-items: center;
    min-width: 0;
  }
  .part-icon {
    flex: none;
    display: flex;
    font-size: ${({ theme }) => theme.fontSizeLG}px;
    margin-right: ${({ theme }) => theme.sizeUnit}px;
  }
  /* What matched in a description (014), under the row it describes: at
     most two lines, its name's start above it. */
  .snippet {
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    overflow: hidden;
  }
  .part-line + .snippet {
    padding-left: ${({ theme }) => theme.fontSizeLG + theme.sizeUnit}px;
  }
  /* Matched words take the colour the action buttons turn on hover. */
  mark {
    padding: 0;
    background: none;
    color: ${({ theme }) => theme.colorPrimary};
    font-weight: ${({ theme }) => theme.fontWeightStrong};
  }
`;

/** The code points of `chars` from `from` until `to`, with `matches` marked. */
function highlighted(
  chars: string[],
  from: number,
  to: number,
  matches: Match[],
): React.ReactNode[] {
  const parts: React.ReactNode[] = [];
  let at = from;
  matches.forEach(match => {
    const start = Math.max(match.start, from);
    const end = Math.min(match.end, to);
    if (start >= end) return;
    if (start > at) parts.push(chars.slice(at, start).join(''));
    parts.push(<mark key={start}>{chars.slice(start, end).join('')}</mark>);
    at = end;
  });
  if (at < to) parts.push(chars.slice(at, to).join(''));
  return parts;
}

const entryOf = (hit: SearchHit): Entry => ({
  name: hit.path.slice(hit.path.lastIndexOf('/') + 1),
  path: hit.path,
  type: 'file',
  outline: hit.outline,
  modified: hit.modified,
});

/** What `found` shows of a description, with "…" where text is left out. */
const SnippetLine = ({ found }: { found?: Snippet }) => {
  if (!found) return null;
  const chars = Array.from(found.text);
  return (
    <small className="snippet">
      {found.cut && '…'}
      {highlighted(chars, 0, chars.length, found.matches)}
    </small>
  );
};

/** Tells a result's row apart from the same row in the tree. */
const resultKey = (key: string) => `result:${key}`;

/**
 * A row of a result: its file's, or that of a query or CTE that matched
 * (014). `query` is as file actions take it: none for a one-query file's
 * CTE. `level` is how far a part's row is indented. `snippet` is of the
 * description of what the row stands for, when that matched.
 */
type ResultRow =
  | { kind: 'file'; hit: SearchHit; entry: Entry; snippet?: Snippet }
  | {
      kind: 'query';
      entry: Entry;
      query: number;
      level: number;
      snippet?: Snippet;
    }
  | {
      kind: 'cte';
      entry: Entry;
      query?: number;
      cte: Cte;
      nameMatches: Match[];
      level: number;
      snippet?: Snippet;
    };

const snippetOf = (description: string | null, matches: Match[]) =>
  description && matches.length > 0 ? snippet(description, matches) : undefined;

/**
 * A result's rows: its file's, then the rows the tree would show each part
 * that matched in (009, 012), in the order they're written. A file of
 * several queries shows the row of each query that matched or has a CTE
 * that did, with the CTEs that matched under it. A one-query file shows
 * its CTEs that matched; its query is the file's row.
 */
function resultRows(hit: SearchHit): ResultRow[] {
  const entry = entryOf(hit);
  const queries = queriesOf(entry);
  const several = queries.length > 1;
  const file: ResultRow = { kind: 'file', hit, entry };
  const rows: ResultRow[] = [file];
  const queriesShown = new Set<number>();
  partHitsOf(hit).forEach(part => {
    const query = queries[part.query];
    if (!query) return;
    // A query's own part comes before its CTEs', so its row is made with
    // its snippet.
    const querySnippet =
      part.cte === undefined
        ? snippetOf(query.description, part.descriptionMatches)
        : undefined;
    if (!several) {
      if (querySnippet) file.snippet = querySnippet;
    } else if (!queriesShown.has(part.query)) {
      queriesShown.add(part.query);
      rows.push({
        kind: 'query',
        entry,
        query: part.query,
        level: 1,
        snippet: querySnippet,
      });
    }
    const cte = query.ctes.find(c => c.name === part.cte);
    if (!cte) return;
    rows.push({
      kind: 'cte',
      entry,
      query: several ? part.query : undefined,
      cte,
      nameMatches: part.nameMatches,
      level: several ? 2 : 1,
      snippet: snippetOf(cte.description, part.descriptionMatches),
    });
  });
  return rows;
}

/** The key of `row`, as the tree keys the same row. */
function treeKeyOf(row: ResultRow): string {
  if (row.kind === 'file') return row.entry.path;
  if (row.kind === 'query') return queryKey(row.entry.path, row.query);
  return cteKey(row.entry.path, row.query, row.cte.name);
}

export const SearchResults = ({
  text,
  filters,
  reloads,
  now,
  actions,
  listRef,
  onChoose,
  onUpFromFirst,
  onEscape,
}: {
  text: string;
  /** Chosen in the search box (013): results must match every one. */
  filters: Filter[];
  /** Changes on Reload, which runs the search again. */
  reloads: number;
  /** What relative dates are relative to. */
  now: Date;
  actions: FileActions;
  /** The list, which the search box moves focus into. */
  listRef: React.RefObject<HTMLDivElement>;
  /** Called with a suggestion the analyst chose. */
  onChoose: (filter: Filter) => void;
  onUpFromFirst: () => void;
  onEscape: () => void;
}) => {
  const [state, setState] = useState<SearchState>({ status: 'loading' });
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  // Of the list's rows: suggestions first, then results.
  const [active, setActive] = useState<number | null>(null);
  // Whether the keyboard moved to the active row, so its tooltip shows (008).
  const [byKeyboard, setByKeyboard] = useState(false);
  const [retries, setRetries] = useState(0);
  const [idPrefix] = useState(() => `sql-examples-result-${(instances += 1)}`);
  // A new array on every render; search again only when the filters change.
  const filtersKey = JSON.stringify(filters);

  // Searches, and looks up suggestions for the word being typed, once
  // typing pauses. The previous results stay until the new ones arrive,
  // and answers to searches that have been overtaken are dropped.
  useEffect(() => {
    let overtaken = false;
    const timer = setTimeout(async () => {
      const word = wordBeingTyped(text);
      const [result, suggested] = await Promise.all([
        searchFiles(text, filters),
        word ? suggest(word) : Promise.resolve([]),
      ]);
      if (overtaken) return;
      setState(
        result.ok
          ? { status: 'loaded', total: result.total, hits: result.hits }
          : { status: 'error', message: result.message },
      );
      setSuggestions(result.ok ? suggested : []);
      setActive(null);
    }, SEARCH_DELAY_MS);
    return () => {
      overtaken = true;
      clearTimeout(timer);
    };
    // `filters` is followed through `filtersKey`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, filtersKey, reloads, retries]);

  const hits = state.status === 'loaded' ? state.hits : [];
  const rows = hits.flatMap(resultRows);
  const rowCount = suggestions.length + rows.length;
  const rowId = (index: number) => `${idPrefix}-${index}`;

  const openRow = (row: ResultRow) => {
    if (row.kind === 'file') actions.open(row.entry);
    else if (row.kind === 'query') actions.open(row.entry, row.query);
    else actions.open(row.entry, row.query, row.cte.name);
  };

  // Only the keyboard moves the active row this way.
  const moveTo = (index: number) => {
    setActive(index);
    setByKeyboard(true);
    document.getElementById(rowId(index))?.scrollIntoView?.({ block: 'nearest' });
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    // Keys on a row's buttons are the buttons' own.
    if (event.target !== event.currentTarget) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      onEscape();
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (rowCount) moveTo(Math.min((active ?? -1) + 1, rowCount - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (!active) onUpFromFirst();
      else moveTo(active - 1);
    } else if (event.key === 'Enter' && active !== null) {
      if (active < suggestions.length) {
        event.preventDefault();
        onChoose(suggestions[active]);
      } else if (rows[active - suggestions.length]) {
        openRow(rows[active - suggestions.length]);
      }
    }
  };

  // Arriving from the search box starts at the first row.
  const onFocus = (event: React.FocusEvent) => {
    if (event.target === event.currentTarget && active === null && rowCount) {
      moveTo(0);
    }
  };

  if (state.status === 'loading') {
    return (
      <div>
        <Spin size="small" /> Searching…
      </div>
    );
  }
  if (state.status === 'error') {
    return (
      <components.Alert
        type="error"
        closable={false}
        action={
          <Button size="small" onClick={() => setRetries(r => r + 1)}>
            Retry
          </Button>
        }
      >
        {state.message}
      </components.Alert>
    );
  }
  return (
    <>
      {rowCount > 0 && (
        <List
          ref={listRef}
          role="listbox"
          aria-label="Search results"
          tabIndex={0}
          aria-activedescendant={active !== null ? rowId(active) : undefined}
          onKeyDown={onKeyDown}
          onFocus={onFocus}
          // Using the mouse, or leaving the list, closes the keyboard's
          // tooltip.
          onMouseDown={() => setByKeyboard(false)}
          onMouseMove={() => setByKeyboard(false)}
          onBlur={() => setByKeyboard(false)}
        >
          {suggestions.length > 0 && (
            <div role="group" aria-label="Suggestions" className="suggestions">
              {suggestions.map((suggestion, index) => (
                <div
                  key={`${suggestion.field} ${suggestion.value}`}
                  id={rowId(index)}
                  role="option"
                  aria-label={`${fieldName(suggestion.field)} ${suggestion.value}, ${examples(suggestion.files)}`}
                  aria-selected={index === active}
                  onClick={() => onChoose(suggestion)}
                >
                  <span className="suggestion-field">
                    {fieldName(suggestion.field)}
                  </span>
                  <span className="result-text">{suggestion.value}</span>
                  <span className="suggestion-files" title={examples(suggestion.files)}>
                    {suggestion.files}
                  </span>
                </div>
              ))}
            </div>
          )}
          {rows.length > 0 && (
            <div role="group" aria-label="Examples">
              {rows.map((row, i) => {
                const index = suggestions.length + i;
                const key = resultKey(treeKeyOf(row));
                const keyboardActive = byKeyboard && index === active;
                const select = () => {
                  setActive(index);
                  setByKeyboard(false);
                  listRef.current?.focus();
                };
                // Named and described as their rows in the tree are: files
                // by their paths here, since the folder isn't a row (013).
                const option = (
                  name: string,
                  description: string | undefined,
                  contents: React.ReactNode,
                  level = 0,
                ) => (
                  <div
                    key={key}
                    id={rowId(index)}
                    role="option"
                    aria-label={name}
                    aria-describedby={description}
                    aria-selected={index === active}
                    // Hides its date, without :has() (bug 003).
                    data-busy={actions.busy[treeKeyOf(row)] ? 'true' : undefined}
                    onClick={select}
                    onDoubleClick={() => openRow(row)}
                    style={level ? { paddingLeft: level * INDENT } : undefined}
                  >
                    {contents}
                  </div>
                );
                if (row.kind === 'query') {
                  const query = queriesOf(row.entry)[row.query];
                  return option(
                    queryName(row.query),
                    queryDescribedBy(query, key),
                    <div className="result-text">
                      <div className="part-line">
                        <span className="part-icon">
                          <ConsoleSqlOutlined />
                        </span>
                        <QueryLabel
                          query={query}
                          index={row.query}
                          rowKey={key}
                          actions={
                            <FileActionButtons
                              entry={row.entry}
                              query={row.query}
                              actions={actions}
                            />
                          }
                          keyboardActive={keyboardActive}
                        />
                      </div>
                      <SnippetLine found={row.snippet} />
                    </div>,
                    row.level,
                  );
                }
                if (row.kind === 'cte') {
                  const name = Array.from(row.cte.name);
                  return option(
                    row.cte.name,
                    queryDescribedBy(row.cte, key),
                    <div className="result-text">
                      <div className="part-line">
                        <span className="part-icon">
                          <BlockOutlined />
                        </span>
                        <CteLabel
                          cte={row.cte}
                          rowKey={key}
                          name={highlighted(name, 0, name.length, row.nameMatches)}
                          actions={
                            <FileActionButtons
                              entry={row.entry}
                              query={row.query}
                              cte={row.cte.name}
                              actions={actions}
                            />
                          }
                          keyboardActive={keyboardActive}
                        />
                      </div>
                      <SnippetLine found={row.snippet} />
                    </div>,
                    row.level,
                  );
                }
                const { hit, entry } = row;
                const chars = Array.from(hit.path);
                const slash = chars.lastIndexOf('/');
                // Named without `.sql` (016), so matches in it don't show.
                const shown = Array.from(shownName(hit.path));
                return option(
                  shown.join(''),
                  describedBy(entry, key),
                  <div className="result-text">
                    <RowLabel
                      entry={entry}
                      now={now}
                      name={highlighted(chars, slash + 1, shown.length, hit.matches)}
                      rowKey={key}
                      actions={<FileActionButtons entry={entry} actions={actions} />}
                      folder={
                        slash >= 0
                          ? highlighted(chars, 0, slash, hit.matches)
                          : undefined
                      }
                      keyboardActive={keyboardActive}
                    />
                    <SnippetLine found={row.snippet} />
                  </div>,
                );
              })}
            </div>
          )}
        </List>
      )}
      {hits.length === 0 && (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="No SQL examples match. Try fewer words."
        />
      )}
      {state.total > hits.length && (
        <div>
          Showing {hits.length} of {state.total}. Add words to narrow the
          search.
        </div>
      )}
    </>
  );
};

const examples = (files: number) =>
  files === 1 ? '1 example' : `${files} examples`;
