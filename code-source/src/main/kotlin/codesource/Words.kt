package codesource

/** A word found in some text: [text] lower-cased, from UTF-16 offset [start] until [end] in the original. */
data class Word(val text: String, val start: Int, val end: Int)

/**
 * Splits [text] into words, for searching (docs/features/006-filename-search.md).
 *
 * A word is a run of letters and digits. It also ends where lower case
 * changes to upper (`newCustomers`), and before the last capital of a run of
 * capitals followed by lower case (`HTTPServer`). Words are lower-cased one
 * code point at a time, so a word has exactly as many code points as the
 * text it came from, and a prefix of it maps back onto the original.
 */
fun words(text: String): List<Word> {
    val result = mutableListOf<Word>()
    var start = -1
    var previous = 0
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        val next = i + Character.charCount(c)
        if (Character.isLetterOrDigit(c)) {
            val following = if (next < text.length) text.codePointAt(next) else -1
            if (start >= 0 && startsWord(previous, c, following)) {
                result += word(text, start, i)
                start = i
            }
            if (start < 0) start = i
            previous = c
        } else if (start >= 0) {
            result += word(text, start, i)
            start = -1
        }
        i = next
    }
    if (start >= 0) result += word(text, start, text.length)
    return result
}

private fun startsWord(previous: Int, c: Int, following: Int) =
    Character.isUpperCase(c) &&
        (Character.isLowerCase(previous) || (Character.isUpperCase(previous) && following >= 0 && Character.isLowerCase(following)))

private fun word(text: String, start: Int, end: Int): Word {
    val lower = buildString {
        text.substring(start, end).codePoints().forEach { appendCodePoint(Character.toLowerCase(it)) }
    }
    return Word(lower, start, end)
}
