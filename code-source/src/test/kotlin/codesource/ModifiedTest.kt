package codesource

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// docs/api.md: `modified` in GET /api/v1/tree and GET /api/v1/file
// docs/features/008-show-outlines.md
class ModifiedTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    private fun fixture() = FixtureRepo().also { cleanup += it }

    private fun source(url: String, ref: String? = null) =
        GitCodeSource(url, ref, Files.createTempDirectory("clone").toFile().resolve("repo.git")).also { cleanup += it }

    private fun serving(source: CodeSource, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(source) }
        block()
    }

    private suspend fun HttpClient.json(url: String, path: String): JsonObject {
        val response = get(url) { parameter("path", path) }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** Each entry of the listing of [folder], by name. */
    private suspend fun HttpClient.entries(folder: String = ""): Map<String, JsonObject> =
        json("/api/v1/tree", folder)["entries"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject
        }

    /** When the listing of [path]'s folder says [path] last changed. */
    private suspend fun HttpClient.listedModified(path: String): String? {
        val entry = entries(path.substringBeforeLast('/', ""))[path.substringAfterLast('/')]!!
        return entry["modified"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
    }

    private suspend fun HttpClient.readModified(path: String): String? =
        json("/api/v1/file", path)["modified"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    private val january = Instant.parse("2025-01-10T09:00:00Z")
    private val march = Instant.parse("2025-03-12T14:03:00Z")
    private val may = Instant.parse("2025-05-20T08:30:15Z")

    @Test
    fun `a file was modified when the newest commit that changed it was committed`() {
        val repo = fixture()
        repo.commit(mapOf("a.sql" to "select 1", "orders/b.sql" to "select 2"), committed = january)
        // Authored long before it was committed, as after a rebase: the commit time counts.
        repo.commit(mapOf("a.sql" to "select 1", "orders/b.sql" to "select 3"), committed = march, authored = january)
        repo.commit(mapOf("a.sql" to "select 1", "orders/b.sql" to "select 3", "c.sql" to "select 4"), committed = may)

        serving(source(repo.url)) {
            client.post("/refresh")

            assertEquals("2025-01-10T09:00:00Z", client.listedModified("a.sql"))
            assertEquals("2025-03-12T14:03:00Z", client.listedModified("orders/b.sql"))
            assertEquals("2025-05-20T08:30:15Z", client.listedModified("c.sql"))
        }
    }

    @Test
    fun `the file response gives the same date as the listing`() {
        val repo = fixture()
        repo.commit(mapOf("orders/b.sql" to "select 2"), committed = january)
        repo.commit(mapOf("orders/b.sql" to "select 3"), committed = march)

        serving(source(repo.url)) {
            client.post("/refresh")

            assertEquals("2025-03-12T14:03:00Z", client.readModified("orders/b.sql"))
            assertEquals(client.listedModified("orders/b.sql"), client.readModified("orders/b.sql"))
        }
    }

    @Test
    fun `a change merged from a branch counts from the merge`() {
        val repo = fixture()
        val base = repo.commit(mapOf("a.sql" to "select 1", "b.sql" to "select 2"), committed = january)
        val branchTip = repo.commit(
            mapOf("a.sql" to "select 1 -- changed", "b.sql" to "select 2"),
            branch = "feature", from = base, committed = march,
        )
        repo.commit(mapOf("a.sql" to "select 1 -- changed", "b.sql" to "select 2"), committed = may, merging = branchTip)

        serving(source(repo.url)) {
            client.post("/refresh")

            assertEquals("2025-05-20T08:30:15Z", client.listedModified("a.sql"))
            assertEquals("2025-01-10T09:00:00Z", client.listedModified("b.sql"))
        }
    }

    @Test
    fun `dates follow the served ref`() {
        val repo = fixture()
        val first = repo.commit(mapOf("a.sql" to "select 1"), committed = january)
        repo.tag("v1", first)
        repo.commit(mapOf("a.sql" to "select 2"), committed = march)

        serving(source(repo.url, ref = "v1")) {
            client.post("/refresh")

            assertEquals("2025-01-10T09:00:00Z", client.listedModified("a.sql"))
        }
    }

    @Test
    fun `a refresh brings newer dates`() {
        val repo = fixture()
        repo.commit(mapOf("a.sql" to "select 1", "b.sql" to "select 2"), committed = january)

        serving(source(repo.url)) {
            client.post("/refresh")
            assertEquals("2025-01-10T09:00:00Z", client.listedModified("a.sql"))

            repo.commit(mapOf("a.sql" to "select 1 -- changed", "b.sql" to "select 2"), committed = march)
            client.post("/refresh")

            assertEquals("2025-03-12T14:03:00Z", client.listedModified("a.sql"))
            assertEquals("2025-01-10T09:00:00Z", client.listedModified("b.sql"))
        }
    }

    @Test
    fun `a commit that changes no files changes no dates`() {
        val repo = fixture()
        repo.commit(mapOf("a.sql" to "select 1"), committed = january)

        serving(source(repo.url)) {
            client.post("/refresh")
            repo.commit(mapOf("a.sql" to "select 1"), committed = march)
            client.post("/refresh")

            assertEquals("2025-01-10T09:00:00Z", client.listedModified("a.sql"))
        }
    }

    @Test
    fun `every file has a date and directories have none`() {
        val repo = fixture()
        repo.commit(mapOf("notes.txt" to "hello", "README.md" to "# examples", "orders/b.sql" to "select 2"), committed = january)

        serving(source(repo.url)) {
            client.post("/refresh")

            val entries = client.entries()
            assertEquals("2025-01-10T09:00:00Z", entries["notes.txt"]!!["modified"]!!.jsonPrimitive.content)
            assertEquals("2025-01-10T09:00:00Z", entries["README.md"]!!["modified"]!!.jsonPrimitive.content)
            assertFalse("modified" in entries["orders"]!!)
        }
    }
}
