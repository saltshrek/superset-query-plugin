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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// docs/api.md: GET /api/v1/search (reference, function, keyword, unqualified)
// docs/features/007-file-outlines.md: search by references, functions and keywords
// docs/features/013-search-by-outline.md: outlines and dates in search results
class SearchByOutlineTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    /** A repository of [files] (path to content), served and loaded. */
    private fun serving(files: Map<String, String>, block: suspend ApplicationTestBuilder.() -> Unit) {
        val repo = FixtureRepo().also { cleanup += it; it.commit(files) }
        val source = GitCodeSource(repo.url, null, Files.createTempDirectory("clone").toFile().resolve("repo.git"))
            .also { cleanup += it }
        testApplication {
            application { module(source) }
            assertEquals(HttpStatusCode.OK, client.post("/refresh").status)
            block()
        }
    }

    private suspend fun HttpClient.search(vararg params: Pair<String, String>): HttpResponse =
        get("/api/v1/search") { params.forEach { (name, value) -> parameter(name, value) } }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** The paths a search finds, best first. */
    private suspend fun HttpClient.paths(vararg params: Pair<String, String>): List<String> {
        val response = search(*params)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json()["results"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
    }

    private val tables = mapOf(
        "bare.sql" to "SELECT * FROM orders",
        "schema.sql" to "SELECT * FROM sales.orders",
        "catalog.sql" to "SELECT * FROM iceberg.sales.orders",
        "other_schema.sql" to "SELECT * FROM hr.orders",
        "other_table.sql" to "SELECT * FROM sales.order_items",
    )

    @Test
    fun `a reference finds files that wrote it with at least its parts`() = serving(tables) {
        assertEquals(
            listOf("bare.sql", "catalog.sql", "other_schema.sql", "schema.sql"),
            client.paths("reference" to "orders"),
        )
        assertEquals(listOf("catalog.sql"), client.paths("reference" to "iceberg.sales.orders", "unqualified" to "exclude"))
    }

    @Test
    fun `files that wrote fewer parts than asked match by default, ranked below full matches`() = serving(tables) {
        assertEquals(listOf("catalog.sql", "schema.sql", "bare.sql"), client.paths("reference" to "sales.orders"))
        assertEquals(
            listOf("catalog.sql", "schema.sql", "bare.sql"),
            client.paths("reference" to "sales.orders", "unqualified" to "include"),
        )
    }

    @Test
    fun `unqualified=exclude leaves out files that wrote fewer parts than asked`() = serving(tables) {
        assertEquals(
            listOf("catalog.sql", "schema.sql"),
            client.paths("reference" to "sales.orders", "unqualified" to "exclude"),
        )
    }

    @Test
    fun `references are compared ignoring case and quotes`() = serving(
        mapOf("quoted.sql" to "SELECT * FROM \"Sales\".\"Orders\"", "other.sql" to "SELECT * FROM t")
    ) {
        assertEquals(listOf("quoted.sql"), client.paths("reference" to "sales.orders"))
        assertEquals(listOf("quoted.sql"), client.paths("reference" to "SALES.\"orders\""))
    }

    @Test
    fun `functions and keywords find files that use them, ignoring case`() = serving(
        mapOf(
            "ranked.sql" to "SELECT ROW_NUMBER() OVER (PARTITION BY a ORDER BY b) FROM t",
            "grouped.sql" to "SELECT a, COUNT(*) FROM t GROUP BY a",
        )
    ) {
        assertEquals(listOf("ranked.sql"), client.paths("function" to "row_number"))
        assertEquals(listOf("grouped.sql"), client.paths("keyword" to "group by"))
        assertEquals(listOf("ranked.sql"), client.paths("keyword" to "Partition  By"))
    }

    @Test
    fun `every value given must match, and filters combine with path words and extensions`() = serving(
        mapOf(
            "sales/both.sql" to "SELECT COUNT(*) FROM orders JOIN customers USING (id) GROUP BY 1",
            "sales/orders_only.sql" to "SELECT COUNT(*) FROM orders GROUP BY 1",
            "hr/both.sql" to "SELECT COUNT(*) FROM orders JOIN customers USING (id) GROUP BY 1",
            "sales/both.txt" to "SELECT COUNT(*) FROM orders JOIN customers USING (id) GROUP BY 1",
        )
    ) {
        assertEquals(
            listOf("hr/both.sql", "sales/both.sql"),
            client.paths("reference" to "orders", "reference" to "customers"),
        )
        assertEquals(
            listOf("sales/both.sql"),
            client.paths(
                "q" to "sales", "ext" to "sql", "reference" to "orders", "reference" to "customers",
                "function" to "count", "keyword" to "GROUP BY",
            ),
        )
        assertEquals(emptyList(), client.paths("reference" to "orders", "function" to "sum"))
    }

    @Test
    fun `a search by filters alone counts every match and returns no path matches`() = serving(
        (1..3).associate { "q$it.sql" to "SELECT * FROM orders" }
    ) {
        val json = client.search("reference" to "orders", "limit" to "2").json()

        assertEquals(3, json["total"]!!.jsonPrimitive.int)
        val results = json["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("q1.sql", "q2.sql"), results.map { it["path"]!!.jsonPrimitive.content })
        assertEquals(listOf(0, 0), results.map { it["matches"]!!.jsonArray.size })
    }

    @Test
    fun `each result has the outline and date its folder's listing gives it`() = serving(
        mapOf(
            "sales/top.sql" to "-- Best sellers.\nSELECT name, SUM(qty) FROM sales.orders GROUP BY name",
            "sales/top_notes.txt" to "notes",
        )
    ) {
        val listed = client.get("/api/v1/tree") { parameter("path", "sales") }.json()["entries"]!!.jsonArray
            .associate { it.jsonObject["path"]!!.jsonPrimitive.content to it.jsonObject }

        val results = client.search("q" to "top").json()["results"]!!.jsonArray.map { it.jsonObject }

        assertEquals(listOf("sales/top.sql", "sales/top_notes.txt"), results.map { it["path"]!!.jsonPrimitive.content })
        results.forEach { result ->
            val entry = listed[result["path"]!!.jsonPrimitive.content]!!
            assertEquals(entry["outline"], result["outline"])
            assertEquals(entry["modified"], result["modified"])
        }
        assertEquals("Best sellers.", results[0]["outline"]!!.jsonObject["sql"]!!.jsonObject["queries"]!!.jsonArray[0].jsonObject["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `filters must be well formed`() = serving(tables) {
        listOf(
            arrayOf("reference" to ""),
            arrayOf("reference" to "sales..orders"),
            arrayOf("reference" to "x".repeat(501)),
            arrayOf("function" to ""),
            arrayOf("keyword" to " "),
            arrayOf("reference" to "orders", "unqualified" to "maybe"),
        ).forEach { params ->
            val response = client.search(*params)
            assertEquals(HttpStatusCode.BadRequest, response.status, params.toList().toString())
            assertEquals(
                "invalid_parameter",
                response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
            )
        }
    }
}
