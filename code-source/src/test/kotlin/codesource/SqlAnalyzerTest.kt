package codesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// docs/features/007-file-outlines.md: Behaviour (SQL), Python analyzer
//
// The real analyzer, called directly: no repository, refresh or HTTP. This
// is the place for cases about dialects and the analyzer's heuristics. Add a
// test here for each awkward example found in the wild. OutlineTest covers
// how outlines reach API clients.
class SqlAnalyzerTest {
    private fun analyze(sql: String, dialect: String? = null): Outline {
        val path = RepoPath.parse("example.sql")!!
        return SqlAnalyzer.installed(dialect).analyze(mapOf(path to sql))[path]
            ?: error("the analyzer gave no outline")
    }

    private val Outline.queries get() = sql!!.queries

    @Test
    fun `CTE names can be quoted, and CTEs can have column lists`() {
        val outline = analyze(
            """
            |WITH "Order Totals" (id, total) AS (SELECT id, SUM(x) FROM orders GROUP BY id),
            |big AS (SELECT * FROM "Order Totals" WHERE total > 100)
            |SELECT * FROM big
            |""".trimMargin()
        )

        val (totals, big) = outline.queries.single().ctes
        assertEquals("Order Totals", totals.name)
        assertEquals(TextRange(1, 1, 6, 78), totals.range)
        assertEquals(listOf("orders"), totals.tables)
        assertEquals(listOf("Order Totals"), big.uses)
        assertEquals(TextRange(2, 2, 1, 55), big.range)
    }

    @Test
    fun `a recursive CTE uses itself`() {
        val outline = analyze(
            """
            |WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 5)
            |SELECT * FROM n
            |""".trimMargin()
        )

        val cte = outline.queries.single().ctes.single()
        assertEquals(listOf("n"), cte.uses)
        assertEquals(emptyList(), cte.tables)
        assertTrue("RECURSIVE" in outline.keywords)
    }

    @Test
    fun `a WITH nested inside a CTE belongs to that CTE, and its names aren't tables`() {
        val outline = analyze(
            """
            |WITH outer_cte AS (
            |  WITH inner_cte AS (SELECT id FROM orders)
            |  SELECT id FROM inner_cte
            |)
            |SELECT * FROM outer_cte
            |""".trimMargin()
        )

        val query = outline.queries.single()
        assertEquals(listOf("outer_cte"), query.ctes.map { it.name })
        assertEquals(listOf("orders"), query.ctes.single().tables)
        assertEquals(listOf("orders"), outline.references)
    }

    @Test
    fun `function names are as written, not sqlglot's own names for them`() {
        // sqlglot parses NVL as its COALESCE.
        val outline = analyze("SELECT NVL(a, 0), coalesce(b, 1) FROM t", dialect = "oracle")

        assertEquals(listOf("COALESCE", "NVL"), outline.functions)
    }

    @Test
    fun `syntax that looks like a call isn't always a function`() {
        val outline = analyze(
            """
            |SELECT LISTAGG(n, ',') WITHIN GROUP (ORDER BY n),
            |       COUNT(*) FILTER (WHERE n > 0)
            |FROM names
            |WHERE EXISTS (SELECT 1 FROM other) AND n IN (1, 2)
            |""".trimMargin(),
            dialect = "trino",
        )

        assertEquals(listOf("COUNT", "LISTAGG"), outline.functions)
    }

    @Test
    fun `a keyword used as a name isn't a keyword`() {
        val outline = analyze("SELECT date, year FROM t ORDER BY date")

        assertEquals(listOf("ORDER BY"), outline.keywords)
    }

    @Test
    fun `Oracle's MINUS needs the Oracle dialect`() {
        val sql = "SELECT id FROM orders MINUS SELECT id FROM refunds"

        assertFalse(analyze(sql, dialect = "oracle").partial)
        assertTrue(analyze(sql, dialect = null).partial)
    }

    @Test
    fun `Trino's UNNEST and lambdas`() {
        val outline = analyze(
            "SELECT o.id, t.tag FROM orders o CROSS JOIN UNNEST(transform(o.tags, x -> lower(x))) AS t(tag)",
            dialect = "trino",
        )

        assertFalse(outline.partial)
        assertEquals(listOf("orders"), outline.references)
        assertEquals(listOf("LOWER", "TRANSFORM", "UNNEST"), outline.functions)
    }

    // Descriptions (007, Conventions)

    private val Outline.query get() = queries.single()

    @Test
    fun `a comment directly above a query's first line describes the CTE named on it`() {
        val outline = analyze(
            """
            |-- Revenue per month.
            |WITH monthly AS (SELECT 1 FROM t)
            |SELECT * FROM monthly
            |""".trimMargin()
        )

        assertEquals(null, outline.query.description)
        assertEquals("Revenue per month.", outline.query.ctes.single().description)
    }

