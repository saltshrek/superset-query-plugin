package codesource

/** A folder's README, which gives the folder its description. */
object Readme {
    /** Whether a file named [name] is a folder's README: `README.md`, in any case. */
    fun isReadme(name: String) = name.equals("README.md", ignoreCase = true)

    /**
     * The description a README's [text] gives its folder: its first paragraph
     * that isn't a heading, lines joined with spaces, Markdown as written.
     */
    fun description(text: String): String? {
        val paragraphs = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in text.lines().map { it.trim() } + "") {
            if (line.isNotEmpty()) {
                current += line
            } else if (current.isNotEmpty()) {
                paragraphs += current
                current = mutableListOf()
            }
        }
        return paragraphs
            .map { lines -> lines.filterNot { it.startsWith("#") } }
            // "Title" over "=====" or "-----" is a heading too.
            .filterNot { lines -> lines.size == 2 && (lines[1].all { it == '=' } || lines[1].all { it == '-' }) }
            .firstOrNull { it.isNotEmpty() }
            ?.joinToString(" ")
    }
}
