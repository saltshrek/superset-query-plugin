// What a search result shows of a description that matched (014, Snippet
// of a description).
import type { Match } from './api';

// A first match that ends within this many characters shows from the start.
const LEAD = 60;
// Words shown before the first match's when the start is left out.
const CONTEXT_WORDS = 3;

/**
 * `text` to show, whether text before it was left out (`cut`), and where
 * `matches` are in it, in code points.
 */
export type Snippet = { text: string; cut: boolean; matches: Match[] };

/**
 * The snippet of `description` for `matches` (code points, in order): its
 * whitespace collapsed, starting a few words before the first match when
 * that's far in. Matches outside the description are left out.
 */
export function snippet(description: string, matches: Match[]): Snippet {
  const chars = Array.from(description);
  // Where each code point of the description lands in the collapsed text,
  // and where its end does (at chars.length).
  const at: number[] = [];
  const collapsed: string[] = [];
  chars.forEach(char => {
    at.push(collapsed.length);
    if (!/\s/.test(char)) collapsed.push(char);
    else if (collapsed.length > 0 && collapsed[collapsed.length - 1] !== ' ') {
      collapsed.push(' ');
    }
  });
  at.push(collapsed.length);
  if (collapsed[collapsed.length - 1] === ' ') collapsed.pop();

  const placed = matches
    .filter(m => m.start >= 0 && m.start < m.end && m.end <= chars.length)
    .map(m => ({ start: at[m.start], end: at[m.end] }));
  const first = placed[0];
  let start = 0;
  if (first && first.end > LEAD) {
    const wordStarts = collapsed
      .map((char, i) => (char !== ' ' && (i === 0 || collapsed[i - 1] === ' ') ? i : -1))
      .filter(i => i >= 0);
    const containing = wordStarts.filter(i => i <= first.start).length - 1;
    start = wordStarts[Math.max(containing - CONTEXT_WORDS, 0)];
  }
  return {
    text: collapsed.slice(start).join(''),
    cut: start > 0,
    matches: placed.map(m => ({ start: m.start - start, end: m.end - start })),
  };
}
