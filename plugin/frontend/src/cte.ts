// The SQL that shows what one CTE produces (009, SQL of a CTE).
import { lineRange } from './api';
import { TextRange, linesOf, textOf } from './lines';

const textRange = (value: any): TextRange | null =>
  lineRange(value) &&
  Number.isInteger(value.startColumn) &&
  Number.isInteger(value.endColumn)
    ? {
        start: value.start,
        end: value.end,
        startColumn: value.startColumn,
        endColumn: value.endColumn,
      }
    : null;

type Part = {
  /** Its description's lines, ending included, or ''. */
  description: string;
  body: string;
  /** Whether `body` starts with the indentation before its name. */
  indented: boolean;
};

/** One CTE's text in `content`, or null when it isn't wholly there. */
function partOf(content: string, cte: any): Part | null {
  const range = textRange(cte?.range);
  if (!range) return null;
  let description = '';
  if (cte.descriptionRange != null) {
    const lines = lineRange(cte.descriptionRange);
    const text = lines && linesOf(content, lines);
    if (text == null) return null;
    description = text;
  }
  // Indentation before the name comes too, so the body's lines line up.
  const line = linesOf(content, { start: range.start, end: range.start });
  const before = line && Array.from(line).slice(0, range.startColumn - 1).join('');
  const indented = !!before && /^\s+$/.test(before);
  const body = textOf(content, indented ? { ...range, startColumn: 1 } : range);
  return body === null ? null : { description, body, indented };
}

/**
 * Runnable SQL for the CTE called `name` in `query`, one query of the
 * outline that came with `content`: that CTE and those it depends on, as
 * written, then `SELECT * FROM <name>`. Null when the query has no such CTE,
 * or the outline doesn't match the content.
 */
export function cteSql(
  content: string,
  query: unknown,
  name: string,
): string | null {
  const ctes: any[] = Array.isArray((query as any)?.ctes)
    ? (query as any).ctes
    : [];
  const byName = new Map(ctes.map(cte => [cte?.name, cte]));
  // It and those it uses, and theirs, and so on.
  const needed = new Set<string>();
  const waiting = [name];
  while (waiting.length > 0) {
    const next = waiting.pop()!;
    if (needed.has(next)) continue;
    const cte = byName.get(next);
    if (!cte) return null;
    needed.add(next);
    if (Array.isArray(cte.uses)) waiting.push(...cte.uses);
  }

  // In the order they're written, so each is defined before it's used.
  const parts = ctes
    .filter(cte => needed.has(cte?.name))
    .map(cte => partOf(content, cte));
  if (parts.some(part => part === null)) return null;
  const [first, ...rest] = parts as Part[];

  const recursive = (query as any)?.recursive === true;
  return [
    first.description,
    recursive ? 'WITH RECURSIVE' : 'WITH',
    first.indented ? '\n' : ' ',
    first.body,
    ...rest.map(part => `,\n${part.description}${part.body}`),
    `\nSELECT * FROM ${name}\n`,
  ].join('');
}
