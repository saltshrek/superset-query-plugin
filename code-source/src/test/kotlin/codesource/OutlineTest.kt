package codesource

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// docs/api.md: Outlines, GET /api/v1/file, GET /api/v1/tree
// docs/features/007-file-outlines.md
class OutlineTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    private fun fixture(files: Map<String, Any>) = FixtureRepo().also { cleanup += it; it.commit(files) }

    private fun source(repo: FixtureRepo, analyzer: SqlAnalyzer = SqlAnalyzer.installed(dialect = null)) =
        GitCodeSource(repo.url, null, Files.createTempDirectory("clone").toFile().resolve("repo.git"), sqlAnalyzer = analyzer)
            .also { cleanup += it }

    private fun serving(source: CodeSource, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(source) }
        block()
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.outline(path: String): JsonObject {
        val response = get("/api/v1/file") { parameter("path", path) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.json()["outline"]!!.jsonObject
    }

    private suspend fun HttpClient.entries(path: String = ""): List<JsonObject> =
        get("/api/v1/tree") { parameter("path", path) }.json()["entries"]!!.jsonArray.map { it.jsonObject }

    private suspend fun HttpClient.refresh() = assertEquals(HttpStatusCode.OK, post("/refresh").status)

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun JsonObject.strings(name: String) = this[name]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.queries() = this["sql"]!!.jsonObject["queries"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.ctes() = this["ctes"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.partial() = this["partial"]!!.jsonPrimitive.boolean

    private val monthlyRevenue = """
        |-- Revenue per month, with a running total for each year.
        |WITH monthly AS (
        |  SELECT year, month, SUM(sales) AS revenue
        |  FROM cleaned_sales_data
        |  GROUP BY year, month
        |)
        |SELECT
        |  year,
        |  month,
        |  ROUND(revenue, 2) AS revenue,
        |  ROUND(SUM(revenue) OVER (PARTITION BY year ORDER BY month), 2) AS year_to_date
        |FROM monthly
        |ORDER BY year, month;
        |""".trimMargin()

    @Test
    fun `a SQL file's outline lists its queries and their CTEs, with what each uses`() {
        val repo = fixture(mapOf("sales/monthly.sql" to monthlyRevenue))

        serving(source(repo)) {
            client.refresh()

            assertEquals(
                json(
                    """
                    {
                      "language": "sql",
                      "partial": false,
                      "keywords": ["GROUP BY", "ORDER BY", "OVER", "PARTITION BY", "WITH"],
                      "functions": ["ROUND", "SUM"],
                      "references": ["cleaned_sales_data"],
                      "sql": {
                        "queries": [
                          {
                            "range": { "start": 2, "end": 13, "startColumn": 1, "endColumn": 20 },
                            "description": null,
                            "descriptionRange": null,
                            "keywords": ["ORDER BY", "OVER", "PARTITION BY", "WITH"],
                            "functions": ["ROUND", "SUM"],
                            "tables": [],
                            "uses": ["monthly"],
                            "recursive": false,
                            "ctes": [
                              {
                                "name": "monthly",
                                "range": { "start": 2, "end": 6, "startColumn": 6, "endColumn": 1 },
                                "description": "Revenue per month, with a running total for each year.",
                                "descriptionRange": { "start": 1, "end": 1 },
                                "keywords": ["GROUP BY"],
                                "functions": ["SUM"],
                                "tables": ["cleaned_sales_data"],
                                "uses": []
                              }
                            ]
                          }
                        ]
                      }
                    }
                    """
                ),
                client.outline("sales/monthly.sql"),
            )
        }
    }

    @Test
    fun `file entries in a listing carry the same outline as the file, and folders carry none`() {
        val repo = fixture(mapOf("sales/monthly.sql" to monthlyRevenue, "sales/notes.txt" to "hello"))

        serving(source(repo)) {
            client.refresh()

            val entries = client.entries("sales").associateBy { it["name"]!!.jsonPrimitive.content }
            assertEquals(client.outline("sales/monthly.sql"), entries["monthly.sql"]!!["outline"])
            assertEquals(client.outline("sales/notes.txt"), entries["notes.txt"]!!["outline"])
            assertNull(client.entries().single()["outline"])
        }
    }

    @Test
    fun `folders are described by the first paragraph of their README that isn't a heading`() {
        val repo = fixture(
            mapOf(
                "README.md" to "# Examples\n\nQueries for the\nwhole team.\n\nMore detail.\n",
                "sales/readme.md" to "Sales\n=====\n\nRevenue and orders.\n",
                "sales/q.sql" to "SELECT 1",
                "empty/README.md" to "# Only a heading\n",
                "plain/q.sql" to "SELECT 1",
            )
        )

        serving(source(repo)) {
            client.refresh()

            val root = client.get("/api/v1/tree").json()
            assertEquals("Queries for the whole team.", root["description"]!!.jsonPrimitive.content)
            val descriptions = client.entries()
                .filter { it["type"]!!.jsonPrimitive.content == "directory" }
                .associate { it["name"]!!.jsonPrimitive.content to it["description"] }
            assertEquals(
                mapOf("empty" to JsonNull, "plain" to JsonNull, "sales" to JsonPrimitive("Revenue and orders.")),
                descriptions,
            )
            assertEquals(JsonNull, client.get("/api/v1/tree") { parameter("path", "plain") }.json()["description"])
        }
    }

    @Test
    fun `folder descriptions reflect the repository after a refresh`() {
        val repo = fixture(mapOf("sales/README.md" to "Before.", "sales/q.sql" to "SELECT 1"))

        serving(source(repo)) {
            client.refresh()
            repo.commit(mapOf("sales/README.md" to "After.", "sales/q.sql" to "SELECT 1"))
            client.refresh()

            assertEquals("After.", client.entries().single()["description"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a file in a language code-source doesn't analyse gets the generic fields only`() {
        val repo = fixture(mapOf("README.md" to "# Examples\n"))

        serving(source(repo)) {
            client.refresh()

            assertEquals(
                json("""{ "language": null, "partial": false, "keywords": [], "functions": [], "references": [] }"""),
                client.outline("README.md"),
            )
        }
    }

    @Test
    fun `queries in one file can define CTEs of the same name`() {
        val repo = fixture(
            mapOf(
                "two.sql" to """
                    |WITH base AS (SELECT id FROM orders)
                    |SELECT * FROM base;
                    |
                    |WITH base AS (SELECT id FROM customers)
                    |SELECT * FROM base;
                    |""".trimMargin(),
            )
        )

        serving(source(repo)) {
            client.refresh()

            val queries = client.outline("two.sql").queries()
            assertEquals(listOf(listOf("base"), listOf("base")), queries.map { it.strings("uses") })
            assertEquals(
                listOf(listOf("orders"), listOf("customers")),
                queries.map { it.ctes().single().strings("tables") },
            )
            assertEquals(listOf(1, 4), queries.map { it["range"]!!.jsonObject["start"]!!.jsonPrimitive.content.toInt() })
        }
    }

    @Test
    fun `tables are the real tables read, as written, with aliases resolved and CTEs left out`() {
        val repo = fixture(
            mapOf(
                "tables.sql" to """
                    |WITH recent AS (
                    |  SELECT o.id FROM iceberg.sales.orders AS o
                    |  WHERE o.id IN (SELECT order_id FROM sales.returns)
                    |),
                    |named AS (SELECT r.id, c.name FROM recent r JOIN customers c ON c.id = r.id)
                    |SELECT * FROM named n JOIN regions USING (id);
                    |""".trimMargin(),
            )
        )

        serving(source(repo)) {
            client.refresh()

            val outline = client.outline("tables.sql")
            assertEquals(listOf("customers", "iceberg.sales.orders", "regions", "sales.returns"), outline.strings("references"))
            val query = outline.queries().single()
            assertEquals(listOf("regions"), query.strings("tables"))
            assertEquals(listOf("named"), query.strings("uses"))
            val (recent, named) = query.ctes()
            assertEquals(listOf("iceberg.sales.orders", "sales.returns"), recent.strings("tables"))
            assertEquals(listOf("customers"), named.strings("tables"))
            assertEquals(listOf("recent"), named.strings("uses"))
        }
    }

    @Test
    fun `keywords leave out comments, strings, Jinja, names and ubiquitous keywords`() {
        val repo = fixture(
            mapOf(
                "keywords.sql" to """
                    |-- PIVOT in a comment
                    |SELECT 'QUALIFY in a string' AS s, date, t.x
                    |FROM t
                    |WHERE dt > '{{ from_dttm }}' {# UNNEST #}
                    |GROUP BY 1, 2, 3
                    |LIMIT 10
                    |""".trimMargin(),
            )
        )

        serving(source(repo)) {
            client.refresh()

            assertEquals(listOf("GROUP BY", "LIMIT"), client.outline("keywords.sql").strings("keywords"))
        }
    }

    @Test
    fun `functions are listed apart from keywords, without qualifiers, for the file and each part`() {
        val repo = fixture(
            mapOf(
                "functions.sql" to """
                    |WITH ranked AS (
                    |  SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS n, analytics.score(id) AS s FROM t
                    |)
                    |SELECT CAST(n AS VARCHAR), LEFT(s, 2) FROM ranked LEFT JOIN u ON TRUE
                    |""".trimMargin(),
            )
        )

        serving(source(repo)) {
            client.refresh()

            val outline = client.outline("functions.sql")
            assertEquals(listOf("CAST", "LEFT", "ROW_NUMBER", "SCORE"), outline.strings("functions"))
            assertTrue("LEFT" in outline.strings("keywords"))
            val query = outline.queries().single()
            assertEquals(listOf("CAST", "LEFT"), query.strings("functions"))
            assertEquals(listOf("ROW_NUMBER", "SCORE"), query.ctes().single().strings("functions"))
        }
    }

    @Test
    fun `statements other than queries are left out, and don't make the outline partial`() {
        val repo = fixture(
            mapOf(
                "mixed.sql" to """
                    |CREATE TABLE staging AS SELECT * FROM raw_events;
                    |INSERT INTO archive SELECT * FROM staging;
                    |SELECT COUNT(*) FROM archive;
                    |""".trimMargin(),
            )
        )

        serving(source(repo)) {
            client.refresh()

            val outline = client.outline("mixed.sql")
            assertFalse(outline.partial())
            assertEquals(listOf(3), outline.queries().map { it["range"]!!.jsonObject["start"]!!.jsonPrimitive.content.toInt() })
            assertEquals(listOf("archive"), outline.strings("references"))
        }
    }

    @Test
    fun `a templated query is outlined, and Jinja stands for no table`() {
        val repo = fixture(
            mapOf(
                "templated.sql" to """
                    |SELECT region, SUM(amount)
                    |FROM {{ dataset }} JOIN regions USING (region_id)
                    |WHERE day >= '{{ from_dttm }}'
                    |{% if filter_values('region') %}
                    |  AND region IN {{ filter_values('region') | where_in }}
                    |{% endif %}
                    |GROUP BY region
                    |""".trimMargin(),
            )
        )

        serving(source(repo)) {
            client.refresh()

            val outline = client.outline("templated.sql")
            assertFalse(outline.partial())
            assertEquals(listOf("regions"), outline.strings("references"))
            assertEquals(listOf("SUM"), outline.strings("functions"))
            assertEquals(json("""{ "start": 1, "end": 7, "startColumn": 1, "endColumn": 15 }"""), outline.queries().single()["range"])
        }
    }

    @Test
    fun `a file that can't be parsed gets a partial outline with what could be found`() {
        val repo = fixture(mapOf("broken.sql" to "SELECT ROW_NUMBER() OVER (PARTITION BY x) FROM t WHERE (\n"))

        serving(source(repo)) {
            client.refresh()

            val outline = client.outline("broken.sql")
            assertTrue(outline.partial())
            assertEquals(listOf("OVER", "PARTITION BY"), outline.strings("keywords"))
            assertEquals(listOf("ROW_NUMBER"), outline.strings("functions"))
            assertEquals(emptyList(), outline.queries())
        }
    }

    @Test
    fun `outlines reflect the repository after a refresh`() {
        val repo = fixture(mapOf("q.sql" to "SELECT * FROM before_table"))

        serving(source(repo)) {
            client.refresh()
            assertEquals(listOf("before_table"), client.outline("q.sql").strings("references"))

            repo.commit(mapOf("q.sql" to "SELECT * FROM after_table"))
            client.refresh()

            assertEquals(listOf("after_table"), client.outline("q.sql").strings("references"))
        }
    }

    @Test
    fun `the configured dialect is used to parse`() {
        // MINUS is Oracle's EXCEPT. sqlglot's generic dialect doesn't know it.
        val repo = fixture(mapOf("oracle.sql" to "SELECT id FROM orders MINUS SELECT id FROM refunds"))

        serving(source(repo, SqlAnalyzer.installed(dialect = "oracle"))) {
            client.refresh()

            val outline = client.outline("oracle.sql")
            assertFalse(outline.partial())
            assertEquals(listOf("orders", "refunds"), outline.strings("references"))
        }
        serving(source(repo, SqlAnalyzer.installed(dialect = null))) {
            client.refresh()

            assertTrue(client.outline("oracle.sql").partial())
        }
    }

    // The analyzer failing, in each way it can. Stand-ins replace it at the
    // command code-source runs.

    private fun standIn(script: String) = SqlAnalyzer(listOf("sh", "-c", script), dialect = null, timeout = 2.seconds)

    private fun assertAnalyzerFailureIsContained(analyzer: SqlAnalyzer, logged: String) {
        val repo = fixture(mapOf("a.sql" to "SELECT 1", "b.txt" to "text"))
        LogCapture().use { logs ->
            serving(source(repo, analyzer)) {
                client.refresh()

                assertEquals(listOf("a.sql", "b.txt"), client.entries().map { it["name"]!!.jsonPrimitive.content })
                val response = client.get("/api/v1/file") { parameter("path", "a.sql") }
                assertEquals("SELECT 1", response.json()["content"]!!.jsonPrimitive.content)
                assertEquals(
                    json(
                        """{ "language": "sql", "partial": true, "keywords": [], "functions": [],
                             "references": [], "sql": { "queries": [] } }"""
                    ),
                    response.json()["outline"],
                )
                assertFalse(client.outline("b.txt").partial())
            }
            assertTrue(logs.messages.any { logged in it }, "expected a log message containing '$logged' in ${logs.messages}")
        }
    }

    @Test
    fun `an analyzer that can't start leaves SQL files with partial outlines`() =
        assertAnalyzerFailureIsContained(SqlAnalyzer(listOf("/nonexistent/python"), dialect = null), "couldn't start")

    @Test
    fun `an analyzer that crashes leaves SQL files with partial outlines`() =
        assertAnalyzerFailureIsContained(standIn("cat > /dev/null; echo 'Traceback: boom' >&2; exit 1"), "exited with status 1")

    @Test
    fun `an analyzer that hangs is stopped, and leaves SQL files with partial outlines`() =
        assertAnalyzerFailureIsContained(standIn("cat > /dev/null; sleep 30"), "didn't finish")

    @Test
    fun `an analyzer that can't be stopped is abandoned, and leaves SQL files with partial outlines`() =
        assertAnalyzerFailureIsContained(
            SqlAnalyzer(listOf("analyzer"), dialect = null, timeout = 1.seconds, stopTimeout = 1.seconds) { Unstoppable() },
            "abandoning it",
        )

    /**
     * A process that never exits, even when killed, like one stuck in the
     * kernel. No real process can be made to do that on demand.
     */
    private class Unstoppable : Process() {
        private val never = java.util.concurrent.CountDownLatch(1)
        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()
        override fun getInputStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun getErrorStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun waitFor(): Int = never.await().let { 0 }
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = never.await(timeout, unit)
        override fun isAlive() = true
        override fun exitValue(): Int = throw IllegalThreadStateException("still running")
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
        override fun descendants(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()
    }

    @Test
    fun `an analyzer that prints rubbish leaves SQL files with partial outlines`() =
        assertAnalyzerFailureIsContained(standIn("cat > /dev/null; echo 'not json'; echo '{\"version\": 1}'"), "unusable lines")

    @Test
    fun `an analyzer that speaks another version of the contract leaves SQL files with partial outlines`() =
        assertAnalyzerFailureIsContained(
            standIn(
                """cat > /dev/null; echo '{"version": 2, "path": "a.sql", "outline": null, "problems": []}'"""
            ),
            "unusable lines",
        )

    @Test
    fun `an unknown dialect leaves SQL files with partial outlines`() =
        assertAnalyzerFailureIsContained(SqlAnalyzer.installed(dialect = "no-such-dialect"), "Unknown SQL dialect")

    @Test
    fun `files the analyzer did outline keep their outlines when it fails part way`() {
        val repo = fixture(mapOf("a.sql" to "SELECT 1", "b.sql" to "SELECT 2"))
        val line = """{"version": 1, "path": "a.sql", "problems": [], "outline": {"language": "sql", "partial": false,
            "keywords": ["LIMIT"], "functions": [], "references": [], "sql": {"queries": []}}}"""
            .replace("\n", "")
        val analyzer = standIn("cat > /dev/null; echo '$line'; exit 1")

        serving(source(repo, analyzer)) {
            client.refresh()

            assertEquals(listOf("LIMIT"), client.outline("a.sql").strings("keywords"))
            assertTrue(client.outline("b.sql").partial())
        }
    }
}
