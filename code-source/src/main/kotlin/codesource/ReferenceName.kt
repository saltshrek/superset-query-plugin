package codesource

import java.util.Locale

/**
 * A reference's name as parts, `.` separating them (`iceberg.sales.orders`),
 * compared ignoring case and quotes.
 */
object ReferenceName {
    val QUOTES = charArrayOf('"', '`', '[', ']')

    /** The parts of [name], normalised, or null if any part is empty. */
    fun parts(name: String): List<String>? =
        name.split('.')
            .map { it.trim().trim(*QUOTES).lowercase(Locale.ROOT) }
            .takeIf { parts -> parts.none { it.isEmpty() } }

    /** [parts] and each shorter name made of its last parts, longest first. */
    fun suffixes(parts: List<String>): List<String> = parts.indices.map { parts.drop(it).joinToString(".") }
}
