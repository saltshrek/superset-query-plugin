package codesource

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

// docs/features/005-authenticated-remotes.md: start-up configuration mistakes
// docs/features/007-file-outlines.md: SQL_DIALECT
class ConfigTest {
    private val dir: File = Files.createTempDirectory("config").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun secret(name: String) = File(dir, name).apply { writeText("secret") }.path

    private fun failure(env: Map<String, String>) = assertFailsWith<ConfigException> { Config.fromEnv(env) }.message!!

    @Test
    fun `an SSH remote takes a key and known_hosts from files`() {
        val key = secret("ssh-privatekey")
        val knownHosts = secret("known_hosts")

        val config = Config.fromEnv(
            mapOf(
                "REPO_URL" to "ssh://git@example.com/examples.git",
                "REPO_SSH_KEY_FILE" to key,
                "REPO_SSH_KNOWN_HOSTS_FILE" to knownHosts,
            )
        )

        assertEquals(Credentials.Ssh(File(key), File(knownHosts)), config.credentials)
    }

    @Test
    fun `scp-like URLs are SSH remotes`() {
        val config = Config.fromEnv(
            mapOf(
                "REPO_URL" to "git@example.com:team/examples.git",
                "REPO_SSH_KEY_FILE" to secret("key"),
                "REPO_SSH_KNOWN_HOSTS_FILE" to secret("known_hosts"),
            )
        )

        assertEquals(Credentials.Ssh::class, config.credentials!!::class)
    }

    @Test
    fun `an HTTPS remote takes a username and a password file`() {
        val password = secret("token")

        val config = Config.fromEnv(
            mapOf(
                "REPO_URL" to "https://example.com/examples.git",
                "REPO_USERNAME" to "reader",
                "REPO_PASSWORD_FILE" to password,
            )
        )

        assertEquals(Credentials.Http("reader", File(password)), config.credentials)
    }

    @Test
    fun `an unauthenticated remote needs no credentials`() {
        assertNull(Config.fromEnv(mapOf("REPO_URL" to "https://example.com/examples.git")).credentials)
        assertNull(Config.fromEnv(mapOf("REPO_URL" to "file:///srv/git/examples.git")).credentials)
    }

    @Test
    fun `a credential file that doesn't exist is refused`() {
        val missing = File(dir, "missing").path
        for (name in listOf("REPO_SSH_KEY_FILE", "REPO_SSH_KNOWN_HOSTS_FILE")) {
            val env = mapOf(
                "REPO_URL" to "ssh://git@example.com/examples.git",
                "REPO_SSH_KEY_FILE" to secret("key"),
                "REPO_SSH_KNOWN_HOSTS_FILE" to secret("known_hosts"),
            ) + (name to missing)
            assertContains(failure(env), name)
        }
        val env = mapOf(
            "REPO_URL" to "https://example.com/examples.git",
            "REPO_USERNAME" to "reader",
            "REPO_PASSWORD_FILE" to missing,
        )
        assertContains(failure(env), "REPO_PASSWORD_FILE")
    }

    @Test
    fun `a credential path that is a directory is refused`() {
        val env = mapOf(
            "REPO_URL" to "ssh://git@example.com/examples.git",
            "REPO_SSH_KEY_FILE" to dir.path,
            "REPO_SSH_KNOWN_HOSTS_FILE" to secret("known_hosts"),
        )

        assertContains(failure(env), "REPO_SSH_KEY_FILE")
    }

    @Test
    fun `an SSH remote without known_hosts is refused`() {
        for (url in listOf("ssh://git@example.com/examples.git", "git@example.com:examples.git")) {
            val env = mapOf("REPO_URL" to url, "REPO_SSH_KEY_FILE" to secret("key"))
            assertContains(failure(env), "REPO_SSH_KNOWN_HOSTS_FILE")
        }
    }

    @Test
    fun `SSH settings with an HTTPS remote are refused`() {
        for (name in listOf("REPO_SSH_KEY_FILE", "REPO_SSH_KNOWN_HOSTS_FILE")) {
            val env = mapOf("REPO_URL" to "https://example.com/examples.git", name to secret("file"))
            assertContains(failure(env), name)
        }
    }

    @Test
    fun `HTTPS settings with an SSH remote are refused`() {
        val ssh = mapOf(
            "REPO_URL" to "ssh://git@example.com/examples.git",
            "REPO_SSH_KEY_FILE" to secret("key"),
            "REPO_SSH_KNOWN_HOSTS_FILE" to secret("known_hosts"),
        )

        assertContains(failure(ssh + ("REPO_USERNAME" to "reader")), "REPO_USERNAME")
        assertContains(failure(ssh + ("REPO_PASSWORD_FILE" to secret("token"))), "REPO_PASSWORD_FILE")
    }

    @Test
    fun `a username needs a password file and the other way round`() {
        val https = mapOf("REPO_URL" to "https://example.com/examples.git")

        assertContains(failure(https + ("REPO_USERNAME" to "reader")), "REPO_PASSWORD_FILE")
        assertContains(failure(https + ("REPO_PASSWORD_FILE" to secret("token"))), "REPO_USERNAME")
    }

    @Test
    fun `a password in the URL is refused without repeating it`() {
        val message = failure(mapOf("REPO_URL" to "https://reader:hunter2@example.com/examples.git"))

        assertContains(message, "REPO_PASSWORD_FILE")
        assertFalse("hunter2" in message)
        assertFalse("example.com" in message)
    }

    @Test
    fun `the SQL dialect comes from SQL_DIALECT, and is sqlglot's generic one when unset`() {
        val url = "REPO_URL" to "file:///srv/git/examples.git"

        assertEquals("oracle", Config.fromEnv(mapOf(url, "SQL_DIALECT" to "oracle")).sqlDialect)
        assertNull(Config.fromEnv(mapOf(url)).sqlDialect)
    }
}