    @Test
    fun `a query's comment above a CTE named on its first line is separated by a blank line`() {
        val outline = analyze(
            """
            |-- Revenue per month.
            |
            |-- One row per month.
            |WITH monthly AS (SELECT 1 FROM t)
            |SELECT * FROM monthly
            |""".trimMargin()
        )

        assertEquals("Revenue per month.", outline.query.description)
        assertEquals("One row per month.", outline.query.ctes.single().description)
    }

    @Test
    fun `a comment above a query that doesn't start with a CTE describes the query, blank line or not`() {
        assertEquals("Directly above.", analyze("-- Directly above.\nSELECT 1").query.description)
        assertEquals("Further up.", analyze("-- Further up.\n\nSELECT 1").query.description)
        assertEquals("Above a WITH on its own line.", analyze("-- Above a WITH on its own line.\nWITH\nx AS (SELECT 1)\nSELECT * FROM x").query.description)
    }

    @Test
    fun `a comment directly above a later CTE's name describes it, and trailing comments don't count`() {
        val outline = analyze(
            """
            |WITH
            |-- The first.
            |a AS (SELECT 1 AS n), -- not a description
            |
            |-- Not directly above.
            |
            |b AS (SELECT n FROM a),
            |-- The third.
            |c AS (SELECT n FROM b)
            |SELECT * FROM c
            |""".trimMargin()
        )

        assertEquals(listOf("The first.", null, "The third."), outline.query.ctes.map { it.description })
    }

    @Test
    fun `each query has its own description, and a comment belongs to the statement after it`() {
        val outline = analyze(
            """
            |-- First.
            |SELECT 1;
            |
            |-- Second.
            |SELECT 2;
            |SELECT 3;
            |-- After the last query.
            |""".trimMargin()
        )

        assertEquals(listOf("First.", "Second.", null), outline.queries.map { it.description })
    }

    @Test
    fun `only the nearest comment block describes a query`() {
        val outline = analyze("-- Licence header.\n\n-- The query.\nSELECT 1")

        assertEquals("The query.", outline.query.description)
    }

    @Test
    fun `a description's range is the lines of its whole comment block`() {
        val outline = analyze(
            """
            |-- Licence header.
            |
            |-- Revenue per month,
            |-- with a running total.
            |
            |/* One row
            | * per month. */
            |WITH monthly AS (SELECT 1 FROM t),
            |-- Months with sales.
            |busy AS (SELECT * FROM monthly),
            |quiet AS (SELECT * FROM monthly)
            |SELECT * FROM busy
            |""".trimMargin()
        )

        assertEquals(LineRange(3, 4), outline.query.descriptionRange)
        assertEquals(
            listOf(LineRange(6, 7), LineRange(9, 9), null),
            outline.query.ctes.map { it.descriptionRange },
        )
    }

    // docs/features/009-use-a-cte.md: API
    @Test
    fun `a CTE's range runs from its name to its closing parenthesis, in columns`() {
        val outline = analyze(
            """
            |WITH first AS (
            |  SELECT 1 AS x
            |),
            |-- Second.
            |second (x) AS (SELECT x FROM first), third AS (
            |  SELECT x FROM second
            |),
            |  own_line AS (SELECT 'é' AS e), x AS (SELECT 2)
            |SELECT * FROM third, own_line;
            |""".trimMargin()
        )

        val query = outline.query
        assertEquals(TextRange(1, 9, 1, 29), query.range)
        assertEquals(
            listOf(
                TextRange(1, 3, 6, 1),
                TextRange(5, 5, 1, 35),
                TextRange(5, 7, 38, 1),
                TextRange(8, 8, 3, 31),
                TextRange(8, 8, 34, 48),
            ),
            query.ctes.map { it.range },
        )
    }

    @Test
    fun `a query says whether its WITH is recursive`() {
        val outline = analyze(
            """
            |WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 5)
            |SELECT * FROM n;
            |WITH m AS (SELECT 1) SELECT * FROM m;
            |SELECT 1
            |""".trimMargin()
        )

        assertEquals(listOf(true, false, false), outline.queries.map { it.recursive })
    }

    @Test
    fun `there's no description range without a description`() {
        assertEquals(null, analyze("SELECT 1").query.descriptionRange)
        assertEquals(null, analyze("--\nSELECT 1").query.descriptionRange)
    }

    @Test
    fun `description text loses its markers, joins wrapped lines and keeps paragraphs`() {
        val lineComments = analyze("-- Wrapped over\n--   two lines.\n--\n-- A second paragraph.\nSELECT 1")
        val blockComment = analyze("/* Wrapped over\n * two lines.\n *\n * A second paragraph. */\nSELECT 1")

        val expected = "Wrapped over two lines.\n\nA second paragraph."
        assertEquals(expected, lineComments.query.description)
        assertEquals(expected, blockComment.query.description)
    }

    @Test
    fun `Jinja comments and strings are never descriptions`() {
        assertEquals(null, analyze("{# A Jinja comment. #}\nSELECT 1").query.description)
        assertEquals(null, analyze("SELECT '-- not a comment',\n  1").query.description)
    }
}
