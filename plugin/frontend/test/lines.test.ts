// docs/features/012-use-a-query.md (Lines of a file): the text of a range
// of a file's lines, as the query rows' actions and 009's CTEs cut it.
import { linesOf, textOf } from '../src/lines';

test.each([
  // content, start, end, text
  ['a\nb\nc\n', 1, 1, 'a\n'],
  ['a\nb\nc\n', 2, 3, 'b\nc\n'], // the last line's ending comes too
  ['a\nb\nc', 2, 3, 'b\nc'], // and there's none when the file has none
  ['a\r\nb\r\nc\r\n', 1, 2, 'a\r\nb\r\n'], // endings as written
  ['a\n\n  b;\n', 2, 3, '\n  b;\n'], // blank lines and indentation kept
])('lines %#: %j from %d to %d', (content, start, end, text) => {
  expect(linesOf(content, { start, end })).toBe(text);
});

test.each([
  // content, start, end
  ['a\nb\n', 3, 3], // a final \n doesn't start another line
  ['a\nb', 2, 3], // past the last line
  ['', 1, 1], // empty content has no lines
  ['a\nb\n', 0, 1], // before line 1
  ['a\nb\n', 2, 1], // ends before it starts
])('not in the file %#: %j from %d to %d', (content, start, end) => {
  expect(linesOf(content, { start, end })).toBeNull();
});

// docs/features/009-use-a-cte.md (Text of a range): the text between two
// columns, as CTEs are cut.
describe('textOf', () => {
  test.each([
    // content, start, end, startColumn, endColumn, text
    ['WITH a AS (\n  SELECT 1\n),\n', 1, 3, 6, 1, 'a AS (\n  SELECT 1\n)'],
    ['x, b AS (SELECT 2) SELECT', 1, 1, 4, 18, 'b AS (SELECT 2)'],
    ['abc\n', 1, 1, 1, 3, 'abc'], // a whole line, without its ending
    ['abc', 1, 1, 2, 2, 'b'], // one character
    ['a\r\nbc\r\nd\r\n', 1, 2, 1, 2, 'a\r\nbc'], // endings within kept as written
    ['😀😀 x AS (y)', 1, 1, 4, 11, 'x AS (y)'], // columns count code points
    ['é\n😀 z', 1, 2, 1, 3, 'é\n😀 z'],
  ])(
    'text %#: %j, lines %d to %d, columns %d to %d',
    (content, start, end, startColumn, endColumn, text) => {
      expect(textOf(content, { start, end, startColumn, endColumn })).toBe(text);
    },
  );

  test.each([
    // content, start, end, startColumn, endColumn
    ['abc\n', 2, 2, 1, 1], // past the last line
    ['abc\n', 1, 1, 0, 2], // before column 1
    ['abc\n', 1, 1, 2, 4], // past the end of the line
    ['abc\n', 1, 1, 3, 2], // ends before it starts
    ['a\nbc\n', 1, 2, 2, 1], // starts past the end of its line
    ['😀\n', 1, 1, 1, 2], // a code point is one column, not two
  ])(
    'not in the file %#: %j, lines %d to %d, columns %d to %d',
    (content, start, end, startColumn, endColumn) => {
      expect(textOf(content, { start, end, startColumn, endColumn })).toBeNull();
    },
  );
});
