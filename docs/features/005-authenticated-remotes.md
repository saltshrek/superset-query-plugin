# 005: Authenticated remotes

**Status:** done

## Purpose

Let code-source clone and fetch from repositories that need credentials,
so it can serve real repositories in production (k8s), not just
unauthenticated ones. SSH is preferred, since other git-based systems in
the target environment already use it. HTTPS should also work.

## Behaviour

- An operator points code-source at an SSH or HTTPS remote (`REPO_URL`, as
  in 003) and names the files holding the credentials in environment
  variables. code-source then clones and fetches as it does for
  unauthenticated remotes (003).
- Credentials are only ever read from files, never from the environment
  itself, so secrets stay out of `env`, process listings and crash dumps.
  Any mount path and any file names work: the variables hold the paths.
- Configuration:
  - `REPO_SSH_KEY_FILE`: private key for an SSH remote. Unencrypted
    (OpenSSH or PEM format, as k8s `kubernetes.io/ssh-auth` secrets hold).
  - `REPO_SSH_KNOWN_HOSTS_FILE`: known_hosts for an SSH remote. Required
    whenever the remote is SSH.
  - `REPO_USERNAME`: username for an HTTPS remote. Not secret, so a plain
    value.
  - `REPO_PASSWORD_FILE`: password or token for an HTTPS remote.
- SSH verifies the server's host key against the supplied known_hosts and
  never connects to a host that isn't in it, or whose key doesn't match.
  It never adds entries to known_hosts.
- code-source ignores the user's `~/.ssh` (keys, known_hosts, config) and
  any SSH agent. Only the configured files are used, so behaviour doesn't
  depend on what happens to be in the container image.
- Credential files are read at each clone or fetch, so a rotated k8s
  secret (which k8s updates in place in the mounted volume) takes effect
  at the next refresh, with no restart.
- Mistakes in configuration that can be detected at start-up stop the
  service at start-up, with a message naming the variable, like a missing
  `REPO_URL` does today (003):
  - a `_FILE` variable naming a file that doesn't exist or can't be read
  - an SSH remote without `REPO_SSH_KNOWN_HOSTS_FILE`
  - SSH settings with an HTTPS remote, or HTTPS settings with an SSH
    remote
  - `REPO_USERNAME` without `REPO_PASSWORD_FILE`, or the other way round
  - a password embedded in `REPO_URL` (`https://user:secret@host/...`).
    The message points to `REPO_PASSWORD_FILE` and doesn't repeat the URL.
- Failures talking to the remote show up as they do today (003): in the
  logs, and as `refresh_failed` from `POST /refresh`. The message names
  the cause in words an operator can act on:
  - authentication failed (key or password rejected, or none supplied but
    the remote needs one)
  - the host key is unknown, or doesn't match known_hosts
  - the private key can't be used (unreadable, passphrase-protected,
    unsupported format)
- No log line or error message contains secret material: no key or
  password content, and no password from a URL.
- `not_ready` from the repository endpoints is unchanged. It tells API
  clients the repository isn't loaded, not why. The cause is in the logs
  and in `POST /refresh`, which operators have.

## Acceptance criteria

- [x] code-source clones and refreshes over SSH with a private key and
      known_hosts supplied as files
- [x] code-source clones and refreshes over HTTP(S) with a username and
      a password or token supplied as a file
- [x] An SSH remote whose host key isn't in known_hosts, or doesn't match
      it, is refused, with an error saying so
- [x] A rejected key, a rejected password, and credentials the remote
      needs but weren't given each give a `refresh_failed` message naming
      the cause
- [x] A passphrase-protected key gives a message saying so
- [x] Replacing a credential file's contents takes effect at the next
      refresh without a restart
- [x] Each start-up configuration mistake listed under Behaviour stops
      the service with a message naming the variable
- [x] No message or log line from any of the above contains the key,
      password, or token

## Out of scope

- Credentials for more than one remote (see the one-tree decision in
  `docs/decisions.md`)
- Passphrase-protected keys. Keys from k8s secrets are normally
  unencrypted; supporting a passphrase would mean another secret file for
  no gain.
- SSH agents, `~/.ssh/config`, proxy/jump hosts, and anything else from
  OpenSSH configuration
- Git LFS
- An authenticated remote in the dev Compose stack. The tests cover it
  with real servers; one can be added later if trying it by hand proves
  useful.

## Components and API changes

- code-source: configuration parsing and transport setup behind the
  repository-access interface (`GitCodeSource`). New dependencies
  `org.eclipse.jgit.ssh.apache` and Bouncy Castle (for Ed25519).
- code-source/AGENTS.md: the new variables.
- API: none. Only the content of `refresh_failed` messages changes, which
  `docs/api.md` already leaves to `message`.

## Testing

Per AGENTS.md, no fakes for git. JGit's own in-process test servers, all
published at our JGit version (7.8.0.202609011348-r), serve fixture repos
with real authentication:

