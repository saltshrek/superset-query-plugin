package codesource

/**
 * A canonical repository path: empty for the root, otherwise segments joined
 * by `/`, each non-empty, free of NUL, and neither `.` nor `..`.
 *
 * Request paths are untrusted. Anything that isn't already canonical is
 * rejected, never normalised, so each path has exactly one spelling
 * (docs/features/003-browse-tree.md, Paths).
 */
@JvmInline
value class RepoPath private constructor(val value: String) {
    val isRoot: Boolean get() = value.isEmpty()

    /** The path of the entry [name] inside this directory. */
    fun child(name: String): RepoPath =
        requireNotNull(parse(if (isRoot) name else "$value/$name")) { "Invalid entry name: $name" }

    override fun toString() = value

    companion object {
        val ROOT = RepoPath("")

        /** Returns the path, or null if [raw] isn't canonical. */
        fun parse(raw: String): RepoPath? {
            if (raw.isEmpty()) return ROOT
            val valid = raw.split('/').all { segment ->
                segment.isNotEmpty() && segment != "." && segment != ".." && '\u0000' !in segment
            }
            return if (valid) RepoPath(raw) else null
        }
    }
}
