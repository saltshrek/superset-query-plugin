package codesource

import kotlinx.serialization.Serializable

/**
 * What's in a file (docs/api.md, "Outlines"). The top-level fields are the
 * same for every language. Language-specific detail sits in a field named
 * after the language, such as [sql].
 */
@Serializable
data class Outline(
    val language: String?,
    val partial: Boolean,
    val keywords: List<String>,
    val functions: List<String>,
    val references: List<String>,
    val sql: SqlOutline? = null,
) {
    /** The file's parts, whatever its language, for search. */
    fun parts(): List<Part> = sql?.parts().orEmpty()

    companion object {
        /** A file in [language] that couldn't be analysed. */
        fun unanalysed(language: String?) = when (language) {
            SQL -> Outline(SQL, true, emptyList(), emptyList(), emptyList(), SqlOutline(emptyList()))
            else -> Outline(language, false, emptyList(), emptyList(), emptyList())
        }

        const val SQL = "sql"

        /** The language of the file at [path], or null if code-source doesn't recognise it. */
        fun languageOf(path: RepoPath): String? =
            if (path.value.substringAfterLast('/').lowercase().endsWith(".sql")) SQL else null
    }
}

@Serializable
data class LineRange(val start: Int, val end: Int)

/**
 * Lines [start] to [end], from [startColumn] on the first to [endColumn] on
 * the last. All 1-based and inclusive; columns count code points.
 */
@Serializable
data class TextRange(val start: Int, val end: Int, val startColumn: Int, val endColumn: Int)

/**
 * A piece of a file that may have a name and a description, such as a
 * function in most languages (docs/features/014-search-descriptions-and-ctes.md).
 * Search matches words in them without knowing the language. [range] is the
 * piece's range in its language's outline.
 */
data class Part(val range: TextRange, val name: String?, val description: String?)

@Serializable
data class SqlOutline(val queries: List<SqlQuery>) {
    /** Each query (unnamed), then its CTEs, in the order they're written. */
    fun parts(): List<Part> = queries.flatMap { query ->
        listOf(Part(query.range, null, query.description)) + query.ctes.map { Part(it.range, it.name, it.description) }
    }
}

@Serializable
data class SqlQuery(
    val range: TextRange,
    val description: String?,
    /** The lines [description] came from; null when it's null. */
    val descriptionRange: LineRange?,
    val keywords: List<String>,
    val functions: List<String>,
    val tables: List<String>,
    val uses: List<String>,
    /** Whether its WITH clause says RECURSIVE. */
    val recursive: Boolean,
    val ctes: List<SqlCte>,
)

@Serializable
data class SqlCte(
    val name: String,
    val range: TextRange,
    val description: String?,
    /** The lines [description] came from; null when it's null. */
    val descriptionRange: LineRange?,
    val keywords: List<String>,
    val functions: List<String>,
    val tables: List<String>,
    val uses: List<String>,
)
