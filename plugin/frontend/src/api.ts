// Calls to the extension's backend (plugin/backend), which calls code-source
// for us. The browser never talks to code-source directly.
import type { LineRange } from './lines';

export const TREE_URL = '/extensions/quillfern/sql-examples/code-source/tree';
export const FILE_URL = '/extensions/quillfern/sql-examples/code-source/file';
export const SEARCH_URL = '/extensions/quillfern/sql-examples/code-source/search';
export const SUGGESTIONS_URL =
  '/extensions/quillfern/sql-examples/code-source/suggestions';

/** Most search results the panel shows. */
export const SEARCH_LIMIT = 50;

/** Most suggestions the panel shows (013). */
export const SUGGESTION_LIMIT = 5;

export type Entry = {
  name: string;
  path: string;
  type: 'file' | 'directory';
  /** A folder's, from its README (007). Null or absent when it has none. */
  description?: string | null;
  /** When a file last changed, as an ISO 8601 timestamp (008). */
  modified?: string | null;
  /** What's in a file (007). Read it through `queriesOf`. */
  outline?: unknown;
};

/** What the panel uses of one query in a SQL file's outline (007). */
export type Query = {
  description: string | null;
  /** The real tables it reads, its CTEs' included: distinct and sorted. */
  tables: string[];
  /** Its CTEs, in the order they're written (009). */
  ctes: Cte[];
};

/** What the panel uses of one CTE of a query (009). */
export type Cte = {
  name: string;
  description: string | null;
  /** The real tables its SQL reads: its own and those of the CTEs it
   * depends on. Distinct and sorted. */
  tables: string[];
};

const strings = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter(v => typeof v === 'string') : [];

/** A SQL file's queries, in order. None when its outline doesn't say. */
export function queriesOf(entry: Entry): Query[] {
  const queries = (entry.outline as any)?.sql?.queries;
  if (!Array.isArray(queries)) return [];
  return queries.map(query => ({
    description:
      typeof query?.description === 'string' ? query.description : null,
    // A query's own `tables` leave out those its CTEs read.
    tables: Array.from(
      new Set([
        ...strings(query?.tables),
        ...(Array.isArray(query?.ctes) ? query.ctes : []).flatMap(
          (cte: any) => strings(cte?.tables),
        ),
      ]),
    ).sort(),
    ctes: ctesWithTables(Array.isArray(query?.ctes) ? query.ctes : []),
  }));
}

function ctesWithTables(ctes: any[]): Cte[] {
  const byName = new Map(ctes.map(cte => [cte?.name, cte]));
  // A CTE's tables and those of every CTE it uses, however indirectly.
  const tablesOf = (name: string): string[] => {
    const tables = new Set<string>();
    const seen = new Set<string>();
    const waiting = [name];
    while (waiting.length > 0) {
      const next = waiting.pop()!;
      const cte = byName.get(next);
      if (seen.has(next) || !cte) continue;
      seen.add(next);
      strings(cte.tables).forEach(table => tables.add(table));
      waiting.push(...strings(cte.uses));
    }
    return Array.from(tables).sort();
  };
  return ctes
    .filter(cte => typeof cte?.name === 'string')
    .map(cte => ({
      name: cte.name,
      description: typeof cte.description === 'string' ? cte.description : null,
      tables: tablesOf(cte.name),
    }));
}

export const lineRange = (value: any): LineRange | null =>
  Number.isInteger(value?.start) && Number.isInteger(value?.end)
    ? { start: value.start, end: value.end }
    : null;

/**
 * The lines the `index`th query in `outline` takes (012): from its
 * description's comment, or its first CTE's above it, to its end. Null if
 * the outline has no such query.
 */
export function queryRange(outline: unknown, index: number): LineRange | null {
  const query = (outline as any)?.sql?.queries?.[index];
  const range = lineRange(query?.range);
  if (!range) return null;
  const firstCte = Array.isArray(query.ctes) ? query.ctes[0] : undefined;
  const starts = [range, query.descriptionRange, firstCte?.descriptionRange]
    .map(lineRange)
    .filter((r): r is LineRange => r !== null)
    .map(r => r.start);
  return { start: Math.min(...starts), end: range.end };
}

export type TreeResult =
  | { ok: true; entries: Entry[] }
  | { ok: false; message: string };

export type FileResult =
  | { ok: true; content: string; outline: unknown }
  | { ok: false; message: string };

/** Where a search matched in some text: code points `start` until `end`. */
export type Match = { start: number; end: number };

/** A file found, with its outline and date as a listing has them (013),
 * and the parts of it that matched (014). Read parts through `partHitsOf`. */
export type SearchHit = {
  path: string;
  matches: Match[];
  parts?: unknown;
  outline?: unknown;
  modified?: string | null;
};

/**
 * A part of a found file that the search matched (014), placed in the
 * file's outline: its `query`th query, or that query's CTE named `cte`.
 */
export type PartHit = {
  query: number;
  cte?: string;
  nameMatches: Match[];
  descriptionMatches: Match[];
};

const sameRange = (a: any, b: any) =>
  ['start', 'end', 'startColumn', 'endColumn'].every(
    key => Number.isInteger(a?.[key]) && a[key] === b?.[key],
  );

