// docs/features/009-use-a-cte.md (SQL of a CTE): the runnable SQL a CTE's
// actions give, from a file's content and its outline.
import { cteSql } from '../src/cte';

// Outlines as code-source gives them, with ranges found from the text
// they cover, so the cases read as SQL rather than numbers.
function rangeOf(content: string, text: string) {
  const at = content.indexOf(text);
  if (at === -1) throw new Error(`not in the content: ${text}`);
  const position = (offset: number) => {
    const before = content.slice(0, offset);
    const lineStart = before.lastIndexOf('\n') + 1;
    return {
      line: before.split('\n').length,
      column: Array.from(content.slice(lineStart, offset)).length + 1,
    };
  };
  const first = position(at);
  const last = position(at + text.length - 1);
  return {
    start: first.line,
    end: last.line,
    startColumn: first.column,
    endColumn: last.column,
  };
}

type Cte = { name: string; text: string; uses?: string[]; comment?: string };

function query(content: string, ctes: Cte[], recursive = false) {
  return {
    recursive,
    ctes: ctes.map(({ name, text, uses = [], comment }) => {
      const comments = comment && rangeOf(content, comment);
      return {
        name,
        range: rangeOf(content, text),
        descriptionRange: comments
          ? { start: comments.start, end: comments.end }
          : null,
        uses,
      };
    }),
  };
}

const publisherShare = `-- Each publisher's share of global sales.

-- Global sales per publisher, in millions of units.
WITH by_publisher AS (
  SELECT publisher, SUM(global_sales) AS sales
  FROM video_game_sales
  GROUP BY publisher
),
-- Publishers ranked by sales, best first.
ranked AS (
  SELECT *, RANK() OVER (ORDER BY sales DESC) AS rnk
  FROM by_publisher
)
SELECT publisher, SUM(sales)
FROM ranked
GROUP BY 1;
`;

const publisherQuery = query(publisherShare, [
  {
    name: 'by_publisher',
    text: `by_publisher AS (
  SELECT publisher, SUM(global_sales) AS sales
  FROM video_game_sales
  GROUP BY publisher
)`,
    comment: '-- Global sales per publisher, in millions of units.',
  },
  {
    name: 'ranked',
    text: `ranked AS (
  SELECT *, RANK() OVER (ORDER BY sales DESC) AS rnk
  FROM by_publisher
)`,
    uses: ['by_publisher'],
    comment: '-- Publishers ranked by sales, best first.',
  },
]);

test('a CTE comes with those it uses, each with its description, as written', () => {
  expect(cteSql(publisherShare, publisherQuery, 'ranked')).toBe(
    `-- Global sales per publisher, in millions of units.
WITH by_publisher AS (
  SELECT publisher, SUM(global_sales) AS sales
  FROM video_game_sales
  GROUP BY publisher
),
-- Publishers ranked by sales, best first.
ranked AS (
  SELECT *, RANK() OVER (ORDER BY sales DESC) AS rnk
  FROM by_publisher
)
SELECT * FROM ranked
`,
  );
});

test("a first CTE followed by another loses its WITH's and the comma", () => {
  expect(cteSql(publisherShare, publisherQuery, 'by_publisher')).toBe(
    `-- Global sales per publisher, in millions of units.
WITH by_publisher AS (
  SELECT publisher, SUM(global_sales) AS sales
  FROM video_game_sales
  GROUP BY publisher
)
SELECT * FROM by_publisher
`,
  );
});

test('only the CTEs it depends on come, however indirectly, in file order', () => {
  const content = `WITH a AS (SELECT 1 AS x),
b AS (SELECT x FROM a),
c AS (SELECT 2 AS y),
d AS (SELECT * FROM b, c),
e AS (SELECT * FROM d)
SELECT * FROM e`;
  const q = query(content, [
    { name: 'a', text: 'a AS (SELECT 1 AS x)' },
    { name: 'b', text: 'b AS (SELECT x FROM a)', uses: ['a'] },
    { name: 'c', text: 'c AS (SELECT 2 AS y)' },
    { name: 'd', text: 'd AS (SELECT * FROM b, c)', uses: ['b', 'c'] },
    { name: 'e', text: 'e AS (SELECT * FROM d)', uses: ['d'] },
  ]);

  expect(cteSql(content, q, 'd')).toBe(`WITH a AS (SELECT 1 AS x),
b AS (SELECT x FROM a),
c AS (SELECT 2 AS y),
d AS (SELECT * FROM b, c)
SELECT * FROM d
`);
  expect(cteSql(content, q, 'c')).toBe(`WITH c AS (SELECT 2 AS y)
SELECT * FROM c
`);
});

