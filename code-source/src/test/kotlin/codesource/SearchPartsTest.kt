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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// docs/api.md: GET /api/v1/search (parts' names and descriptions)
// docs/features/014-search-descriptions-and-ctes.md
class SearchPartsTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    private fun fixture(files: Map<String, String>) = FixtureRepo().also { cleanup += it; it.commit(files) }

    /** [repo], served and loaded. */
    private fun serving(repo: FixtureRepo, block: suspend ApplicationTestBuilder.() -> Unit) {
        val source = GitCodeSource(repo.url, null, Files.createTempDirectory("clone").toFile().resolve("repo.git"))
            .also { cleanup += it }
        testApplication {
            application { module(source) }
            assertEquals(HttpStatusCode.OK, client.refresh().status)
            block()
        }
    }

    private fun serving(files: Map<String, String>, block: suspend ApplicationTestBuilder.() -> Unit) =
        serving(fixture(files), block)

    private suspend fun HttpClient.refresh(): HttpResponse = post("/refresh")

    /** The paths [q] finds, best first. */
    private suspend fun HttpClient.paths(q: String): List<String> {
        val response = get("/api/v1/search") { parameter("q", q) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]!!.jsonArray
            .map { it.jsonObject["path"]!!.jsonPrimitive.content }
    }

    @Test
    fun `finds files by their CTEs' names, the last word as a prefix`() = serving(
        mapOf(
            "a.sql" to "WITH monthly_revenue AS (SELECT 1 AS x) SELECT * FROM monthly_revenue",
            "b.sql" to "WITH weekly AS (SELECT 1 AS x) SELECT * FROM weekly",
        ),
    ) {
        assertEquals(listOf("a.sql"), client.paths("monthly revenue"))
        assertEquals(listOf("a.sql"), client.paths("revenue monthly"), "words of a name in any order")
        assertEquals(listOf("a.sql"), client.paths("monthly rev"))
        assertEquals(listOf("b.sql"), client.paths("week"))
    }

    @Test
    fun `finds files by their queries' and CTEs' descriptions`() = serving(
        mapOf(
            "a.sql" to """
                |-- Revenue per month, with a running total.
                |SELECT 1
            """.trimMargin(),
            "b.sql" to """
                |-- Customers by region.
                |
                |-- One row per region.
                |WITH regions AS (SELECT 1 AS x)
                |SELECT * FROM regions
            """.trimMargin(),
            "c.sql" to """
                |-- First query.
                |SELECT 1;
                |
                |-- Second query, of churned customers.
                |SELECT 2
            """.trimMargin(),
        ),
    ) {
        assertEquals(listOf("a.sql"), client.paths("running total"))
        assertEquals(listOf("a.sql"), client.paths("running tot"))
        assertEquals(listOf("b.sql"), client.paths("one row per"), "a CTE's description")
        assertEquals(listOf("c.sql"), client.paths("churned"), "any query's description")
    }

    @Test
    fun `words may match in different places, but every word must match somewhere`() = serving(
        mapOf(
            "sales/top.sql" to """
                |-- Best sellers of the year.
                |WITH ranked AS (SELECT 1 AS x)
                |SELECT * FROM ranked
            """.trimMargin(),
        ),
    ) {
        assertEquals(listOf("sales/top.sql"), client.paths("top ranked"), "file name and CTE name")
        assertEquals(listOf("sales/top.sql"), client.paths("ranked sellers"), "CTE name and description")
        assertEquals(listOf("sales/top.sql"), client.paths("sales year"), "folder and description")
        assertEquals(emptyList(), client.paths("top ranked monthly"))
    }

    @Test
    fun `each place ranks above the next, and whole words above prefixes in each`() = serving(
        // Path order is close to the reverse of rank, so ranking, not ties,
        // decides the order.
        mapOf(
            "h/revenue.sql" to "SELECT 1",
            "g/revenues.sql" to "SELECT 1",
            "f.sql" to "WITH revenue AS (SELECT 1 AS x) SELECT * FROM revenue",
            "e.sql" to "WITH revenues AS (SELECT 1 AS x) SELECT * FROM revenues",
            "d.sql" to "-- Total revenue.\nSELECT 1",
            "c.sql" to "-- Revenues in total.\nSELECT 1",
            "x/revenue/b.sql" to "SELECT 1",
            "revenues/a.sql" to "SELECT 1",
        ),
    ) {
        assertEquals(
            listOf(
                "h/revenue.sql", // file name, whole word
                "g/revenues.sql", // file name, prefix
                "f.sql", // CTE name, whole word
                "e.sql", // CTE name, prefix
                "d.sql", // description, whole word
                "c.sql", // description, prefix
                "x/revenue/b.sql", // folder, whole word
                "revenues/a.sql", // folder, prefix
            ),
            client.paths("revenue"),
        )
    }

    @Test
    fun `among files words score the same, descriptions more about the words rank higher`() = serving(
        // Path order is the reverse of the expected order.
        mapOf(
            "a.sql" to "-- Orders, customers, products and returns, with revenue for each region and quarter.\nSELECT 1",
            "b.sql" to "-- Revenue per day.\nSELECT 1",
            "c.sql" to "-- Revenue per day, and revenue per week.\nSELECT 1",
        ),
    ) {
        assertEquals(listOf("c.sql", "b.sql", "a.sql"), client.paths("revenue"), "said twice, then short, then in passing")
    }

    @Test
    fun `how much descriptions are about the words never overturns where the words matched`() = serving(
        mapOf(
            "a.sql" to "WITH revenue AS (SELECT 1 AS x) SELECT * FROM revenue",
            "b.sql" to "-- Revenue, revenue, revenue.\nSELECT 1",
        ),
    ) {
        assertEquals(listOf("a.sql", "b.sql"), client.paths("revenue"))
    }

    @Test
    fun `a last word still being typed doesn't count towards relevance until it's whole`() = serving(
        mapOf(
            "a.sql" to "-- Orders, customers, products and returns, with revenue for each region and quarter.\nSELECT 1",
            "b.sql" to "-- Revenue per day.\nSELECT 1",
        ),
    ) {
        assertEquals(listOf("a.sql", "b.sql"), client.paths("reve"), "tied, so in path order")
        assertEquals(listOf("b.sql", "a.sql"), client.paths("revenue"))
    }

    @Test
    fun `relevance ranks above references written in full`() = serving(
        mapOf(
            "a.sql" to "-- Orders over the years, with returns, refunds and revenue, by region.\nSELECT * FROM sales.orders",
            "b.sql" to "-- Revenue per day.\nSELECT * FROM orders",
        ),
    ) {
        val response = client.get("/api/v1/search") {
            parameter("q", "revenue")
            parameter("reference", "sales.orders")
        }
        val paths = Json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]!!.jsonArray
            .map { it.jsonObject["path"]!!.jsonPrimitive.content }
        assertEquals(listOf("b.sql", "a.sql"), paths)
    }

    /** The `parts` of [q]'s results, by path. */
    private suspend fun HttpClient.parts(vararg params: Pair<String, String>): Map<String, JsonArray> {
        val response = get("/api/v1/search") { params.forEach { (name, value) -> parameter(name, value) } }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]!!.jsonArray.associate {
            it.jsonObject["path"]!!.jsonPrimitive.content to it.jsonObject["parts"]!!.jsonArray
        }
    }

    private fun range(start: Int, end: Int, startColumn: Int, endColumn: Int) =
        Json.parseToJsonElement("""{"start":$start,"end":$end,"startColumn":$startColumn,"endColumn":$endColumn}""")

    private fun matches(vararg ranges: Pair<Int, Int>) =
        Json.parseToJsonElement(ranges.joinToString(",", "[", "]") { (start, end) -> """{"start":$start,"end":$end}""" })

    @Test
    fun `results list the parts that matched, with their ranges, names, descriptions and where they matched`() = serving(
        mapOf(
            "monthly.sql" to """
                |-- Revenue per month, with a running total.
                |
                |-- One row per month.
                |WITH monthly AS (
                |  SELECT 1 AS x
                |),
                |yearly AS (SELECT 2 AS y)
                |SELECT * FROM monthly, yearly
            """.trimMargin(),
        ),
    ) {
        val parts = client.parts("q" to "month").getValue("monthly.sql")
        assertEquals(2, parts.size, "the query and monthly, in order; not yearly")
        val (query, monthly) = parts.map { it.jsonObject }

        assertEquals(range(4, 8, 1, 29), query["range"])
        assertEquals(JsonNull, query["name"])
        assertEquals(matches(), query["nameMatches"])
        assertEquals(JsonPrimitive("Revenue per month, with a running total."), query["description"])
        assertEquals(matches(12 to 17), query["descriptionMatches"])

        assertEquals(range(4, 6, 6, 1), monthly["range"])
        assertEquals(JsonPrimitive("monthly"), monthly["name"])
        assertEquals(matches(0 to 5), monthly["nameMatches"], "only the prefix of the last word")
        assertEquals(JsonPrimitive("One row per month."), monthly["description"])
        assertEquals(matches(12 to 17), monthly["descriptionMatches"])
    }

    @Test
    fun `parts' matches are every word that matched, in code points`() = serving(
        mapOf("a.sql" to "-- \uD83D\uDE00 revenue per day, and revenue per week.\nSELECT 1"),
    ) {
        val part = client.parts("q" to "revenue per").getValue("a.sql").single().jsonObject
        assertEquals(
            matches(2 to 9, 10 to 13, 23 to 30, 31 to 34),
            part["descriptionMatches"],
            "the emoji is one code point (two UTF-16 units)",
        )
    }

    @Test
    fun `results matched only by path list no parts`() = serving(
        mapOf("revenue.sql" to "-- Sales per day.\nWITH daily AS (SELECT 1 AS x) SELECT * FROM daily"),
    ) {
        assertEquals(JsonArray(emptyList()), client.parts("q" to "revenue").getValue("revenue.sql"))
    }

    @Test
    fun `searches by filters alone list no parts`() = serving(
        mapOf("a.sql" to "-- Orders per day.\nWITH orders_daily AS (SELECT * FROM orders) SELECT * FROM orders_daily"),
    ) {
        assertEquals(JsonArray(emptyList()), client.parts("reference" to "orders").getValue("a.sql"))
    }

    @Test
    fun `refresh finds parts that were added`() {
        val repo = fixture(mapOf("a.sql" to "SELECT 1"))
        serving(repo) {
            assertEquals(emptyList(), client.paths("cohorts"))
            repo.commit(mapOf("a.sql" to "-- Signups by cohorts.\nSELECT 1"))
            client.refresh()
            assertEquals(listOf("a.sql"), client.paths("cohorts"))
        }
    }
}
