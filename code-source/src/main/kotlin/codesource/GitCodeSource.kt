package codesource

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant

/**
 * Serves the tree of [ref] (or the remote's default branch) from a bare clone
 * of [url] kept in [cloneDir].
 *
 * Nothing happens until the first [refresh], which clones. Later refreshes
 * fetch. Listings and reads use git objects directly: there's no checkout, and
 * no filesystem path is built from request input.
 *
 * Each refresh also outlines every file, SQL ones with [sqlAnalyzer], reads
 * folders' descriptions from their READMEs, and works out when each file last
 * changed.
 */
class GitCodeSource(
    private val url: String,
    private val ref: String?,
    private val cloneDir: File,
    private val credentials: Credentials? = null,
    private val sqlAnalyzer: SqlAnalyzer = SqlAnalyzer.installed(dialect = null),
) : CodeSource, AutoCloseable {
    private val log = LoggerFactory.getLogger(GitCodeSource::class.java)

    private val sshDir = lazy(RemoteSession::privateSshDir)

    private class Snapshot(
        val repository: Repository,
        val commitId: ObjectId,
        val treeId: ObjectId,
        val index: PathIndex,
        val outlines: Map<RepoPath, Outline>,
        val folderDescriptions: Map<RepoPath, String>,
        val modified: Map<RepoPath, Instant>,
    ) {
        fun outline(path: RepoPath) = outlines.outlineOf(path)
    }

    // Replaced as a whole after each successful refresh, so readers never see
    // a half-updated state, and the tree and its index and outlines always match.
    @Volatile
    private var snapshot: Snapshot? = null

    private val refreshLock = Any()

    val isReady: Boolean get() = snapshot != null

    override fun list(path: RepoPath): ListResult {
        val current = snapshot ?: return ListResult.NotReady
        val repository = current.repository
        val dirTreeId = when (val found = lookup(current, path)) {
            is Found.Directory -> found.id
            is Found.File -> return ListResult.NotADirectory
            null -> return ListResult.NotFound
        }

        val entries = mutableListOf<Entry>()
        TreeWalk(repository).use { walk ->
            walk.addTree(dirTreeId)
            walk.isRecursive = false
            while (walk.next()) {
                val type = when (walk.getFileMode(0)) {
                    FileMode.TREE -> EntryType.DIRECTORY
                    FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE -> EntryType.FILE
                    else -> continue
                }
                val child = path.child(walk.nameString)
                entries += when (type) {
                    EntryType.FILE -> Entry(
                        walk.nameString, child, type,
                        outline = current.outline(child), modified = current.modified[child],
                    )
                    EntryType.DIRECTORY -> Entry(walk.nameString, child, type, description = current.folderDescriptions[child])
                }
            }
        }
        // Git's own order puts "a.sql" before "a" (trees sort as "a/"), and
        // Kotlin's String order is by UTF-16 unit. UTF-8 byte order is
        // code-point order, as the API promises.
        entries.sortWith { a, b -> java.util.Arrays.compareUnsigned(a.name.toByteArray(), b.name.toByteArray()) }
        return ListResult.Directory(path, current.folderDescriptions[path], entries)
    }

    override fun read(path: RepoPath): ReadResult {
        val current = snapshot ?: return ReadResult.NotReady
        val blobId = when (val found = lookup(current, path)) {
            is Found.File -> found.id
            is Found.Directory -> return ReadResult.NotAFile
            null -> return ReadResult.NotFound
        }
        return when (val text = text(current.repository, blobId)) {
            is Text.Readable -> ReadResult.File(path, text.content, current.outline(path), current.modified[path])
            Text.TooLarge -> ReadResult.TooLarge
            Text.NotText -> ReadResult.NotText
        }
    }

    private sealed interface Text {
        data class Readable(val content: String) : Text
        data object TooLarge : Text
        data object NotText : Text
    }

    /** The blob [id] as text, as the API serves it: UTF-8 without a BOM. */
    private fun text(repository: Repository, id: ObjectId): Text {
        val loader = repository.open(id)
        if (loader.size > ReadResult.MAX_BYTES) return Text.TooLarge
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(loader.bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            return Text.NotText
        }
        return Text.Readable(text.removePrefix("\uFEFF"))
    }

    override fun search(text: String, filters: SearchFilters, limit: Int): SearchResult {
        val current = snapshot ?: return SearchResult.NotReady
        val found = current.index.search(text, filters, limit)
        return found.copy(hits = found.hits.map { it.copy(outline = current.outline(it.path), modified = current.modified[it.path]) })
    }

    override fun suggest(text: String, fields: Set<OutlineField>, extensions: Set<String>, limit: Int): SuggestResult =
        snapshot?.index?.suggest(text, fields, extensions, limit)?.let(SuggestResult::Suggestions) ?: SuggestResult.NotReady

    private sealed interface Found {
        val id: ObjectId

        data class Directory(override val id: ObjectId) : Found
        data class File(override val id: ObjectId) : Found
    }

    /** The directory or file at [path], or null if there's none. */
    private fun lookup(snapshot: Snapshot, path: RepoPath): Found? {
        if (path.isRoot) return Found.Directory(snapshot.treeId)
        val walk = TreeWalk.forPath(snapshot.repository, path.value, snapshot.treeId) ?: return null
        return walk.use {
            when (it.getFileMode(0)) {
                FileMode.TREE -> Found.Directory(it.getObjectId(0))
                FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE -> Found.File(it.getObjectId(0))
                // Symlinks and submodules aren't listed, so they don't exist here either.
                else -> null
            }
        }
    }

    override fun refresh() = synchronized(refreshLock) {
        val current = snapshot
        try {
            snapshot = RemoteSession(credentials, sshDir).use { session ->
                if (current == null) cloneAndResolve(session) else fetchAndResolve(session, current)
            }
        } catch (e: RefreshFailedException) {
            throw e
        } catch (e: Exception) {
            val message = RemoteSession.describe(e)?.let { "$url: $it" } ?: e.message ?: e.javaClass.simpleName
            throw RefreshFailedException(message, e)
        }
        if (current == null) log.info("Cloned {}", url) else log.debug("Fetched {}", url)
    }

    private fun cloneAndResolve(session: RemoteSession): Snapshot {
        // A failed earlier attempt may have left a partial clone.
        cloneDir.deleteRecursively()
        val repository = session.apply(Git.cloneRepository())
            .setURI(url)
            .setDirectory(cloneDir)
            .setBare(true)
            .call()
            .repository
        try {
            fetch(session, repository) // also picks up tags, which a clone of branches leaves out
            return snapshotOf(repository, resolveCommit(repository), previous = null)
        } catch (e: Exception) {
            repository.close()
            throw e
        }
    }

    private fun fetchAndResolve(session: RemoteSession, current: Snapshot): Snapshot {
        fetch(session, current.repository)
        return snapshotOf(current.repository, resolveCommit(current.repository), current)
    }

    /**
     * Indexes and outlines [commitId]'s tree, unless it's the tree [previous]
     * already did, and dates its files, unless [previous] is the same commit.
     */
    private fun snapshotOf(repository: Repository, commitId: ObjectId, previous: Snapshot?): Snapshot {
        if (previous?.commitId == commitId) return previous
        val treeId = RevWalk(repository).use { it.parseCommit(commitId).tree.id }
        val files = files(repository, treeId).toList()
        val modified = modified(repository, commitId, files)
        previous?.takeIf { it.treeId == treeId }?.let {
            return Snapshot(repository, commitId, treeId, it.index, it.outlines, it.folderDescriptions, modified)
        }
        val outlines = outlines(repository, files)
        return Snapshot(
            repository, commitId, treeId, PathIndex.build(files.asSequence().map { it.first }, outlines::outlineOf),
            outlines, folderDescriptions(repository, files), modified,
        )
    }

    /** When each of [files] last changed. A failure leaves dates out, never fails the refresh. */
    private fun modified(repository: Repository, commitId: ObjectId, files: List<Pair<RepoPath, ObjectId>>): Map<RepoPath, Instant> {
        val paths = files.mapTo(HashSet()) { it.first }
        val started = System.nanoTime()
        val found = try {
            lastModified(repository, commitId, paths)
        } catch (e: Exception) {
            log.warn("Couldn't work out when files last changed", e)
            return emptyMap()
        }
        if (found.size < paths.size) {
            log.warn("History doesn't say when {} of {} files last changed", paths.size - found.size, paths.size)
        }
        log.debug("Dated {} files in {} ms", found.size, (System.nanoTime() - started) / 1_000_000)
        return found
    }

    /** Descriptions of the folders in [files] that have a README giving one. */
    private fun folderDescriptions(repository: Repository, files: List<Pair<RepoPath, ObjectId>>): Map<RepoPath, String> =
        files
            .filter { (path, _) -> Readme.isReadme(path.value.substringAfterLast('/')) }
            .mapNotNull { (path, id) ->
                val text = text(repository, id) as? Text.Readable ?: return@mapNotNull null
                val folder = RepoPath.parse(path.value.substringBeforeLast('/', ""))!!
                Readme.description(text.content)?.let { folder to it }
            }
            // Should a folder have README.md and readme.md, the first in path order wins.
            .sortedBy { it.first.value }
            .distinctBy { it.first }
            .toMap()

    /** Outlines of the SQL files in [files]. Other files' outlines need no analysis. */
    private fun outlines(repository: Repository, files: List<Pair<RepoPath, ObjectId>>): Map<RepoPath, Outline> {
        val sql = files
            .filter { (path, _) -> Outline.languageOf(path) == Outline.SQL }
            .mapNotNull { (path, id) -> (text(repository, id) as? Text.Readable)?.let { path to it.content } }
            .toMap()
        return sqlAnalyzer.analyze(sql)
    }

    /** Every file in the tree, as listings would show them: no symlinks or submodules. */
    private fun files(repository: Repository, treeId: ObjectId): Sequence<Pair<RepoPath, ObjectId>> = sequence {
        TreeWalk(repository).use { walk ->
            walk.addTree(treeId)
            walk.isRecursive = true
            while (walk.next()) {
                when (walk.getFileMode(0)) {
                    FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE ->
                        RepoPath.parse(walk.pathString)?.let { yield(it to walk.getObjectId(0)) }
                    else -> {}
                }
            }
        }
    }

    private fun fetch(session: RemoteSession, repository: Repository) {
        session.apply(Git.wrap(repository).fetch())
            .setRemote(url)
            .setRefSpecs(RefSpec("+refs/heads/*:refs/heads/*"), RefSpec("+refs/tags/*:refs/tags/*"))
            .setRemoveDeletedRefs(true)
            .call()
    }

    private fun resolveCommit(repository: Repository): ObjectId {
        // A bare clone's HEAD follows the remote's default branch.
        val name = ref ?: "HEAD"
        return repository.resolve("$name^{commit}")
            ?: throw RefreshFailedException("Ref '$name' not found in $url")
    }

    override fun close() {
        snapshot?.repository?.close()
        if (sshDir.isInitialized()) sshDir.value.deleteRecursively()
    }
}

/** The outline of the file at [path]: its analysis, or what any file in its language gets without one. */
private fun Map<RepoPath, Outline>.outlineOf(path: RepoPath) = this[path] ?: Outline.unanalysed(Outline.languageOf(path))
