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
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.eclipse.jgit.junit.ssh.SshTestGitServer
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

// docs/features/005-authenticated-remotes.md: SSH remotes
class SshRemoteTest {
    private val cleanup = mutableListOf<AutoCloseable>()
    private val dir: File = Files.createTempDirectory("ssh").toFile().also { d -> cleanup += AutoCloseable { d.deleteRecursively() } }
    private val logs = LogCapture().also { cleanup += it }

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    private val repo = FixtureRepo().also { cleanup += it }
    private val userKey = newKey()
    private val hostKey = newKey()

    /** An SSH server for [repo] that accepts only [userKey] from user `git`. */
    private val port: Int = run {
        val repository = FileRepositoryBuilder().setGitDir(repo.dir).build().also { cleanup += it }
        val publicKeyFile = File(dir, "user.pub").apply { writeText(PublicKeyEntry.toString(userKey.public)) }
        val server = SshTestGitServer("git", publicKeyFile.toPath(), repository, hostKey)
        server.start().also { cleanup += AutoCloseable { server.stop() } }
    }

    private val url get() = "ssh://git@localhost:$port/examples.git"

    private fun newKey(): KeyPair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256)

    private fun keyFile(key: KeyPair, passphrase: String? = null): File =
        File.createTempFile("key", "", dir).apply { writeText(privateKeyText(key, passphrase)) }

    private fun privateKeyText(key: KeyPair, passphrase: String? = null): String {
        val options = passphrase?.let {
            OpenSSHKeyEncryptionContext().apply {
                setPassword(it)
                cipherName = "AES"
                cipherType = "256"
            }
        }
        return ByteArrayOutputStream().also { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "", options, it) }
            .toString(Charsets.US_ASCII)
    }

    private fun knownHosts(vararg lines: String): File =
        File.createTempFile("known_hosts", "", dir).apply { writeText(lines.joinToString("\n", postfix = "\n")) }

    private fun knownHostsLine(port: Int = this.port, key: KeyPair = hostKey) =
        "[localhost]:$port ${PublicKeyEntry.toString(key.public)}"

    private fun source(credentials: Credentials) =
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

    private fun assertNoSecrets(message: String, vararg keys: KeyPair) {
        for (key in keys) {
            val body = privateKeyText(key).lines().filter { it.isNotEmpty() && !it.startsWith("-----") }
            for (line in body) {
                assertNotContained(line, message)
                logs.messages.forEach { assertNotContained(line, it) }
            }
        }
    }

    private fun assertNotContained(secret: String, text: String) =
        assertEquals(false, secret in text, "secret material in: $text")

    @Test
    fun `clones and refreshes over SSH with a key and known_hosts from files`() {
        repo.commit(mapOf("first.sql" to "select 1"))

        serving(source(Credentials.Ssh(keyFile(userKey), knownHosts(knownHostsLine())))) {
            assertEquals(HttpStatusCode.OK, client.post("/refresh").status)
            assertEquals(listOf("first.sql"), rootNames())

            repo.commit(mapOf("first.sql" to "select 1", "second.sql" to "select 2"))
            assertEquals(HttpStatusCode.OK, client.post("/refresh").status)
            assertEquals(listOf("first.sql", "second.sql"), rootNames())
        }
    }

    @Test
    fun `a host that isn't in known_hosts is refused`() {
        repo.commit(mapOf("first.sql" to "select 1"))
        val otherHost = newKey()

        serving(source(Credentials.Ssh(keyFile(userKey), knownHosts(knownHostsLine(port = port + 1, key = otherHost))))) {
            val message = refreshFailure()
            assertContains(message, "host key", ignoreCase = true)
            assertContains(message, "known_hosts")
            assertNoSecrets(message, userKey)
        }
    }

    @Test
    fun `a host whose key doesn't match known_hosts is refused`() {
        repo.commit(mapOf("first.sql" to "select 1"))

        serving(source(Credentials.Ssh(keyFile(userKey), knownHosts(knownHostsLine(key = newKey()))))) {
            val message = refreshFailure()
            assertContains(message, "host key", ignoreCase = true)
            assertContains(message, "known_hosts")
            assertNoSecrets(message, userKey)
        }
    }

    @Test
    fun `a rejected key is reported as an authentication failure`() {
        repo.commit(mapOf("first.sql" to "select 1"))
        val wrongKey = newKey()

        serving(source(Credentials.Ssh(keyFile(wrongKey), knownHosts(knownHostsLine())))) {
            val message = refreshFailure()
            assertContains(message, "authentication failed", ignoreCase = true)
            assertNoSecrets(message, wrongKey)
        }
    }

    @Test
    fun `no key for a remote that needs one is reported as an authentication failure`() {
        repo.commit(mapOf("first.sql" to "select 1"))

        serving(source(Credentials.Ssh(null, knownHosts(knownHostsLine())))) {
            assertContains(refreshFailure(), "authentication failed", ignoreCase = true)
        }
    }

    @Test
    fun `a passphrase-protected key is reported as such`() {
        repo.commit(mapOf("first.sql" to "select 1"))

        serving(source(Credentials.Ssh(keyFile(userKey, passphrase = "hunter2"), knownHosts(knownHostsLine())))) {
            val message = refreshFailure()
            assertContains(message, "passphrase", ignoreCase = true)
            assertNotContained("hunter2", message)
            assertNoSecrets(message, userKey)
        }
    }

    @Test
    fun `a replaced key file takes effect at the next refresh`() {
        repo.commit(mapOf("first.sql" to "select 1"))
        val key = keyFile(newKey())

        serving(source(Credentials.Ssh(key, knownHosts(knownHostsLine())))) {
            refreshFailure()

            key.writeText(privateKeyText(userKey))
            assertEquals(HttpStatusCode.OK, client.post("/refresh").status)
            assertEquals(listOf("first.sql"), rootNames())
        }
    }
}
