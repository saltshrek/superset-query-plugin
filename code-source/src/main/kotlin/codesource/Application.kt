package codesource

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

fun main() {
    val config = try {
        Config.fromEnv(System.getenv())
    } catch (e: ConfigException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    val dataDir = config.dataDir ?: Files.createTempDirectory("code-source").toFile()

    val source = GitCodeSource(
        config.url, config.ref, File(dataDir, "repo.git"), config.credentials,
        SqlAnalyzer.installed(config.sqlDialect),
    )
    RefreshScheduler(source, config.refreshInterval).start()
    embeddedServer(Netty, port = config.port) { module(source) }.start(wait = true)
}

/** Operational endpoints only. */
fun Application.module() {
    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
    }
}

/** The whole service: operational endpoints plus the API over [source]. */
fun Application.module(source: CodeSource) {
    module()
    routing {
        post("/refresh") {
            try {
                source.refresh()
                call.respondJson(HttpStatusCode.OK, buildJsonObject { put("status", "ok") })
            } catch (e: RefreshFailedException) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "refresh_failed", "Refresh failed: ${e.message}")
            }
        }

        get("/api/v1/tree") {
            val raw = call.request.queryParameters["path"] ?: ""
            val path = RepoPath.parse(raw)
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_path", "Not a canonical repository path")
            when (val result = source.list(path)) {
                is ListResult.Directory -> call.respondJson(HttpStatusCode.OK, result.toJson())
                ListResult.NotADirectory ->
                    call.respondError(HttpStatusCode.BadRequest, "not_a_directory", "'$path' is a file")
                ListResult.NotFound ->
                    call.respondError(HttpStatusCode.NotFound, "not_found", "Nothing at '$path'")
                ListResult.NotReady ->
                    call.respondError(HttpStatusCode.ServiceUnavailable, "not_ready", "The repository hasn't been loaded yet")
            }
        }

        get("/api/v1/file") {
            val raw = call.request.queryParameters["path"] ?: ""
            val path = RepoPath.parse(raw)
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_path", "Not a canonical repository path")
            when (val result = source.read(path)) {
                is ReadResult.File -> call.respondJson(HttpStatusCode.OK, buildJsonObject {
                    put("path", result.path.value)
                    put("content", result.content)
                    put("outline", result.outline.toJson())
                    put("modified", result.modified?.toJson())
                })
                ReadResult.NotAFile ->
                    call.respondError(HttpStatusCode.BadRequest, "not_a_file", "'$path' is a directory")
                ReadResult.NotFound ->
                    call.respondError(HttpStatusCode.NotFound, "not_found", "Nothing at '$path'")
                ReadResult.TooLarge ->
                    call.respondError(HttpStatusCode.PayloadTooLarge, "too_large", "'$path' is larger than 1 MiB")
                ReadResult.NotText ->
                    call.respondError(HttpStatusCode.UnprocessableEntity, "not_text", "'$path' isn't UTF-8 text")
                ReadResult.NotReady ->
                    call.respondError(HttpStatusCode.ServiceUnavailable, "not_ready", "The repository hasn't been loaded yet")
            }
        }

        get("/api/v1/search") {
            val params = call.request.queryParameters
            suspend fun invalid(message: String) = call.respondError(HttpStatusCode.BadRequest, "invalid_parameter", message)

            val q = params["q"] ?: ""
            if (q.codePointCount(0, q.length) > MAX_QUERY_LENGTH) {
                return@get invalid("q is longer than $MAX_QUERY_LENGTH characters")
            }
            val limit = params.limit(DEFAULT_LIMIT) ?: return@get invalid(INVALID_LIMIT)
            val extensions = params.extensions() ?: return@get invalid(INVALID_EXT)
            fun values(name: String) = params.getAll(name).orEmpty()
            val references = values("reference")
            if (references.any { it.length > MAX_QUERY_LENGTH || ReferenceName.parts(it) == null }) {
                return@get invalid("reference must be a name such as 'schema.table', with no empty parts")
            }
            for (name in listOf("function", "keyword")) {
                if (values(name).any { it.isBlank() || it.length > MAX_QUERY_LENGTH }) {
                    return@get invalid("$name must be a name of up to $MAX_QUERY_LENGTH characters")
                }
            }
            val includeUnqualified = when (params["unqualified"]) {
                null, "include" -> true
                "exclude" -> false
                else -> return@get invalid("unqualified must be 'include' or 'exclude'")
            }
            val filters = SearchFilters(extensions, references, values("function"), values("keyword"), includeUnqualified)

            when (val result = source.search(q, filters, limit)) {
                is SearchResult.Hits -> call.respondJson(HttpStatusCode.OK, result.toJson())
                SearchResult.NotReady ->
                    call.respondError(HttpStatusCode.ServiceUnavailable, "not_ready", "The repository hasn't been loaded yet")
            }
        }

        get("/api/v1/suggestions") {
            val params = call.request.queryParameters
            suspend fun invalid(message: String) = call.respondError(HttpStatusCode.BadRequest, "invalid_parameter", message)

            val q = params["q"] ?: ""
            if (q.codePointCount(0, q.length) > MAX_QUERY_LENGTH) {
                return@get invalid("q is longer than $MAX_QUERY_LENGTH characters")
            }
            val fields = params.getAll("field")?.map { name ->
                OutlineField.entries.find { it.parameter == name }
                    ?: return@get invalid("field must be 'reference', 'function' or 'keyword'")
            }?.toSet() ?: OutlineField.entries.toSet()
            val extensions = params.extensions() ?: return@get invalid(INVALID_EXT)
            val limit = params.limit(DEFAULT_SUGGESTIONS) ?: return@get invalid(INVALID_LIMIT)

            when (val result = source.suggest(q, fields, extensions, limit)) {
                is SuggestResult.Suggestions -> call.respondJson(HttpStatusCode.OK, buildJsonObject {
                    putJsonArray("suggestions") {
                        result.suggestions.forEach { suggestion ->
                            addJsonObject {
                                put("field", suggestion.field.parameter)
                                put("value", suggestion.value)
                                put("files", suggestion.files)
                            }
                        }
                    }
                })
                SuggestResult.NotReady ->
                    call.respondError(HttpStatusCode.ServiceUnavailable, "not_ready", "The repository hasn't been loaded yet")
            }
        }
    }
}


