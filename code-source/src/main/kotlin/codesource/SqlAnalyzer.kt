package codesource

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Outlines SQL files by running the Python analyzer, `analyzers/sql/analyze.py`,
 * as a subprocess: once per call, with every file at once, so Python's start-up
 * is paid once per refresh. The contract is in
 * docs/features/007-file-outlines.md.
 *
 * The analyzer is treated as fallible. Whatever goes wrong (it won't start,
 * crashes, hangs, prints too much or prints rubbish), files it gave no usable
 * outline for are simply missing from the result. A hung analyzer is killed
 * after [timeout]. If it still hasn't exited [stopTimeout] later (a process
 * stuck in the kernel can't be killed), it's abandoned, so a refresh never
 * waits for it indefinitely.
 */
class SqlAnalyzer(
    private val command: List<String>,
    private val dialect: String?,
    private val timeout: Duration = 2.minutes,
    private val stopTimeout: Duration = 10.seconds,
    private val maxOutputBytes: Long = 256L * 1024 * 1024,
    private val start: (List<String>) -> Process = { ProcessBuilder(it).start() },
) {
    private val log = LoggerFactory.getLogger(SqlAnalyzer::class.java)

    fun analyze(files: Map<RepoPath, String>): Map<RepoPath, Outline> {
        if (files.isEmpty()) return emptyMap()
        val started = TimeSource.Monotonic.markNow()

        val process = try {
            start(command)
        } catch (e: IOException) {
            log.warn("SQL analyzer couldn't start: {}", e.message)
            return emptyMap()
        }

        val input = buildJsonObject {
            put("dialect", dialect)
            putJsonArray("files") {
                files.forEach { (path, text) ->
                    addJsonObject {
                        put("path", path.value)
                        put("text", text)
                    }
                }
            }
        }.toString()

        // Writing, reading and logging stderr each get a thread, so a full
        // pipe on one side can't stall the others.
        val writer = thread(name = "sql-analyzer-stdin", isDaemon = true) {
            try {
                process.outputStream.bufferedWriter().use { it.write(input) }
            } catch (e: IOException) {
                // The analyzer stopped reading; its exit status says why.
            }
        }
        val stderr = thread(name = "sql-analyzer-stderr", isDaemon = true) {
            process.errorStream.bufferedReader().forEachLine { log.warn("SQL analyzer: {}", it) }
        }
        val results = mutableMapOf<RepoPath, Outline>()
        var rejected = 0
        val reader = thread(name = "sql-analyzer-stdout", isDaemon = true) {
            var bytes = 0L
            process.inputStream.bufferedReader().forEachLine { line ->
                bytes += line.length + 1
                if (bytes > maxOutputBytes) {
                    if (process.isAlive) log.warn("SQL analyzer printed more than {} bytes; stopping it", maxOutputBytes)
                    stop(process)
                    return@forEachLine
                }
                val result = parse(line)
                val path = result?.path?.let(RepoPath::parse)
                if (result == null || path == null || path !in files || result.version != VERSION) {
                    rejected++
                    return@forEachLine
                }
                if (result.problems.isNotEmpty()) log.debug("{}: {}", path, result.problems.joinToString("; "))
                if (result.outline != null) synchronized(results) { results.putIfAbsent(path, result.outline) }
            }
        }

        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            log.warn("SQL analyzer didn't finish within {}; stopping it", timeout)
            stop(process)
            if (!process.waitFor(stopTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                log.warn("SQL analyzer didn't stop within {}; abandoning it", stopTimeout)
            }
        }
        listOf(writer, reader, stderr).forEach { it.join(JOIN_MILLIS) }

        if (!process.isAlive && process.exitValue() != 0) {
            log.warn("SQL analyzer exited with status {}", process.exitValue())
        }
        if (rejected > 0) log.warn("SQL analyzer printed {} unusable lines", rejected)
        val outlines = synchronized(results) { results.toMap() }
        log.info(
            "Outlined {} of {} SQL files in {} ms",
            outlines.size, files.size, started.elapsedNow().inWholeMilliseconds,
        )
        return outlines
    }

    /** Stops [process] and anything it started, which could otherwise hold its output open. */
    private fun stop(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    @Serializable
    private data class Result(val version: Int, val path: String, val outline: Outline?, val problems: List<String>)

    private fun parse(line: String): Result? = try {
        json.decodeFromString<Result>(line)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        private const val VERSION = 1
        private const val JOIN_MILLIS = 5_000L
        private val json = Json { ignoreUnknownKeys = false }

        /** The analyzer as built or installed with code-source; see [Analyzers]. */
        fun installed(dialect: String?) =
            SqlAnalyzer(listOf(Analyzers.python, File(Analyzers.dir, "sql/analyze.py").path), dialect)
    }
}

/** Where the analyzers and their Python are. */
object Analyzers {
    /**
     * Set by the build for tests and `run`. An installation finds them next to
     * its jars, in `analyzers/` and `analyzers/venv/`.
     */
    val dir: File by lazy {
        System.getProperty("codesource.analyzers")?.let(::File)
            ?: File(Analyzers::class.java.protectionDomain.codeSource.location.toURI()).parentFile.resolveSibling("analyzers")
    }

    val python: String by lazy {
        System.getProperty("codesource.python") ?: File(dir, "venv/bin/python").path
    }
}
