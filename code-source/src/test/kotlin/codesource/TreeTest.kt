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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// docs/api.md: GET /api/v1/tree, POST /refresh
// docs/features/003-browse-tree.md
class TreeTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    private fun fixture(defaultBranch: String = "main") = FixtureRepo(defaultBranch).also { cleanup += it }

    private fun source(url: String, ref: String? = null) =
        GitCodeSource(url, ref, Files.createTempDirectory("clone").toFile().resolve("repo.git")).also { cleanup += it }

    private fun serving(source: CodeSource, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(source) }
        block()
    }

    private suspend fun HttpClient.tree(path: String? = null): HttpResponse =
        get("/api/v1/tree") { if (path != null) parameter("path", path) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.entries(): List<Triple<String, String, String>> =
        json()["entries"]!!.jsonArray.map {
            val o = it.jsonObject
            Triple(o["name"]!!.jsonPrimitive.content, o["path"]!!.jsonPrimitive.content, o["type"]!!.jsonPrimitive.content)
        }

    private suspend fun HttpResponse.errorCode(): String =
        json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private suspend fun HttpClient.refresh(): HttpResponse = post("/refresh")

    @Test
    fun `lists the root with directories and files sorted by name`() {
        val repo = fixture()
        repo.commit(
            mapOf(
                "orders/top.sql" to "select 1",
                "b.sql" to "select 2",
                "README.md" to "# examples",
                ".github/ci.yml" to "on: push",
                "a" to "file named a",
                "a.sql" to "select 3",
            )
        )

        serving(source(repo.url)) {
            assertEquals(HttpStatusCode.OK, client.refresh().status)

            val response = client.tree()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("", response.json()["path"]!!.jsonPrimitive.content)
            assertEquals(
                listOf(
                    Triple(".github", ".github", "directory"),
                    Triple("README.md", "README.md", "file"),
                    Triple("a", "a", "file"),
                    Triple("a.sql", "a.sql", "file"),
                    Triple("b.sql", "b.sql", "file"),
                    Triple("orders", "orders", "directory"),
                ),
                response.entries(),
            )
        }
    }

    @Test
    fun `empty path is the root`() {
        val repo = fixture()
        repo.commit(mapOf("x.sql" to "select 1"))

        serving(source(repo.url)) {
            client.refresh()
            assertEquals(client.tree().bodyAsText(), client.tree("").bodyAsText())
        }
    }

    @Test
    fun `lists a nested directory with full entry paths`() {
        val repo = fixture()
        repo.commit(mapOf("orders/archive/2024.sql" to "select 1", "orders/top.sql" to "select 2"))

        serving(source(repo.url)) {
            client.refresh()

            val response = client.tree("orders")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("orders", response.json()["path"]!!.jsonPrimitive.content)
            assertEquals(
                listOf(
                    Triple("archive", "orders/archive", "directory"),
                    Triple("top.sql", "orders/top.sql", "file"),
                ),
                response.entries(),
            )
            assertEquals(
                listOf(Triple("2024.sql", "orders/archive/2024.sql", "file")),
                client.tree("orders/archive").entries(),
            )
        }
    }

    @Test
    fun `sorts by code point, not UTF-16 unit or case`() {
        val repo = fixture()
        // U+FF5E is above the surrogates in UTF-16, U+1F600 is encoded with them.
        repo.commit(mapOf("～.sql" to "", "😀.sql" to "", "B.sql" to "", "a.sql" to ""))

        serving(source(repo.url)) {
            client.refresh()

            assertEquals(
                listOf("B.sql", "a.sql", "～.sql", "😀.sql"),
                client.tree().entries().map { it.first },
            )
        }
    }

    @Test
    fun `executables are files, symlinks and submodules are left out`() {
        val repo = fixture()
        repo.commit(
            mapOf(
                "run.sh" to FixtureRepo.Text("#!/bin/sh", executable = true),
                "link.sql" to FixtureRepo.Symlink("run.sh"),
                "vendored" to FixtureRepo.Submodule,
            )
        )

        serving(source(repo.url)) {
            client.refresh()

            assertEquals(listOf(Triple("run.sh", "run.sh", "file")), client.tree().entries())
            assertEquals(HttpStatusCode.NotFound, client.tree("link.sql").status)
            assertEquals(HttpStatusCode.NotFound, client.tree("vendored").status)
        }
    }

    @Test
    fun `non-canonical paths are rejected`() {
        val repo = fixture()
        repo.commit(mapOf("orders/top.sql" to "select 1"))

        serving(source(repo.url)) {
            client.refresh()

            listOf("/", "/orders", "orders/", "..", "../etc", "orders/..", "orders/../orders", "./orders").forEach {
                val response = client.tree(it)
                assertEquals(HttpStatusCode.BadRequest, response.status, "path '$it'")
                assertEquals("invalid_path", response.errorCode(), "path '$it'")
            }
        }
    }

    @Test
    fun `a file is not a directory`() {
        val repo = fixture()
        repo.commit(mapOf("orders/top.sql" to "select 1"))

        serving(source(repo.url)) {
            client.refresh()

            val response = client.tree("orders/top.sql")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("not_a_directory", response.errorCode())
        }
    }

    @Test
    fun `a missing path is not found`() {
        val repo = fixture()
        repo.commit(mapOf("orders/top.sql" to "select 1"))

        serving(source(repo.url)) {
            client.refresh()

            val response = client.tree("customers")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("not_found", response.errorCode())
        }
    }

    @Test
    fun `not ready until the first successful refresh`() {
        val repo = fixture()
        repo.commit(mapOf("x.sql" to "select 1"))

        serving(source(repo.url)) {
            val response = client.tree()
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("not_ready", response.errorCode())

            client.refresh()
            assertEquals(HttpStatusCode.OK, client.tree().status)
        }
    }

    @Test
    fun `a failed clone reports refresh_failed and stays not ready`() {
        val missing = Files.createTempDirectory("missing").toFile().resolve("nope.git").toURI().toString()

        serving(source(missing)) {
            val refresh = client.refresh()
            assertEquals(HttpStatusCode.ServiceUnavailable, refresh.status)
            assertEquals("refresh_failed", refresh.errorCode())

            assertEquals("not_ready", client.tree().errorCode())
        }
    }

    @Test
    fun `refresh picks up new commits on the configured ref`() {
        val repo = fixture()
        repo.commit(mapOf("old.sql" to "select 1"))

        serving(source(repo.url)) {
            client.refresh()
            assertEquals(listOf("old.sql"), client.tree().entries().map { it.first })

            repo.commit(mapOf("new.sql" to "select 2"))
            assertEquals(listOf("old.sql"), client.tree().entries().map { it.first }, "unchanged before refresh")

            assertEquals(HttpStatusCode.OK, client.refresh().status)
            assertEquals(listOf("new.sql"), client.tree().entries().map { it.first })
        }
    }

    private suspend fun eventually(timeout: Duration = 10.seconds, check: suspend () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!check()) {
            if (start.elapsedNow() > timeout) throw AssertionError("condition not met within $timeout")
            kotlinx.coroutines.delay(50)
        }
    }

    @Test
    fun `the timer refreshes on its interval`() {
        val repo = fixture()
        repo.commit(mapOf("old.sql" to "select 1"))
        val source = source(repo.url)

        serving(source) {
            RefreshScheduler(source, interval = 100.milliseconds).also { cleanup += it }.start()
            eventually { client.tree().status == HttpStatusCode.OK }

            repo.commit(mapOf("new.sql" to "select 2"))
            eventually { client.tree().entries().map { it.first } == listOf("new.sql") }
        }
    }

    @Test
    fun `with the timer disabled, a failed first clone is retried`() {
        val repo = fixture()
        val source = source(repo.url) // no commits yet, so there's nothing to serve

        serving(source) {
            RefreshScheduler(source, interval = Duration.ZERO, retryInterval = 100.milliseconds)
                .also { cleanup += it }.start()

            repo.commit(mapOf("x.sql" to "select 1"))
            eventually { client.tree().status == HttpStatusCode.OK }
        }
    }

    @Test
    fun `a failed refresh keeps serving the previous contents`() {
        val repo = fixture()
        repo.commit(mapOf("x.sql" to "select 1"))

        serving(source(repo.url)) {
            client.refresh()
            repo.close() // the remote disappears

            val refresh = client.refresh()
            assertEquals(HttpStatusCode.ServiceUnavailable, refresh.status)
            assertEquals("refresh_failed", refresh.errorCode())
            assertEquals(listOf("x.sql"), client.tree().entries().map { it.first })
        }
    }

    @Test
    fun `serves the remote's default branch when no ref is configured`() {
        val repo = fixture(defaultBranch = "trunk")
        repo.commit(mapOf("trunk.sql" to ""), branch = "trunk")
        repo.commit(mapOf("other.sql" to ""), branch = "other")

        serving(source(repo.url)) {
            client.refresh()
            assertEquals(listOf("trunk.sql"), client.tree().entries().map { it.first })
        }
    }

    @Test
    fun `serves the configured branch`() {
        val repo = fixture()
        repo.commit(mapOf("main.sql" to ""))
        repo.commit(mapOf("dev.sql" to ""), branch = "dev")

        serving(source(repo.url, ref = "dev")) {
            client.refresh()
            assertEquals(listOf("dev.sql"), client.tree().entries().map { it.first })
        }
    }

    @Test
    fun `serves the configured tag`() {
        val repo = fixture()
        val first = repo.commit(mapOf("v1.sql" to ""))
        repo.commit(mapOf("v2.sql" to ""))
        repo.tag("v1", first)

        serving(source(repo.url, ref = "v1")) {
            client.refresh()
            assertEquals(listOf("v1.sql"), client.tree().entries().map { it.first })
        }
    }

    @Test
    fun `a missing ref fails the refresh`() {
        val repo = fixture()
        repo.commit(mapOf("x.sql" to ""))

        serving(source(repo.url, ref = "nope")) {
            val refresh = client.refresh()
            assertEquals(HttpStatusCode.ServiceUnavailable, refresh.status)
            assertEquals("refresh_failed", refresh.errorCode())
            assertEquals("not_ready", client.tree().errorCode())
        }
    }
}
