package codesource

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset

/**
 * A real bare git repository on local disk, to be cloned via [url].
 * Commits are written straight into the object store, so tests can build
 * trees with any entry types (symlinks, submodules) without a working copy.
 */
class FixtureRepo(defaultBranch: String = "main") : AutoCloseable {
    val dir: File = Files.createTempDirectory("fixture").toFile()
    val url: String get() = dir.toURI().toString()

    private val repository = Git.init().setBare(true).setDirectory(dir).setInitialBranch(defaultBranch).call().repository

    sealed interface Content
    data class Text(val text: String, val executable: Boolean = false) : Content
    class Bytes(val bytes: ByteArray) : Content
    data class Symlink(val target: String) : Content
    data object Submodule : Content

    /**
     * Commits exactly [files] (path to content) as the new tip of [branch],
     * committed at [committed] and authored at [authored]. With [merging], the
     * commit is a merge, with [merging] as its second parent. A [branch] that
     * doesn't exist yet starts [from] the given commit, or with no parent.
     */
    fun commit(
        files: Map<String, Any>,
        branch: String = "main",
        committed: Instant = Instant.now(),
        authored: Instant = committed,
        merging: ObjectId? = null,
        from: ObjectId? = null,
    ): ObjectId {
        repository.newObjectInserter().use { inserter ->
            val index = DirCache.newInCore()
            val builder = index.builder()
            files.toSortedMap().forEach { (path, value) ->
                val content = if (value is String) Text(value) else value as Content
                builder.add(DirCacheEntry(path).apply {
                    when (content) {
                        is Text -> {
                            fileMode = if (content.executable) FileMode.EXECUTABLE_FILE else FileMode.REGULAR_FILE
                            setObjectId(inserter.insert(Constants.OBJ_BLOB, content.text.toByteArray()))
                        }
                        is Bytes -> {
                            fileMode = FileMode.REGULAR_FILE
                            setObjectId(inserter.insert(Constants.OBJ_BLOB, content.bytes))
                        }
                        is Symlink -> {
                            fileMode = FileMode.SYMLINK
                            setObjectId(inserter.insert(Constants.OBJ_BLOB, content.target.toByteArray()))
                        }
                        Submodule -> {
                            fileMode = FileMode.GITLINK
                            setObjectId(ObjectId.fromString("0123456789abcdef0123456789abcdef01234567"))
                        }
                    }
                })
            }
            builder.finish()

            val refName = "refs/heads/$branch"
            val parent = repository.resolve(refName) ?: from
            fun ident(time: Instant) = PersonIdent("fixture", "fixture@example.com", time, ZoneOffset.UTC)
            val commitId = inserter.insert(CommitBuilder().apply {
                setTreeId(index.writeTree(inserter))
                setParentIds(listOfNotNull(parent, merging))
                author = ident(authored)
                committer = ident(committed)
                message = "fixture"
            })
            inserter.flush()

            val update = repository.updateRef(refName)
            update.setNewObjectId(commitId)
            update.setForceUpdate(true)
            check(update.update() in setOf(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD))
            return commitId
        }
    }

    fun tag(name: String, commit: ObjectId) {
        val update = repository.updateRef("refs/tags/$name")
        update.setNewObjectId(commit)
        check(update.update() == RefUpdate.Result.NEW)
    }

    override fun close() {
        repository.close()
        dir.deleteRecursively()
    }
}
