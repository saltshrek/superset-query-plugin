// The text of some of a file's lines (012, Lines of a file).

/** Lines `start` to `end`, 1-based and inclusive, as in an outline. */
export type LineRange = { start: number; end: number };

/**
 * The text of `content`'s lines in `range`, as written: from the start of
 * the first to the end of the last, its line ending included. Lines end at
 * `\n`, and a final `\n` doesn't start another line. Null when the range
 * isn't wholly in the file.
 */
export function linesOf(content: string, range: LineRange): string | null {
  const { start, end } = range;
  if (!Number.isInteger(start) || !Number.isInteger(end)) return null;
  if (start < 1 || end < start) return null;
  // Where each line starts.
  const starts = [0];
  let i = content.indexOf('\n');
  while (i !== -1 && i + 1 < content.length) {
    starts.push(i + 1);
    i = content.indexOf('\n', i + 1);
  }
  const count = content === '' ? 0 : starts.length;
  if (end > count) return null;
  return content.slice(starts[start - 1], end < count ? starts[end] : content.length);
}

/**
 * Lines `start` to `end`, from `startColumn` on the first to `endColumn` on
 * the last, as in an outline's `range` (009). All 1-based and inclusive;
 * columns count code points.
 */
export type TextRange = LineRange & { startColumn: number; endColumn: number };

const withoutEnding = (line: string) => line.replace(/\r?\n$/, '');

/**
 * The text of `content` in `range`, as written (009, Text of a range). Null
 * when the range isn't wholly in the file.
 */
export function textOf(content: string, range: TextRange): string | null {
  const { start, end, startColumn, endColumn } = range;
  if (!Number.isInteger(startColumn) || !Number.isInteger(endColumn)) return null;
  const text = linesOf(content, { start, end });
  if (text === null) return null;
  const firstLine = linesOf(content, { start, end: start })!;
  const lastLine = linesOf(content, { start: end, end })!;
  // Code points, not UTF-16 units.
  const first = Array.from(withoutEnding(firstLine));
  const last = Array.from(withoutEnding(lastLine));
  if (startColumn < 1 || startColumn > first.length) return null;
  if (endColumn < 1 || endColumn > last.length) return null;
  if (start === end && endColumn < startColumn) return null;
  const from = first.slice(0, startColumn - 1).join('').length;
  const to = text.length - lastLine.length + last.slice(0, endColumn).join('').length;
  return text.slice(from, to);
}
