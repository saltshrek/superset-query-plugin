package codesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// docs/features/007-file-outlines.md: "Representative Trino and Oracle
// examples are outlined correctly, including ones that use Superset's Jinja
// templating"
//
// Everyday queries in each dialect, in src/test/resources/sql-examples/, run
// through the real analyzer. Each test checks what an analyst would rely on:
// the queries and CTEs, the tables, and the functions and keywords that say
// what the example shows how to do. Cases about the analyzer's heuristics in
// isolation are in SqlAnalyzerTest.
class SqlExamplesTest {
    private fun outline(dialect: String, name: String): Outline {
        val text = javaClass.getResource("/sql-examples/$dialect/$name")!!.readText()
        val path = RepoPath.parse(name)!!
        val outline = SqlAnalyzer.installed(dialect).analyze(mapOf(path to text))[path]
            ?: error("the analyzer gave no outline")
        assertFalse(outline.partial, "$dialect/$name should parse fully")
        return outline
    }

    private val Outline.queries get() = sql!!.queries

    // Trino

    @Test
    fun `daily active users, templated with Jinja`() {
        val outline = outline("trino", "daily_active_users.sql")

        val query = outline.queries.single()
        assertTrue(query.description!!.startsWith("Daily active users over the dashboard's time range"))
        val daily = query.ctes.single()
        assertEquals("One row per day.", daily.description)
        assertEquals(listOf("iceberg.analytics.events"), daily.tables)
        assertEquals(listOf("APPROX_DISTINCT", "DATE_TRUNC", "FROM_ISO8601_TIMESTAMP"), daily.functions)
        assertEquals(listOf("daily"), query.uses)
        assertEquals(listOf("LAG"), query.functions)
    }

    @Test
    fun `tags per order, from an unnested array`() {
        val outline = outline("trino", "tags_per_order.sql")

        assertEquals(listOf("iceberg.sales.orders"), outline.references)
        assertEquals(listOf("CARDINALITY", "COUNT", "COUNT_IF", "LOWER", "UNNEST"), outline.functions)
        assertTrue(outline.keywords.containsAll(listOf("CROSS", "JOIN", "HAVING", "LIMIT")))
    }

    @Test
    fun `revenue rollup, two queries in one file`() {
        val outline = outline("trino", "revenue_rollup.sql")

        val (rollup, check) = outline.queries
        assertTrue(rollup.description!!.startsWith("Revenue by region and country"))
        assertEquals(listOf("sales.customers", "sales.orders"), rollup.tables)
        assertTrue(rollup.keywords.containsAll(listOf("FILTER", "ROLLUP")))
        assertTrue("TRY_CAST" in rollup.functions)
        assertTrue(check.description!!.startsWith("Orders whose amount isn't a whole number of cents"))
        assertEquals(listOf("sales.orders"), check.tables)
    }

    @Test
    fun `session lengths, with a named window and chained CTEs`() {
        val outline = outline("trino", "session_lengths.sql")

        val query = outline.queries.single()
        assertTrue(query.description!!.startsWith("Session lengths per user"))
        val (ordered, numbered) = query.ctes
        assertEquals(null, ordered.description)
        assertEquals(listOf("iceberg.analytics.events"), ordered.tables)
        assertTrue("WINDOW" in ordered.keywords)
        assertTrue(numbered.description!!.startsWith("Numbers each user's sessions"))
        assertEquals(listOf("ordered"), numbered.uses)
        assertEquals(listOf("numbered"), query.uses)
    }

    // Oracle

    @Test
    fun `org chart, a hierarchical query`() {
        val outline = outline("oracle", "org_chart.sql")

        assertEquals(listOf("hr.employees"), outline.references)
        assertTrue(outline.keywords.containsAll(listOf("CONNECT BY", "START WITH", "ORDER SIBLINGS BY")))
        assertFalse("WITH" in outline.keywords, "START WITH isn't a CTE")
        assertEquals(listOf("LPAD", "SYS_CONNECT_BY_PATH"), outline.functions)
    }

    @Test
    fun `late invoices, with an old-style outer join and Jinja`() {
        val outline = outline("oracle", "late_invoices.sql")

        assertEquals(listOf("finance.customers", "finance.invoices"), outline.references)
        assertEquals(listOf("DECODE", "NVL", "TO_CHAR", "TO_DATE", "TRUNC"), outline.functions)
    }

    @Test
    fun `top products per region, with LISTAGG and FETCH FIRST`() {
        val outline = outline("oracle", "top_products_per_region.sql")

        val query = outline.queries.single()
        val ranked = query.ctes.single()
        assertTrue(ranked.description!!.startsWith("Sales per product and region"))
        assertEquals(listOf("sales.order_lines", "sales.products"), ranked.tables)
        assertEquals(listOf("RANK", "SUM"), ranked.functions)
        assertTrue("LISTAGG" in query.functions)
        assertTrue("FETCH" in query.keywords)
    }

    @Test
    fun `missing from the warehouse, with MINUS and a database link`() {
        val outline = outline("oracle", "missing_from_warehouse.sql")

        assertEquals("MINUS", outline.keywords.single())
        // A database link stays part of the name, as written (see 007).
        assertEquals(listOf("dw.dim_product@reporting_link", "sales.order_lines"), outline.references)
    }
}
