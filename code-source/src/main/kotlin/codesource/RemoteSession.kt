package codesource

import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.SshException
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyPair

/**
 * Authenticates the clones and fetches of one refresh with [credentials].
 * Secret files are read when the session is made, so a refresh picks up a
 * rotated secret. Throws [RefreshFailedException] if they can't be used.
 */
internal class RemoteSession(credentials: Credentials?, private val sshDir: Lazy<File>) : AutoCloseable {
    private val sshFactory: SshdSessionFactory?
    private val password: UsernamePasswordCredentialsProvider?

    init {
        when (credentials) {
            is Credentials.Ssh -> {
                val keys = credentials.keyFile?.let(::loadKey).orEmpty()
                sshFactory = sshSessionFactory(keys, credentials.knownHostsFile, sshDir.value)
                password = null
            }
            is Credentials.Http -> {
                sshFactory = null
                password = UsernamePasswordCredentialsProvider(
                    credentials.username,
                    credentials.passwordFile.readText().trimEnd('\r', '\n'),
                )
            }
            null -> {
                sshFactory = null
                password = null
            }
        }
    }

    fun <C : TransportCommand<C, *>> apply(command: C): C {
        sshFactory?.let { factory ->
            command.setTransportConfigCallback { transport -> (transport as? SshTransport)?.sshSessionFactory = factory }
        }
        password?.let { command.setCredentialsProvider(it) }
        return command
    }

    override fun close() {
        sshFactory?.close()
    }

    private class PassphraseRequired : IOException()

    companion object {
        /**
         * A directory of our own to act as home and `~/.ssh`, so nothing from
         * the user's real ones (keys, known_hosts, config) is used.
         */
        fun privateSshDir(): File = Files.createTempDirectory("code-source-ssh").toFile().also { dir ->
            // Without this JGit would ask about unknown or changed host keys,
            // and with nobody to ask it falls back to refusing. Say so outright.
            File(dir, "config").writeText("Host *\n  StrictHostKeyChecking yes\n")
        }

        /**
         * An operator-facing cause for a clone or fetch failure, if it's one
         * we recognise. JGit's own messages for these are terse, and some
         * include file paths.
         */
        fun describe(e: Throwable): String? {
            val causes = generateSequence(e) { it.cause }
            // JGit's HTTP transport has no exception types for these, but
            // builds the message from its own text constants.
            val http = causes.filterIsInstance<TransportException>().mapNotNull { it.message }.firstOrNull { message ->
                val text = JGitText.get()
                message.endsWith(text.notAuthorized) || message.endsWith(text.noCredentialsProvider)
            }
            if (http != null) {
                return "authentication failed: the server refused REPO_USERNAME and REPO_PASSWORD_FILE, or needs them and they aren't set"
            }
            val ssh = causes.filterIsInstance<SshException>().firstOrNull() ?: return null
            return when (ssh.disconnectCode) {
                SshConstants.SSH2_DISCONNECT_HOST_KEY_NOT_VERIFIABLE ->
                    "the server's host key isn't in known_hosts (REPO_SSH_KNOWN_HOSTS_FILE), or doesn't match it"
                SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE ->
                    "authentication failed: the server refused the key in REPO_SSH_KEY_FILE, or needs one and none is set"
                else -> null
            }
        }

        // Keys are loaded here rather than by JGit, which skips a key it can't
        // read and then reports only that it had none to try.
        private fun loadKey(file: File): List<KeyPair> {
            val keys = try {
                file.inputStream().use { input ->
                    SecurityUtils.loadKeyPairIdentities(
                        null,
                        NamedResource.ofName("REPO_SSH_KEY_FILE"),
                        input,
                        FilePasswordProvider { _, _, _ -> throw PassphraseRequired() },
                    )
                }
            } catch (e: PassphraseRequired) {
                throw RefreshFailedException("The key in REPO_SSH_KEY_FILE is passphrase-protected, which isn't supported")
            } catch (e: IOException) {
                // The parser's messages may quote the file, so they're left out.
                throw RefreshFailedException("The key in REPO_SSH_KEY_FILE can't be read (${e.javaClass.simpleName})")
            } catch (e: GeneralSecurityException) {
                throw RefreshFailedException("The key in REPO_SSH_KEY_FILE can't be used (${e.javaClass.simpleName})")
            }
            return keys?.toList()?.takeIf { it.isNotEmpty() }
                ?: throw RefreshFailedException("REPO_SSH_KEY_FILE doesn't hold a private key in a supported format")
        }

        private fun sshSessionFactory(keys: List<KeyPair>, knownHosts: File, dir: File): SshdSessionFactory =
            SshdSessionFactoryBuilder()
                .setHomeDirectory(dir)
                .setSshDirectory(dir)
                .setConfigFile { File(it, "config") }
                .setDefaultKeysProvider { keys }
                .setDefaultKnownHostsFiles { listOf(knownHosts.toPath()) }
                // No SSH agent.
                .setConnectorFactory(null)
                .build(null)
    }
}