/** The `ext` values given, or null if any isn't an extension without its dot. */
private fun Parameters.extensions(): Set<String>? =
    getAll("ext").orEmpty().toSet().takeUnless { all -> all.any { it.isEmpty() || '.' in it || '/' in it } }

/** The `limit` given, [default] if none, or null if it's out of range. */
private fun Parameters.limit(default: Int): Int? = when (val raw = this["limit"]) {
    null -> default
    else -> raw.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
}

/** The search parameter that takes values of this field. */
private val OutlineField.parameter get() = name.lowercase()

private const val MAX_QUERY_LENGTH = 500
private const val DEFAULT_SUGGESTIONS = 10
private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100
private const val INVALID_EXT = "ext must be an extension without its dot, such as 'txt'"
private const val INVALID_LIMIT = "limit must be a whole number from 1 to $MAX_LIMIT"

private fun SearchResult.Hits.toJson() = buildJsonObject {
    put("total", total)
    putJsonArray("results") {
        hits.forEach { hit ->
            addJsonObject {
                put("path", hit.path.value)
                put("type", hit.type.name.lowercase())
                put("score", hit.score)
                put("matches", hit.matches.toJson())
                putJsonArray("parts") {
                    hit.parts.forEach { found ->
                        addJsonObject {
                            put("range", outlineJson.encodeToJsonElement(found.part.range))
                            put("name", found.part.name)
                            put("nameMatches", found.nameMatches.toJson())
                            put("description", found.part.description)
                            put("descriptionMatches", found.descriptionMatches.toJson())
                        }
                    }
                }
                hit.outline?.let { put("outline", it.toJson()) }
                put("modified", hit.modified?.toJson())
            }
        }
    }
}

private fun ListResult.Directory.toJson() = buildJsonObject {
    put("path", path.value)
    put("description", description)
    putJsonArray("entries") {
        entries.forEach { entry ->
            addJsonObject {
                put("name", entry.name)
                put("path", entry.path.value)
                put("type", entry.type.name.lowercase())
                when (entry.type) {
                    EntryType.FILE -> {
                        entry.outline?.let { put("outline", it.toJson()) }
                        put("modified", entry.modified?.toJson())
                    }
                    EntryType.DIRECTORY -> put("description", entry.description)
                }
            }
        }
    }
}

// Leaves out language-specific fields a file doesn't have (they default to null).
private val outlineJson = Json { encodeDefaults = false }

private fun Outline.toJson() = outlineJson.encodeToJsonElement(this)

private fun List<Match>.toJson() = buildJsonArray {
    forEach { match ->
        addJsonObject {
            put("start", match.start)
            put("end", match.end)
        }
    }
}

/** ISO 8601 in UTC, to the second, as git records times. */
private fun Instant.toJson() = truncatedTo(ChronoUnit.SECONDS).toString()

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonObject) =
    respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) =
    respondJson(status, buildJsonObject {
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    })
