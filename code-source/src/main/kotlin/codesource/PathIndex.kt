package codesource

import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.SortedDocValuesField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.MultiTerms
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermsEnum
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.ConstantScoreQuery
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.MatchNoDocsQuery
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.StringHelper
import java.util.Locale
import kotlin.math.ln

/**
 * A searchable, in-memory index of files: one document per file, with the
 * words of its name, its parts' names, its parts' descriptions and its
 * folders in separate fields, so matches in each can rank differently, and
 * fields from its outline to filter by. Immutable once built; a new tree
 * gets a new index.
 */
class PathIndex private constructor(private val searcher: IndexSearcher, private val outline: (RepoPath) -> Outline) {

    fun search(text: String, filters: SearchFilters, limit: Int): SearchResult.Hits {
        val queryWords = words(text).map { it.text }
        if (queryWords.isEmpty() && !filters.selectsByOutline) return SearchResult.Hits(0, emptyList())

        val query = query(queryWords, filters)
        val top = searcher.search(query, limit, SORT, true)
        val stored = searcher.storedFields()
        val hits = top.scoreDocs.map { doc ->
            val path = RepoPath.parse(stored.document(doc.doc).get(PATH))!!
            if (queryWords.isEmpty()) return@map Hit(path, EntryType.FILE, doc.score, emptyList())
            Hit(path, EntryType.FILE, doc.score, matches(path.value, queryWords), partHits(outline(path), queryWords))
        }
        return SearchResult.Hits(searcher.count(query), hits)
    }

    /**
     * Values of [fields] that start with [text], each with how many files
     * with one of [extensions] (if any) a search for it alone would find.
     * Most files first, at most [limit] of them.
     */
    fun suggest(text: String, fields: Set<OutlineField>, extensions: Set<String>, limit: Int): List<Suggestion> {
        if (text.isBlank()) return emptyList()
        val candidates = OutlineField.entries.filter { it in fields }.flatMap { field ->
            val (indexField, prefix) = when (field) {
                // Every name a reference can be searched by.
                OutlineField.REFERENCE -> REFERENCE_SUFFIX to text.trim().filterNot { it in ReferenceName.QUOTES }.lowercase(Locale.ROOT)
                OutlineField.FUNCTION -> FUNCTION to text.trim().uppercase(Locale.ROOT)
                OutlineField.KEYWORD -> KEYWORD to normaliseKeyword(text)
            }
            if (prefix.isEmpty()) return@flatMap emptyList()
            termsStartingWith(indexField, prefix).mapNotNull { value ->
                val filters = SearchFilters(
                    extensions = extensions,
                    references = if (field == OutlineField.REFERENCE) listOf(value) else emptyList(),
                    functions = if (field == OutlineField.FUNCTION) listOf(value) else emptyList(),
                    keywords = if (field == OutlineField.KEYWORD) listOf(value) else emptyList(),
                )
                searcher.count(query(emptyList(), filters)).takeIf { it > 0 }?.let { Suggestion(field, value, it) }
            }
        }
        // Stable, so values with as many files stay in field, then term (code-point) order.
        return candidates.sortedByDescending { it.files }.take(limit)
    }

    /** The terms of [field] that start with [prefix], in code-point order. */
    private fun termsStartingWith(field: String, prefix: String): List<String> {
        val terms = MultiTerms.getTerms(searcher.indexReader, field) ?: return emptyList()
        val start = BytesRef(prefix)
        val iterator = terms.iterator()
        if (iterator.seekCeil(start) == TermsEnum.SeekStatus.END) return emptyList()
        return generateSequence(iterator.term()) { iterator.next() }
            .takeWhile { StringHelper.startsWith(it, start) }
            .map { it.utf8ToString() }
            .toList()
    }

    /** Files that have [queryWords] (the last as a prefix) and that match [filters]. */
    private fun query(queryWords: List<String>, filters: SearchFilters): Query = BooleanQuery.Builder().apply {
        queryWords.forEachIndexed { i, word ->
            add(wordQuery(word, prefix = i == queryWords.lastIndex), Occur.MUST)
        }
        relevance(queryWords).forEach { add(it, Occur.SHOULD) }
        if (queryWords.isEmpty()) add(MatchAllDocsQuery(), Occur.MUST)
        if (filters.extensions.isNotEmpty()) {
            add(TermInSetQuery(EXT, filters.extensions.map { BytesRef(it.lowercase(Locale.ROOT)) }), Occur.FILTER)
        }
        filters.references.forEach {
            add(referenceQuery(it, filters.includeUnqualified, FULL_REFERENCES / filters.references.size), Occur.MUST)
        }
        filters.functions.forEach { add(TermQuery(Term(FUNCTION, it.uppercase(Locale.ROOT))), Occur.FILTER) }
        filters.keywords.forEach { add(TermQuery(Term(KEYWORD, normaliseKeyword(it))), Occur.FILTER) }
    }.build()