const matchList = (value: unknown): Match[] =>
  Array.isArray(value)
    ? value.filter(m => Number.isInteger(m?.start) && Number.isInteger(m?.end))
    : [];

/**
 * The parts of `hit` that matched, in the order they're written, each
 * found in its outline by its range. Parts the outline doesn't have are
 * left out.
 */
export function partHitsOf(hit: SearchHit): PartHit[] {
  const queries = (hit.outline as any)?.sql?.queries;
  if (!Array.isArray(hit.parts) || !Array.isArray(queries)) return [];
  return hit.parts.flatMap((part: any): PartHit[] => {
    const matches = {
      nameMatches: matchList(part?.nameMatches),
      descriptionMatches: matchList(part?.descriptionMatches),
    };
    for (let query = 0; query < queries.length; query += 1) {
      if (sameRange(queries[query]?.range, part?.range)) return [{ query, ...matches }];
      const ctes = Array.isArray(queries[query]?.ctes) ? queries[query].ctes : [];
      const cte = ctes.find((c: any) => sameRange(c?.range, part?.range));
      if (typeof cte?.name === 'string') return [{ query, cte: cte.name, ...matches }];
    }
    return [];
  });
}

export type SearchResult =
  | { ok: true; total: number; hits: SearchHit[] }
  | { ok: false; message: string };

/** What a search can filter by: a field of files' outlines (013). */
export type FilterField = 'reference' | 'function' | 'keyword';

/** Only files whose outline has `value` in `field`. */
export type Filter = { field: FilterField; value: string };

/** A filter that would find `files` SQL files on its own. */
export type Suggestion = Filter & { files: number };

const FILTER_FIELDS: FilterField[] = ['reference', 'function', 'keyword'];

function errorMessage(status: number, body: any): string {
  switch (status) {
    case 401:
    case 403:
      return "You don't have access to the SQL examples.";
    case 413:
      return 'The file is too large to use (over 1 MiB).';
    case 422:
      return "The file isn't text.";
    case 502:
      return 'The examples service is unreachable.';
    case 503:
      if (body?.code === 'not_ready') {
        return 'The examples are still being loaded. Try again shortly.';
      }
  }
  return body?.message || `Loading the examples failed (${status}).`;
}

/** GETs one of our backend routes with query parameters `params`. */
async function get(
  routeUrl: string,
  params: [string, string][],
): Promise<{ ok: true; result: any } | { ok: false; message: string }> {
  const query = new URLSearchParams(params).toString();
  const url = query ? `${routeUrl}?${query}` : routeUrl;
  try {
    const response = await fetch(url, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    });
    const body = await response.json().catch(() => undefined);
    if (response.ok && body?.result) return { ok: true, result: body.result };
    return { ok: false, message: errorMessage(response.status, body) };
  } catch {
    return { ok: false, message: "Couldn't reach Superset." };
  }
}

/** Lists one directory; `path` is '' for the top level. Never throws. */
export async function fetchTree(path: string): Promise<TreeResult> {
  const response = await get(TREE_URL, path ? [['path', path]] : []);
  if (!response.ok) return response;
  if (!Array.isArray(response.result.entries)) {
    return { ok: false, message: errorMessage(200, undefined) };
  }
  return { ok: true, entries: response.result.entries };
}

/** One file's contents. Never throws. */
export async function fetchFile(path: string): Promise<FileResult> {
  const response = await get(FILE_URL, [['path', path]]);
  if (!response.ok) return response;
  if (typeof response.result.content !== 'string') {
    return { ok: false, message: errorMessage(200, undefined) };
  }
  return {
    ok: true,
    content: response.result.content,
    outline: response.result.outline,
  };
}

/**
 * SQL files found by words in their paths and matching every one of
 * `filters`, best first. Never throws.
 */
export async function searchFiles(
  text: string,
  filters: Filter[] = [],
): Promise<SearchResult> {
  const response = await get(SEARCH_URL, [
    ['q', text],
    ['ext', 'sql'],
    ['limit', String(SEARCH_LIMIT)],
    ...filters.map(({ field, value }): [string, string] => [field, value]),
  ]);
  if (!response.ok) return response;
  const { total, results } = response.result;
  if (typeof total !== 'number' || !Array.isArray(results)) {
    return { ok: false, message: errorMessage(200, undefined) };
  }
  return { ok: true, total, hits: results };
}

/**
 * Filters starting with `word` that would find SQL files, most files
 * first. None when they can't be had: suggestions are a help, not
 * something to report failures of. Never throws.
 */
export async function suggest(word: string): Promise<Suggestion[]> {
  const response = await get(SUGGESTIONS_URL, [
    ['q', word],
    ['ext', 'sql'],
    ['limit', String(SUGGESTION_LIMIT)],
  ]);
  const suggestions = response.ok ? response.result.suggestions : undefined;
  if (!Array.isArray(suggestions)) return [];
  return suggestions.filter(
    (s: any) =>
      FILTER_FIELDS.includes(s?.field) &&
      typeof s?.value === 'string' &&
      typeof s?.files === 'number',
  );
}
