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
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// docs/api.md: GET /api/v1/search
// docs/features/006-filename-search.md
class SearchTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    private fun fixture() = FixtureRepo().also { cleanup += it }

    private fun source(url: String) =
        GitCodeSource(url, null, Files.createTempDirectory("clone").toFile().resolve("repo.git")).also { cleanup += it }

    private fun serving(repo: FixtureRepo, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(source(repo.url)) }
        block()
    }

    /** A repository with an empty file at each of [paths], served and loaded. */
    private fun servingFiles(vararg paths: String, block: suspend ApplicationTestBuilder.() -> Unit) {
        val repo = fixture()
        repo.commit(paths.associateWith { "" })
        serving(repo) {
            client.refresh()
            block()
        }
    }

    private suspend fun HttpClient.search(q: String?, limit: String? = null, ext: List<String> = emptyList()): HttpResponse =
        get("/api/v1/search") {
            if (q != null) parameter("q", q)
            if (limit != null) parameter("limit", limit)
            ext.forEach { parameter("ext", it) }
        }

    /** The paths [q] finds, best first. */
    private suspend fun HttpClient.paths(q: String?, limit: String? = null, ext: List<String> = emptyList()): List<String> {
        val response = search(q, limit, ext)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.results().map { it["path"]!!.jsonPrimitive.content }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.results(): List<JsonObject> = json()["results"]!!.jsonArray.map { it.jsonObject }

    private suspend fun HttpResponse.total(): Int = json()["total"]!!.jsonPrimitive.int

    private suspend fun HttpResponse.errorCode(): String =
        json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private suspend fun HttpClient.refresh(): HttpResponse = post("/refresh")

    @Test
    fun `finds files whose path has every word, the last as a prefix`() = servingFiles(
        "customers/new_customers_by_month.sql",
        "customers/churn.sql",
        "orders/new_orders.sql",
    ) {
        assertEquals(listOf("customers/new_customers_by_month.sql"), client.paths("new cust"))
        assertEquals(
            setOf("customers/new_customers_by_month.sql", "orders/new_orders.sql"),
            client.paths("ne").toSet(),
        )
        assertEquals(emptyList(), client.paths("ne cust"), "only the last word is a prefix")
        assertEquals(emptyList(), client.paths("new cust zzz"), "every word must match")
    }

    @Test
    fun `words split at non-alphanumerics and lower-to-upper case changes, ignoring case`() = servingFiles(
        "reports/monthlyChurnRate.sql",
        "HTTPServerLogs.sql",
        "q4_sales-by.region.sql",
        "weekly report.sql",
    ) {
        assertEquals(listOf("reports/monthlyChurnRate.sql"), client.paths("churn rate"))
        assertEquals(listOf("reports/monthlyChurnRate.sql"), client.paths("monthlyChurn"))
        assertEquals(listOf("reports/monthlyChurnRate.sql"), client.paths("MONTHLY_churn"))
        assertEquals(listOf("HTTPServerLogs.sql"), client.paths("http server logs"))
        assertEquals(listOf("q4_sales-by.region.sql"), client.paths("q4 sales by region"))
        assertEquals(emptyList(), client.paths("4"), "letters and digits together are one word")
        assertEquals(listOf("weekly report.sql"), client.paths("weekly-report"))
    }

    @Test
    fun `matches in the file name rank above matches only in folders`() = servingFiles(
        "orders/summary.sql",
        "reports/orders_by_day.sql",
    ) {
        assertEquals(listOf("reports/orders_by_day.sql", "orders/summary.sql"), client.paths("orders"))
    }

    @Test
    fun `ties are in path order`() = servingFiles("b/top.sql", "a/top.sql", "c/top.sql") {
        assertEquals(listOf("a/top.sql", "b/top.sql", "c/top.sql"), client.paths("top"))
    }

    @Test
    fun `results are files, best first`() = servingFiles("orders/summary.sql", "reports/orders_by_day.sql") {
        val results = client.search("orders").results()
        assertEquals(listOf("file", "file"), results.map { it["type"]!!.jsonPrimitive.content })
        val scores = results.map { it["score"]!!.jsonPrimitive.double }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `matches are the matched parts of the path, in code points`() = servingFiles(
        "customers/new_customers_by_month.sql",
        "😀/report.sql",
    ) {
        fun ranges(result: JsonObject) = result["matches"]!!.jsonArray.map {
            val o = it.jsonObject
            o["start"]!!.jsonPrimitive.int to o["end"]!!.jsonPrimitive.int
        }

        val customers = client.search("new cust").results().single()
        // "cust" is a prefix of both "customers"; "new" is a whole word.
        assertEquals(listOf(0 to 4, 10 to 13, 14 to 18), ranges(customers))

        val emoji = client.search("report").results().single()
        assertEquals(listOf(2 to 8), ranges(emoji), "😀 is one code point (two UTF-16 units)")
    }

    @Test
    fun `ext limits results to files with those extensions, ignoring case`() = servingFiles(
        "x_a.sql", "x_b.SQL", "x_c.md", "x_d", "x_e.py",
    ) {
        assertEquals(listOf("x_a.sql", "x_b.SQL"), client.paths("x", ext = listOf("sql")))
        assertEquals(listOf("x_a.sql", "x_b.SQL", "x_c.md"), client.paths("x", ext = listOf("sql", "MD")))
        assertEquals(listOf("x_a.sql", "x_b.SQL", "x_c.md", "x_d", "x_e.py"), client.paths("x"))
    }

    @Test
    fun `limit caps the results and total counts every match`() {
        val names = (1..25).map { "f_%02d.sql".format(it) }
        servingFiles(*names.toTypedArray()) {
            val byDefault = client.search("f")
            assertEquals(names.take(20), byDefault.results().map { it["path"]!!.jsonPrimitive.content })
            assertEquals(25, byDefault.total())

            val three = client.search("f", limit = "3")
            assertEquals(names.take(3), three.results().map { it["path"]!!.jsonPrimitive.content })
            assertEquals(25, three.total())

            assertEquals(25, client.paths("f", limit = "100").size)
        }
    }

    @Test
    fun `dot-entries, symlinks and submodules are never found`() {
        val repo = fixture()
        repo.commit(
            mapOf(
                "report.sql" to "",
                ".github/report.sql" to "",
                "a/.report.sql" to "",
                "a/.hidden/report.sql" to "",
                "report_link.sql" to FixtureRepo.Symlink("report.sql"),
                "report_module" to FixtureRepo.Submodule,
            )
        )
        serving(repo) {
            client.refresh()
            assertEquals(listOf("report.sql"), client.paths("report"))
        }
    }

    @Test
    fun `a search with no words finds nothing`() = servingFiles("x.sql") {
        listOf(null, "", "   ", "-_./ ").forEach {
            val response = client.search(it)
            assertEquals(HttpStatusCode.OK, response.status, "q '$it'")
            assertEquals(emptyList(), response.results(), "q '$it'")
            assertEquals(0, response.total(), "q '$it'")
        }
    }

    @Test
    fun `bad parameters are rejected`() = servingFiles("x.sql") {
        assertEquals(HttpStatusCode.OK, client.search("x".repeat(500)).status)

        val bad = listOf(
            client.search("x".repeat(501)),
            client.search("x", limit = "0"),
            client.search("x", limit = "101"),
            client.search("x", limit = "ten"),
            client.search("x", limit = ""),
            client.search("x", ext = listOf("")),
            client.search("x", ext = listOf(".sql")),
            client.search("x", ext = listOf("a/b")),
        )
        bad.forEachIndexed { i, response ->
            assertEquals(HttpStatusCode.BadRequest, response.status, "case $i")
            assertEquals("invalid_parameter", response.errorCode(), "case $i")
        }
    }

    @Test
    fun `not ready until the first successful refresh`() {
        val repo = fixture()
        repo.commit(mapOf("x.sql" to ""))

        serving(repo) {
            val response = client.search("x")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("not_ready", response.errorCode())

            client.refresh()
            assertEquals(listOf("x.sql"), client.paths("x"))
        }
    }

    @Test
    fun `refresh finds added files and loses removed ones`() {
        val repo = fixture()
        repo.commit(mapOf("old_report.sql" to ""))

        serving(repo) {
            client.refresh()
            assertEquals(listOf("old_report.sql"), client.paths("report"))

            repo.commit(mapOf("new_report.sql" to ""))
            assertEquals(listOf("old_report.sql"), client.paths("report"), "unchanged before refresh")

            client.refresh()
            assertEquals(listOf("new_report.sql"), client.paths("report"))
        }
    }
}