    /**
     * How much files' descriptions are about [queryWords], as BM25 scores
     * them, to break ties between files that words score the same. Only whole
     * words count, so a last word still being typed adds nothing until it's
     * complete.
     *
     * BM25 scores a word at most its idf, which is at most ln(1 + N) for N
     * files with descriptions, so each word's score is scaled to stay under
     * its share of [RELEVANCE].
     */
    private fun relevance(queryWords: List<String>): List<Query> {
        if (queryWords.isEmpty()) return emptyList()
        val described = searcher.collectionStatistics(PART_DESCRIPTION)?.docCount() ?: return emptyList()
        val scale = RELEVANCE / (queryWords.size * ln(1.0 + described)).toFloat()
        return queryWords.map { BoostQuery(TermQuery(Term(PART_DESCRIPTION, it)), scale) }
    }

    companion object {
        private const val PATH = "path"
        private const val NAME = "name"
        private const val FOLDERS = "folders"
        private const val PART_NAME = "part_name"
        private const val PART_DESCRIPTION = "part_description"
        private const val EXT = "ext"
        private const val REFERENCE_SUFFIX = "reference_suffix"
        private const val REFERENCE = "reference"
        private const val FUNCTION = "function"
        private const val KEYWORD = "keyword"

        // Scores per word typed, taking the best way it matched: the file's
        // name, then its parts' names, then their descriptions, then its
        // folders, and whole words before prefixes in each.
        private const val NAME_WORD = 8f
        private const val NAME_PREFIX = 7f
        private const val PART_NAME_WORD = 6f
        private const val PART_NAME_PREFIX = 5f
        private const val DESCRIPTION_WORD = 4f
        private const val DESCRIPTION_PREFIX = 3f
        private const val FOLDER_WORD = 2f
        private const val FOLDER_PREFIX = 1f

        // A score is layered: word scores in whole numbers, then, under 1,
        // description relevance (under RELEVANCE), then, far smaller,
        // references written in full (FULL_REFERENCES among them all), so
        // each only orders files the layers above leave tied. Relevance that
        // differs by less than FULL_REFERENCES counts as tied.
        private const val RELEVANCE = 0.5f
        private const val FULL_REFERENCES = 0.0001f

        // Best first, then code-point order (Lucene compares UTF-8 bytes).
        private val SORT = Sort(SortField.FIELD_SCORE, SortField(PATH, SortField.Type.STRING))

        /**
         * Indexes [files], with their outlines from [outline]. Anything under a
         * dot-entry is left out: search never returns it.
         */
        fun build(files: Sequence<RepoPath>, outline: (RepoPath) -> Outline): PathIndex {
            val directory = ByteBuffersDirectory()
            IndexWriter(directory, IndexWriterConfig()).use { writer ->
                files.filterNot { path -> path.value.split('/').any { it.startsWith('.') } }
                    .forEach { writer.addDocument(document(it, outline(it))) }
            }
            return PathIndex(IndexSearcher(DirectoryReader.open(directory)), outline)
        }

        private fun document(path: RepoPath, outline: Outline) = Document().apply {
            val name = path.value.substringAfterLast('/')
            val folders = path.value.substringBeforeLast('/', "")
            add(StoredField(PATH, path.value))
            add(SortedDocValuesField(PATH, BytesRef(path.value)))
            add(TextField(NAME, WordStream(words(name).map { it.text })))
            add(TextField(FOLDERS, WordStream(words(folders).map { it.text })))
            outline.parts().forEach { part ->
                part.name?.let { add(TextField(PART_NAME, WordStream(words(it).map { word -> word.text }))) }
                part.description?.let { add(TextField(PART_DESCRIPTION, WordStream(words(it).map { word -> word.text }))) }
            }
            if ('.' in name) {
                add(StringField(EXT, name.substringAfterLast('.').lowercase(Locale.ROOT), Field.Store.NO))
            }
            // A reference is indexed by every name it can be asked for by
            // (`orders`, `sales.orders`, `iceberg.sales.orders`) and as written.
            outline.references.mapNotNull(ReferenceName::parts).forEach { parts ->
                ReferenceName.suffixes(parts).forEach { add(StringField(REFERENCE_SUFFIX, it, Field.Store.NO)) }
                add(StringField(REFERENCE, parts.joinToString("."), Field.Store.NO))
            }
            outline.functions.forEach { add(StringField(FUNCTION, it.uppercase(Locale.ROOT), Field.Store.NO)) }
            outline.keywords.forEach { add(StringField(KEYWORD, normaliseKeyword(it), Field.Store.NO)) }
        }

        private fun normaliseKeyword(keyword: String) = keyword.trim().split(Regex("\\s+")).joinToString(" ").uppercase(Locale.ROOT)

        /**
         * Files with the reference [name]: written with at least its parts,
         * scoring [fullScore], or, if [includeUnqualified], with fewer of them,
         * scoring nothing.
         */
        private fun referenceQuery(name: String, includeUnqualified: Boolean, fullScore: Float): Query {
            val parts = ReferenceName.parts(name) ?: return MatchNoDocsQuery()
            val full = scored(TermQuery(Term(REFERENCE_SUFFIX, parts.joinToString("."))), fullScore)
            if (!includeUnqualified || parts.size == 1) return full
            val shorter = ReferenceName.suffixes(parts).drop(1).map { BytesRef(it) }
            return DisjunctionMaxQuery(listOf(full, scored(TermInSetQuery(REFERENCE, shorter), 0f)), 0f)
        }

        private fun wordQuery(word: String, prefix: Boolean): Query {
            val ways = mutableListOf(
                scored(TermQuery(Term(NAME, word)), NAME_WORD),
                scored(TermQuery(Term(PART_NAME, word)), PART_NAME_WORD),
                scored(TermQuery(Term(PART_DESCRIPTION, word)), DESCRIPTION_WORD),
                scored(TermQuery(Term(FOLDERS, word)), FOLDER_WORD),
            )
            if (prefix) {
                ways += scored(PrefixQuery(Term(NAME, word)), NAME_PREFIX)
                ways += scored(PrefixQuery(Term(PART_NAME, word)), PART_NAME_PREFIX)
                ways += scored(PrefixQuery(Term(PART_DESCRIPTION, word)), DESCRIPTION_PREFIX)
                ways += scored(PrefixQuery(Term(FOLDERS, word)), FOLDER_PREFIX)
            }
            return DisjunctionMaxQuery(ways, 0f)
        }

        private fun scored(query: Query, score: Float) = BoostQuery(ConstantScoreQuery(query), score)

        /** The parts of [outline] whose names or descriptions [queryWords] matched, in the order they're written. */
        private fun partHits(outline: Outline, queryWords: List<String>): List<PartHit> =
            outline.parts().mapNotNull { part ->
                val name = part.name?.let { matches(it, queryWords) }.orEmpty()
                val description = part.description?.let { matches(it, queryWords) }.orEmpty()
                if (name.isEmpty() && description.isEmpty()) null else PartHit(part, name, description)
            }

        /** Where [queryWords] matched in [text], in code points: whole words, or the matched prefix for the last word. */
        private fun matches(text: String, queryWords: List<String>): List<Match> {
            val whole = queryWords.toSet()
            val prefix = queryWords.last()
            val prefixLength = prefix.codePointCount(0, prefix.length)
            return words(text).mapNotNull { word ->
                val end = when {
                    word.text in whole -> word.end
                    word.text.startsWith(prefix) -> text.offsetByCodePoints(word.start, prefixLength)
                    else -> return@mapNotNull null
                }
                Match(text.codePointCount(0, word.start), text.codePointCount(0, end))
            }
        }
    }

    /** Hands Lucene words we've already split, so the index and the query agree on what a word is. */
    private class WordStream(private val words: List<String>) : TokenStream() {
        private val term = addAttribute(CharTermAttribute::class.java)
        private var next = 0

        override fun incrementToken(): Boolean {
            if (next == words.size) return false
            clearAttributes()
            term.setEmpty().append(words[next++])
            return true
        }

        override fun reset() {
            super.reset()
            next = 0
        }
    }
}