- SSH: `SshTestGitServer` from `org.eclipse.jgit.junit.ssh`, a real
  Apache MINA sshd serving git. Tests generate a host key and a user key
  per run and write known_hosts and key files to a temp dir.
- HTTP: `AppServer` from `org.eclipse.jgit.junit.http` (Jetty) with
  `GitServlet` from `org.eclipse.jgit.http.server`, behind basic auth.
  Plain HTTP, not TLS: the credentials path is ours, TLS is JGit's and the
  JDK's.

Tests go through the HTTP API as 003's do: build the service over the
remote, call `POST /refresh` and `GET /api/v1/tree`, and check the status
and message. Every failure test also asserts that the key, password or
token doesn't appear in the response or the captured log output.

Start-up configuration checks are isolated logic (environment in,
configuration or an error naming the variable out), so they get a narrow
test that calls the parsing function with a map instead of the real
environment. No mocking needed.

## Decisions

- **JGit with Apache MINA sshd, not the git CLI.** JGit's
  `SshdSessionFactoryBuilder` takes explicit identity files, known_hosts
  files and a home/ssh directory, so mounted secrets with any file names
  work without an ssh `config`, and `~/.ssh` can be ignored entirely.
  Shelling out to `git` for clone and fetch was rejected: it adds a
  runtime dependency on the binary, process and timeout handling, and
  output parsing for error messages. It would also split git access
  between two mechanisms, and give up the in-process test servers.
- **Paths in variables, not a fixed mount at `~/.ssh`.** Mounting the
  secret at `~/.ssh` and relying on JGit's defaults only works if the key
  file is named like `id_ed25519`, which k8s's `ssh-privatekey` isn't. It
  would also pick up anything else in that directory.
- **`_FILE` only for secrets.** Taking `REPO_PASSWORD` directly as well
  was rejected: two ways to do it, and one of them puts the secret in the
  environment.
- **Strict host-key checking with no opt-out.** No
  `StrictHostKeyChecking=no` equivalent. An operator can get a host's key
  with `ssh-keyscan` and check it.
- **Bouncy Castle at runtime, for Ed25519.** JGit's SSH module (Apache
  MINA sshd 2.19) can't read or use Ed25519 keys on its own, and new keys
  are usually Ed25519. sshd supports it through either Bouncy Castle or
  `net.i2p.crypto:eddsa`. Bouncy Castle was chosen because it's
  maintained (eddsa hasn't had a release since 2019) and JGit's own build
  uses it, at the same version (1.85).
- **code-source loads the key, not JGit.** JGit skips a key it can't
  read, such as a passphrase-protected one, and then reports only "no
  keys to try". Loading the key ourselves gives a precise message. The
  loaded key is handed to JGit (`setDefaultKeysProvider`), so its path
  also stays out of JGit's messages.

## Implementation notes

- `Config.fromEnv` takes all configuration from a map, so `main` passes
  `System.getenv()` and `ConfigTest` passes a plain map. It goes one step
  past the Behaviour list: credential settings with a remote that's
  neither SSH nor HTTP(S) (e.g. `file://`) are refused too.
- `RemoteSession` is made per refresh, reads the secret files, and
  applies them to that refresh's clone and fetch. A refresh that runs
  after a secret file is replaced therefore uses the new contents.
- SSH: JGit's home, `~/.ssh` and config file all point at a private temp
  directory per `GitCodeSource`. The only thing in it is an ssh `config`
  with `StrictHostKeyChecking yes`. Without that, JGit's default is
  "ask", which falls back to refusing only because no credentials
  provider is set for SSH. No SSH agent (`setConnectorFactory(null)`).
- Error messages: `RemoteSession.describe` recognises SSH failures by
  sshd's disconnect code (host key not verifiable, no more auth
  methods). HTTP auth failures have no exception type or code, so they're
  recognised by JGit's own text constants (`JGitText.notAuthorized`,
  `noCredentialsProvider`), not by literal strings. Other failures keep
  JGit's message, as before.
- Key-parsing failures report only the exception's class name, because
  parser messages may quote the file.
- Tests: `SshRemoteTest` runs JGit's `SshTestGitServer` (a real MINA
  sshd) with Ed25519 keys generated per run and written in OpenSSH
  format, as `ssh-keygen` writes them. `HttpRemoteTest` runs JGit's
  `SimpleHttpServer` (Jetty, basic auth, fixed test user). Both serve a
  `FixtureRepo`. The JGit test servers call JUnit 4's `Assert`, so
  `junit:junit` is a test runtime dependency. Our tests still run on
  JUnit 5.
- `LogCapture` attaches to the root logger at its configured level (INFO,
  as in production), so the no-secrets checks see what an operator's logs
  would show, not debug output.
- Not done: SSH certificates, and the dev Compose stack has no
  authenticated remote (Out of scope).
