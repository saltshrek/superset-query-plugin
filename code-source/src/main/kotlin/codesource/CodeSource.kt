package codesource

import java.time.Instant

/**
 * Read access to one file tree. The HTTP layer depends only on this, so the
 * backend (git today) and later search can change behind it.
 */
interface CodeSource {
    fun list(path: RepoPath): ListResult

    /** The contents of the file at [path], as text. */
    fun read(path: RepoPath): ReadResult

    /**
     * Files whose paths contain the words of [text] and that match [filters],
     * best first, at most [limit] of them. With no words and no filters,
     * nothing.
     */
    fun search(text: String, filters: SearchFilters, limit: Int): SearchResult

    /**
     * Values of [fields] starting with [text] that a search's filters can
     * take, each with how many files with one of [extensions] (if any) a
     * search for it alone would find. Most files first, at most [limit].
     */
    fun suggest(text: String, fields: Set<OutlineField>, extensions: Set<String>, limit: Int): SuggestResult

    /** Brings the tree up to date. Throws [RefreshFailedException] on failure. */
    fun refresh()
}

enum class EntryType { FILE, DIRECTORY }

/**
 * [outline] and [modified] (when the file last changed, if known) are for
 * files only, [description] for directories only.
 */
data class Entry(
    val name: String,
    val path: RepoPath,
    val type: EntryType,
    val outline: Outline? = null,
    val description: String? = null,
    val modified: Instant? = null,
)

sealed interface ListResult {
    /** [description] is the listed directory's own. */
    data class Directory(val path: RepoPath, val description: String?, val entries: List<Entry>) : ListResult
    data object NotFound : ListResult
    data object NotADirectory : ListResult
    data object NotReady : ListResult
}

sealed interface ReadResult {
    data class File(val path: RepoPath, val content: String, val outline: Outline, val modified: Instant?) : ReadResult
    data object NotFound : ReadResult
    data object NotAFile : ReadResult
    data object TooLarge : ReadResult
    data object NotText : ReadResult
    data object NotReady : ReadResult

    companion object {
        /** Files bigger than this many bytes are [TooLarge]. */
        const val MAX_BYTES = 1024 * 1024
    }
}

/**
 * What a search asks for besides words in paths. A file must have one of
 * [extensions] (if any), and every one of [references], [functions] and
 * [keywords] in its outline. A reference also matches a file that wrote it
 * with fewer parts, if [includeUnqualified].
 */
data class SearchFilters(
    val extensions: Set<String> = emptySet(),
    val references: List<String> = emptyList(),
    val functions: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val includeUnqualified: Boolean = true,
) {
    /** Whether any filter selects files by their outline. */
    val selectsByOutline get() = references.isNotEmpty() || functions.isNotEmpty() || keywords.isNotEmpty()
}

/** Where a search matched in some text: code points [start] until [end]. */
data class Match(val start: Int, val end: Int)

/** A [part] of a file where a search matched its name or its description. */
data class PartHit(val part: Part, val nameMatches: List<Match>, val descriptionMatches: List<Match>)

/** [outline] and [modified] are as the file's [Entry] has them. */
data class Hit(
    val path: RepoPath,
    val type: EntryType,
    val score: Float,
    val matches: List<Match>,
    val parts: List<PartHit> = emptyList(),
    val outline: Outline? = null,
    val modified: Instant? = null,
)

sealed interface SearchResult {
    /** The best [hits], and how many there were in [total]. */
    data class Hits(val total: Int, val hits: List<Hit>) : SearchResult
    data object NotReady : SearchResult
}

/** The outline fields a search can filter by, in the order suggestions list them. */
enum class OutlineField { REFERENCE, FUNCTION, KEYWORD }

/** A [value] of [field], which a search for it alone would find in [files] files. */
data class Suggestion(val field: OutlineField, val value: String, val files: Int)

sealed interface SuggestResult {
    data class Suggestions(val suggestions: List<Suggestion>) : SuggestResult
    data object NotReady : SuggestResult
}

class RefreshFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)
