package codesource

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.junit.http.AppServerBase
import org.eclipse.jgit.junit.http.SimpleHttpServer
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

// docs/features/005-authenticated-remotes.md: HTTP(S) remotes
class HttpRemoteTest {
    private val cleanup = mutableListOf<AutoCloseable>()
    private val dir: File = Files.createTempDirectory("http").toFile().also { d -> cleanup += AutoCloseable { d.deleteRecursively() } }
    private val logs = LogCapture().also { cleanup += it }

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    private val repo = FixtureRepo().also { cleanup += it }

    // The test server accepts exactly this user and password.
    private val username = AppServerBase.username
    private val password = AppServerBase.password

    /** An HTTP git server for [repo], behind basic auth. */
    private val url: String = run {
        val repository = FileRepositoryBuilder().setGitDir(repo.dir).build().also { cleanup += it }
        val server = SimpleHttpServer(repository)
        server.start()
        cleanup += AutoCloseable { server.stop() }
        server.uri.toString()
    }

    private fun passwordFile(text: String): File =
        File.createTempFile("password", "", dir).apply { writeText(text) }

    private fun source(credentials: Credentials?) =
        GitCodeSource(url, null, File(Files.createTempDirectory("clone").toFile(), "repo.git"), credentials)
            .also { cleanup += it }

    private fun serving(source: CodeSource, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(source) }
        block()
    }

    private suspend fun HttpResponse.message(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.rootNames(): List<String> =
        Json.parseToJsonElement(client.get("/api/v1/tree").bodyAsText()).jsonObject["entries"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

    /** Refreshes, expecting `refresh_failed`, and returns its message. */
    private suspend fun ApplicationTestBuilder.refreshFailure(): String {
        val response = client.post("/refresh")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        return response.message()
    }

    private fun assertNoSecret(secret: String, message: String) {
        for (text in listOf(message) + logs.messages) {
            assertEquals(false, secret in text, "secret in: $text")
        }
    }

    @Test
    fun `clones and refreshes over HTTP with a username and a password file`() {
        repo.commit(mapOf("first.sql" to "select 1"))

        // A trailing newline, as `echo` or an editor leaves, isn't part of the password.
        serving(source(Credentials.Http(username, passwordFile("$password\n")))) {
            assertEquals(HttpStatusCode.OK, client.post("/refresh").status)
            assertEquals(listOf("first.sql"), rootNames())

            repo.commit(mapOf("first.sql" to "select 1", "second.sql" to "select 2"))
            assertEquals(HttpStatusCode.OK, client.post("/refresh").status)
            assertEquals(listOf("first.sql", "second.sql"), rootNames())
        }
    }

    @Test
    fun `a rejected password is reported as an authentication failure`() {
        repo.commit(mapOf("first.sql" to "select 1"))
        val wrong = "not-the-password-0451"

        serving(source(Credentials.Http(username, passwordFile(wrong)))) {
            val message = refreshFailure()
            assertContains(message, "authentication failed", ignoreCase = true)
            assertNoSecret(wrong, message)
        }
    }

    @Test
    fun `no credentials for a remote that needs them is reported as an authentication failure`() {
        repo.commit(mapOf("first.sql" to "select 1"))

        serving(source(null)) {
            assertContains(refreshFailure(), "authentication failed", ignoreCase = true)
        }
    }

    @Test
    fun `a replaced password file takes effect at the next refresh`() {
        repo.commit(mapOf("first.sql" to "select 1"))
        val file = passwordFile("not-the-password")

        serving(source(Credentials.Http(username, file))) {
            refreshFailure()

            file.writeText(password)
            assertEquals(HttpStatusCode.OK, client.post("/refresh").status)
            assertEquals(listOf("first.sql"), rootNames())
            assertNoSecret(password, "")
        }
    }
}