test('a recursive WITH stays recursive, and a CTE using itself is fine', () => {
  const content = `WITH RECURSIVE tree AS (
  SELECT id FROM nodes WHERE parent IS NULL
  UNION ALL
  SELECT n.id FROM nodes AS n JOIN tree ON n.parent = tree.id
)
SELECT * FROM tree;
`;
  const q = query(
    content,
    [
      {
        name: 'tree',
        text: `tree AS (
  SELECT id FROM nodes WHERE parent IS NULL
  UNION ALL
  SELECT n.id FROM nodes AS n JOIN tree ON n.parent = tree.id
)`,
        uses: ['tree'],
      },
    ],
    true,
  );

  expect(cteSql(content, q, 'tree')).toBe(`WITH RECURSIVE tree AS (
  SELECT id FROM nodes WHERE parent IS NULL
  UNION ALL
  SELECT n.id FROM nodes AS n JOIN tree ON n.parent = tree.id
)
SELECT * FROM tree
`);
});

test('a name indented on a line of its own keeps its indentation', () => {
  const content = `WITH
  -- First.
  a AS (
    SELECT 1
  ),
  b AS (
    SELECT 2
  )
SELECT * FROM a, b`;
  const q = query(content, [
    { name: 'a', text: 'a AS (\n    SELECT 1\n  )', comment: '  -- First.' },
    { name: 'b', text: 'b AS (\n    SELECT 2\n  )' },
  ]);

  expect(cteSql(content, q, 'a')).toBe(`  -- First.
WITH
  a AS (
    SELECT 1
  )
SELECT * FROM a
`);
  expect(cteSql(content, q, 'b')).toBe(`WITH
  b AS (
    SELECT 2
  )
SELECT * FROM b
`);
});

test('code after the closing parenthesis on its line is left out', () => {
  const content = `WITH a AS (SELECT '😀' AS e), b AS (SELECT 2 AS x) SELECT * FROM b`;
  const q = query(content, [
    { name: 'a', text: "a AS (SELECT '😀' AS e)" },
    { name: 'b', text: 'b AS (SELECT 2 AS x)' },
  ]);

  expect(cteSql(content, q, 'b')).toBe(`WITH b AS (SELECT 2 AS x)
SELECT * FROM b
`);
});

test('comments, Jinja and line endings within a CTE are kept', () => {
  const content =
    "WITH daily AS (\r\n  -- Per day.\r\n  SELECT day FROM events\r\n  WHERE day >= '{{ from_dttm }}'\r\n)\r\nSELECT * FROM daily\r\n";
  const q = query(content, [
    {
      name: 'daily',
      text: "daily AS (\r\n  -- Per day.\r\n  SELECT day FROM events\r\n  WHERE day >= '{{ from_dttm }}'\r\n)",
    },
  ]);

  expect(cteSql(content, q, 'daily')).toBe(
    "WITH daily AS (\r\n  -- Per day.\r\n  SELECT day FROM events\r\n  WHERE day >= '{{ from_dttm }}'\r\n)\nSELECT * FROM daily\n",
  );
});

test("nothing when there's no such CTE, or the outline doesn't match the content", () => {
  expect(cteSql(publisherShare, publisherQuery, 'missing')).toBeNull();
  expect(cteSql(publisherShare, { ctes: [] }, 'ranked')).toBeNull();
  expect(cteSql(publisherShare, undefined, 'ranked')).toBeNull();

  const truncated = publisherShare.split('\n').slice(0, 9).join('\n');
  expect(cteSql(truncated, publisherQuery, 'ranked')).toBeNull();

  const usesMissing = query('WITH a AS (SELECT 1) SELECT 1', [
    { name: 'a', text: 'a AS (SELECT 1)', uses: ['gone'] },
  ]);
  expect(cteSql('WITH a AS (SELECT 1) SELECT 1', usesMissing, 'a')).toBeNull();
});
