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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// docs/api.md: GET /api/v1/file
// docs/features/004-use-a-file.md
class FileTest {
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

    private suspend fun HttpClient.file(path: String? = null): HttpResponse =
        get("/api/v1/file") { if (path != null) parameter("path", path) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.content(): String = json()["content"]!!.jsonPrimitive.content

    private suspend fun HttpResponse.errorCode(): String =
        json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private suspend fun HttpClient.refresh(): HttpResponse = post("/refresh")

    @Test
    fun `returns a file's path and contents`() {
        val repo = fixture()
        repo.commit(mapOf("orders/top_products.sql" to "SELECT *\nFROM products;\n"))

        serving(repo) {
            client.refresh()

            val response = client.file("orders/top_products.sql")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("orders/top_products.sql", response.json()["path"]!!.jsonPrimitive.content)
            assertEquals("SELECT *\nFROM products;\n", response.content())
        }
    }

    @Test
    fun `contents are unchanged, except that a BOM is dropped`() {
        val repo = fixture()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        repo.commit(
            mapOf(
                "crlf.sql" to "select 1\r\nfrom t\r\n",
                "no_newline.sql" to "select 'naïve ☃ 😀'",
                "bom.sql" to FixtureRepo.Bytes(bom + "select 1\n".toByteArray()),
                "empty.sql" to "",
                "run.sh" to FixtureRepo.Text("#!/bin/sh\n", executable = true),
            )
        )

        serving(repo) {
            client.refresh()

            assertEquals("select 1\r\nfrom t\r\n", client.file("crlf.sql").content())
            assertEquals("select 'naïve ☃ 😀'", client.file("no_newline.sql").content())
            assertEquals("select 1\n", client.file("bom.sql").content())
            assertEquals("", client.file("empty.sql").content())
            assertEquals("#!/bin/sh\n", client.file("run.sh").content())
        }
    }

    @Test
    fun `directories, including the root, are not files`() {
        val repo = fixture()
        repo.commit(mapOf("orders/top.sql" to "select 1"))

        serving(repo) {
            client.refresh()

            listOf(null, "", "orders").forEach {
                val response = client.file(it)
                assertEquals(HttpStatusCode.BadRequest, response.status, "path '$it'")
                assertEquals("not_a_file", response.errorCode(), "path '$it'")
            }
        }
    }

    @Test
    fun `missing paths, symlinks and submodules are not found`() {
        val repo = fixture()
        repo.commit(
            mapOf(
                "top.sql" to "select 1",
                "link.sql" to FixtureRepo.Symlink("top.sql"),
                "vendored" to FixtureRepo.Submodule,
            )
        )

        serving(repo) {
            client.refresh()

            listOf("missing.sql", "orders/top.sql", "top.sql/x", "link.sql", "vendored").forEach {
                val response = client.file(it)
                assertEquals(HttpStatusCode.NotFound, response.status, "path '$it'")
                assertEquals("not_found", response.errorCode(), "path '$it'")
            }
        }
    }

    @Test
    fun `non-canonical paths are rejected`() {
        val repo = fixture()
        repo.commit(mapOf("orders/top.sql" to "select 1"))

        serving(repo) {
            client.refresh()

            listOf("/orders/top.sql", "orders/top.sql/", "orders//top.sql", "./orders/top.sql", "orders/../orders/top.sql", "../top.sql")
                .forEach {
                    val response = client.file(it)
                    assertEquals(HttpStatusCode.BadRequest, response.status, "path '$it'")
                    assertEquals("invalid_path", response.errorCode(), "path '$it'")
                }
        }
    }

    @Test
    fun `files over 1 MiB are too large`() {
        val repo = fixture()
        val mib = 1024 * 1024
        repo.commit(mapOf("max.sql" to "x".repeat(mib), "over.sql" to "x".repeat(mib + 1)))

        serving(repo) {
            client.refresh()

            assertEquals(mib, client.file("max.sql").content().length)

            val response = client.file("over.sql")
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("too_large", response.errorCode())
        }
    }

    @Test
    fun `files that aren't UTF-8 are not text`() {
        val repo = fixture()
        repo.commit(
            mapOf(
                "latin1.sql" to FixtureRepo.Bytes("select 'café'".toByteArray(Charsets.ISO_8859_1)),
                "image.png" to FixtureRepo.Bytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
            )
        )

        serving(repo) {
            client.refresh()

            listOf("latin1.sql", "image.png").forEach {
                val response = client.file(it)
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "path '$it'")
                assertEquals("not_text", response.errorCode(), "path '$it'")
            }
        }
    }

    @Test
    fun `not ready until the first successful refresh`() {
        val repo = fixture()
        repo.commit(mapOf("x.sql" to "select 1"))

        serving(repo) {
            val response = client.file("x.sql")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("not_ready", response.errorCode())

            client.refresh()
            assertEquals(HttpStatusCode.OK, client.file("x.sql").status)
        }
    }

    @Test
    fun `refresh picks up new contents`() {
        val repo = fixture()
        repo.commit(mapOf("x.sql" to "select 1"))

        serving(repo) {
            client.refresh()
            assertEquals("select 1", client.file("x.sql").content())

            repo.commit(mapOf("x.sql" to "select 2"))
            assertEquals("select 1", client.file("x.sql").content(), "unchanged before refresh")

            client.refresh()
            assertEquals("select 2", client.file("x.sql").content())
        }
    }
}
