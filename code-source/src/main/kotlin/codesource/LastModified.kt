package codesource

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.time.Instant

/**
 * When each of [paths] last changed in [commit]'s first-parent history: the
 * committer time of the newest commit there that changed it, compared with
 * its first parent. So a change merged from a branch counts from the merge.
 *
 * Walks back from [commit] until every path has a time. Paths the history
 * doesn't reach (a shallow clone's cut-off) are left out.
 */
fun lastModified(repository: Repository, commit: ObjectId, paths: Set<RepoPath>): Map<RepoPath, Instant> {
    val pending = paths.mapTo(HashSet()) { it.value }
    val found = HashMap<RepoPath, Instant>()
    val shallow = repository.objectDatabase.shallowCommits
    RevWalk(repository).use { walk ->
        walk.setFirstParent(true)
        walk.markStart(walk.parseCommit(commit))
        for (current in walk) {
            if (pending.isEmpty()) break
            // A shallow clone's oldest commits have parents it doesn't hold, so
            // what they changed can't be told.
            if (current in shallow) break
            val parent = current.parents.firstOrNull()?.let { walk.parseCommit(it) }
            val time = current.committerIdent.whenAsInstant
            TreeWalk(repository).use { diff ->
                if (parent == null) diff.addTree(EmptyTreeIterator()) else diff.addTree(parent.tree)
                diff.addTree(current.tree)
                diff.isRecursive = true
                diff.filter = TreeFilter.ANY_DIFF
                while (diff.next()) {
                    val path = diff.pathString
                    if (pending.remove(path)) found[RepoPath.parse(path)!!] = time
                }
            }
        }
    }
    return found
}
