package codesource

import org.eclipse.jgit.transport.URIish
import java.io.File
import java.net.URISyntaxException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Credentials for the remote. Secrets are held as files, read when used. */
sealed interface Credentials {
    /** [keyFile] is null when the remote needs no key, but the host must still be known. */
    data class Ssh(val keyFile: File?, val knownHostsFile: File) : Credentials

    data class Http(val username: String, val passwordFile: File) : Credentials
}

data class Config(
    val url: String,
    val ref: String?,
    val refreshInterval: Duration,
    val dataDir: File?,
    val port: Int,
    val credentials: Credentials?,
    /** The SQL dialect sqlglot parses with; null for its generic one. */
    val sqlDialect: String? = null,
) {
    companion object {
        /**
         * Reads the configuration from [env]. Throws [ConfigException] naming
         * the variable at fault for any mistake that can be seen without
         * contacting the remote.
         */
        fun fromEnv(env: Map<String, String>): Config {
            fun value(name: String) = env[name]?.takeIf { it.isNotBlank() }
            fun file(name: String) = value(name)?.let { path ->
                File(path).takeIf { it.isFile && it.canRead() }
                    ?: throw ConfigException("$name: '$path' isn't a readable file")
            }

            val url = value("REPO_URL") ?: throw ConfigException("REPO_URL must be set to the repository to serve")
            val uri = try {
                URIish(url)
            } catch (e: URISyntaxException) {
                throw ConfigException("REPO_URL isn't a git URL")
            }
            if (uri.pass != null) {
                throw ConfigException("REPO_URL contains a password; put it in a file named by REPO_PASSWORD_FILE instead")
            }

            val sshKey = file("REPO_SSH_KEY_FILE")
            val knownHosts = file("REPO_SSH_KNOWN_HOSTS_FILE")
            val username = value("REPO_USERNAME")
            val passwordFile = file("REPO_PASSWORD_FILE")

            val credentials = when (uri.transport()) {
                Transport.SSH -> {
                    if (username != null) throw ConfigException("REPO_USERNAME is for HTTP(S) remotes, but REPO_URL is SSH")
                    if (passwordFile != null) throw ConfigException("REPO_PASSWORD_FILE is for HTTP(S) remotes, but REPO_URL is SSH")
                    knownHosts ?: throw ConfigException("REPO_SSH_KNOWN_HOSTS_FILE must be set for an SSH remote")
                    Credentials.Ssh(sshKey, knownHosts)
                }
                Transport.HTTP -> {
                    if (sshKey != null) throw ConfigException("REPO_SSH_KEY_FILE is for SSH remotes, but REPO_URL is HTTP(S)")
                    if (knownHosts != null) throw ConfigException("REPO_SSH_KNOWN_HOSTS_FILE is for SSH remotes, but REPO_URL is HTTP(S)")
                    when {
                        username != null && passwordFile != null -> Credentials.Http(username, passwordFile)
                        username != null -> throw ConfigException("REPO_USERNAME is set, so REPO_PASSWORD_FILE must be too")
                        passwordFile != null -> throw ConfigException("REPO_PASSWORD_FILE is set, so REPO_USERNAME must be too")
                        else -> null
                    }
                }
                Transport.OTHER -> {
                    listOf("REPO_SSH_KEY_FILE", "REPO_SSH_KNOWN_HOSTS_FILE", "REPO_USERNAME", "REPO_PASSWORD_FILE")
                        .firstOrNull { value(it) != null }
                        ?.let { throw ConfigException("$it is set, but REPO_URL is neither SSH nor HTTP(S)") }
                    null
                }
            }

            return Config(
                url = url,
                ref = value("REPO_REF"),
                refreshInterval = (value("REFRESH_INTERVAL_SECONDS")?.toLong() ?: 300).seconds,
                dataDir = value("DATA_DIR")?.let(::File),
                port = value("PORT")?.toInt() ?: 8080,
                credentials = credentials,
                sqlDialect = value("SQL_DIALECT"),
            )
        }

        private enum class Transport { SSH, HTTP, OTHER }

        private fun URIish.transport() = when (scheme?.lowercase()) {
            "ssh", "ssh+git", "git+ssh" -> Transport.SSH
            "http", "https" -> Transport.HTTP
            // scp-like syntax, user@host:path
            null -> if (host != null) Transport.SSH else Transport.OTHER
            else -> Transport.OTHER
        }
    }
}

class ConfigException(message: String) : Exception(message)
