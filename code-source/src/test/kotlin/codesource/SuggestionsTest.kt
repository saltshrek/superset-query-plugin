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

// docs/api.md: GET /api/v1/suggestions
// docs/features/013-search-by-outline.md
class SuggestionsTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    private fun fixture() = FixtureRepo().also { cleanup += it }

    private fun source(url: String) =
        GitCodeSource(url, null, Files.createTempDirectory("clone").toFile().resolve("repo.git")).also { cleanup += it }

    /** A repository of [files] (path to content), served and loaded. */
    private fun serving(files: Map<String, String>, block: suspend ApplicationTestBuilder.() -> Unit) {
        val repo = fixture().also { it.commit(files) }
        testApplication {
            application { module(source(repo.url)) }
            assertEquals(HttpStatusCode.OK, client.post("/refresh").status)
            block()
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.suggestions(vararg params: Pair<String, String>): HttpResponse =
        get("/api/v1/suggestions") { params.forEach { (name, value) -> parameter(name, value) } }

    /** Each suggestion as `field value files`, in order. */
    private suspend fun HttpClient.suggested(vararg params: Pair<String, String>): List<String> {
        val response = suggestions(*params)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json()["suggestions"]!!.jsonArray.map { it.jsonObject }.map {
            "${it["field"]!!.jsonPrimitive.content} ${it["value"]!!.jsonPrimitive.content} ${it["files"]!!.jsonPrimitive.int}"
        }
    }

    private suspend fun HttpClient.searchTotal(vararg params: Pair<String, String>): Int =
        get("/api/v1/search") { params.forEach { (name, value) -> parameter(name, value) } }
            .json()["total"]!!.jsonPrimitive.int

    private val tables = mapOf(
        "bare.sql" to "SELECT * FROM orders",
        "schema.sql" to "SELECT * FROM sales.orders",
        "catalog.sql" to "SELECT * FROM iceberg.sales.orders",
        "items.sql" to "SELECT * FROM sales.order_items",
        "other.sql" to "SELECT * FROM customers",
    )

    @Test
    fun `references are suggested by any name they can be searched by`() = serving(tables) {
        assertEquals(
            listOf("reference orders 3", "reference order_items 1"),
            client.suggested("q" to "ord", "field" to "reference"),
        )
        assertEquals(
            listOf("reference sales.orders 3", "reference sales.order_items 1"),
            client.suggested("q" to "sales.or", "field" to "reference"),
        )
        assertEquals(
            // Files that wrote fewer parts count too, as search finds them.
            listOf("reference iceberg.sales.orders 3"),
            client.suggested("q" to "ICEBERG", "field" to "reference"),
        )
    }

    @Test
    fun `each count is the total a search for that value alone gives`() = serving(tables) {
        val response = client.suggestions("q" to "s", "field" to "reference").json()["suggestions"]!!.jsonArray
        assertEquals(2, response.size)
        response.map { it.jsonObject }.forEach {
            val value = it["value"]!!.jsonPrimitive.content
            assertEquals(client.searchTotal("reference" to value), it["files"]!!.jsonPrimitive.int, value)
        }
    }

    @Test
    fun `functions and keywords are suggested by prefix, ignoring case and runs of spaces`() = serving(
        mapOf(
            "ranked.sql" to "SELECT ROW_NUMBER() OVER (PARTITION BY a ORDER BY b), ROUND(c) FROM t",
            "grouped.sql" to "SELECT a, ROUND(SUM(c)) FROM t GROUP BY a ORDER BY a",
        )
    ) {
        assertEquals(listOf("function ROUND 2", "function ROW_NUMBER 1"), client.suggested("q" to "ro", "field" to "function"))
        assertEquals(listOf("keyword ORDER BY 2"), client.suggested("q" to "order  b", "field" to "keyword"))
        assertEquals(listOf("keyword PARTITION BY 1"), client.suggested("q" to "Partition", "field" to "keyword"))
    }

    @Test
    fun `without fields every kind is suggested, ordered by files, then field, then value`() = serving(
        mapOf(
            "a.sql" to "SELECT ORD(x) FROM orders ORDER BY 1",
            "b.sql" to "SELECT ORD(x) FROM orders ORDER BY 1",
            "c.sql" to "SELECT * FROM ordinals ORDER BY 1",
        )
    ) {
        assertEquals(
            listOf("keyword ORDER BY 3", "reference orders 2", "function ORD 2", "reference ordinals 1"),
            client.suggested("q" to "ord"),
        )
        assertEquals(
            listOf("reference orders 2", "function ORD 2", "reference ordinals 1"),
            client.suggested("q" to "ord", "field" to "function", "field" to "reference"),
        )
        assertEquals(listOf("keyword ORDER BY 3", "reference orders 2"), client.suggested("q" to "ord", "limit" to "2"))
    }

    @Test
    fun `ext counts only files with those extensions, and leaves out values only others have`() = serving(
        mapOf(
            "a.sql" to "SELECT * FROM orders",
            "b.txt" to "SELECT * FROM orders",
            "c.txt" to "SELECT * FROM order_items",
        )
    ) {
        assertEquals(listOf("reference orders 1"), client.suggested("q" to "ord", "field" to "reference", "ext" to "sql"))
    }

    @Test
    fun `values only files under a dot-entry have aren't suggested`() = serving(
        mapOf(
            "a.sql" to "SELECT * FROM orders",
            ".drafts/b.sql" to "SELECT * FROM orders",
            ".drafts/c.sql" to "SELECT * FROM order_items",
        )
    ) {
        assertEquals(listOf("reference orders 1"), client.suggested("q" to "ord", "field" to "reference"))
    }

    @Test
    fun `nothing typed means no suggestions`() = serving(tables) {
        assertEquals(emptyList(), client.suggested())
        assertEquals(emptyList(), client.suggested("q" to "  "))
        assertEquals(emptyList(), client.suggested("q" to "zzz"))
    }

    @Test
    fun `suggestions follow the served tree after a refresh`() {
        val repo = fixture().also { it.commit(mapOf("a.sql" to "SELECT * FROM orders")) }
        testApplication {
            application { module(source(repo.url)) }
            client.post("/refresh")
            assertEquals(listOf("reference orders 1"), client.suggested("q" to "ord", "field" to "reference"))

            repo.commit(mapOf("b.sql" to "SELECT * FROM order_items"))
            client.post("/refresh")

            assertEquals(listOf("reference order_items 1"), client.suggested("q" to "ord", "field" to "reference"))
        }
    }

    @Test
    fun `parameters must be well formed`() = serving(tables) {
        listOf(
            arrayOf("q" to "x".repeat(501)),
            arrayOf("q" to "ord", "field" to "table"),
            arrayOf("q" to "ord", "ext" to ".sql"),
            arrayOf("q" to "ord", "ext" to ""),
            arrayOf("q" to "ord", "limit" to "0"),
            arrayOf("q" to "ord", "limit" to "101"),
            arrayOf("q" to "ord", "limit" to "ten"),
        ).forEach { params ->
            val response = client.suggestions(*params)
            assertEquals(HttpStatusCode.BadRequest, response.status, params.toList().toString())
            assertEquals("invalid_parameter", response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `not ready until the repository has been loaded`() {
        val repo = fixture().also { it.commit(mapOf("a.sql" to "SELECT * FROM orders")) }
        testApplication {
            application { module(source(repo.url)) }
            val response = client.suggestions("q" to "ord")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("not_ready", response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }
}
