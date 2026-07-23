package com.example.comicdav.core.diagnostics

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

enum class DiagnosticCategory {
    SESSION,
    LOCAL_FILE,
    PAGE_LOAD,
    IMAGE,
    PREFETCH,
    RANGE_CACHE,
    WEBDAV_NETWORK,
    UI,
}

enum class DiagnosticVerbosity {
    OFF,
    SUMMARY,
    DETAIL,
}

interface DiagnosticSink {
    fun log(line: String)
    fun logBlocking(line: String)
}

object NoopDiagnosticSink : DiagnosticSink {
    override fun log(line: String) = Unit
    override fun logBlocking(line: String) = Unit
}

interface Diagnostics {
    fun summary(category: DiagnosticCategory, event: () -> String)
    fun detail(category: DiagnosticCategory, event: () -> String)
    fun error(category: DiagnosticCategory, event: String, error: Throwable)
    fun errorBlocking(category: DiagnosticCategory, event: String, error: Throwable)

    fun event(event: String) {
        summary(DiagnosticCategory.SESSION) { event }
    }

    fun error(event: String, error: Throwable) {
        error(DiagnosticCategory.SESSION, event, error)
    }

    fun errorBlocking(event: String, error: Throwable) {
        errorBlocking(DiagnosticCategory.SESSION, event, error)
    }
}

object NoopDiagnostics : Diagnostics {
    override fun summary(category: DiagnosticCategory, event: () -> String) = Unit
    override fun detail(category: DiagnosticCategory, event: () -> String) = Unit
    override fun error(category: DiagnosticCategory, event: String, error: Throwable) = Unit
    override fun errorBlocking(category: DiagnosticCategory, event: String, error: Throwable) = Unit
}

/** Process-scoped diagnostics implementation configured by the app composition root. */
class ConfigurableDiagnostics(
    defaultSink: DiagnosticSink = NoopDiagnosticSink,
    initialVerbosity: DiagnosticVerbosity = DiagnosticVerbosity.SUMMARY,
) : Diagnostics {
    @Volatile
    private var baseSink: DiagnosticSink = defaultSink

    @Volatile
    private var additionalSink: DiagnosticSink? = null

    @Volatile
    private var verbosity: DiagnosticVerbosity = initialVerbosity

    fun setDefaultSink(sink: DiagnosticSink) {
        baseSink = sink
    }

    fun setAdditionalSink(sink: DiagnosticSink) {
        additionalSink = sink
    }

    fun clearAdditionalSink() {
        additionalSink = null
    }

    fun setVerbosity(nextVerbosity: DiagnosticVerbosity) {
        verbosity = nextVerbosity
    }

    override fun summary(category: DiagnosticCategory, event: () -> String) {
        if (verbosity == DiagnosticVerbosity.OFF) return
        write(level = "summary", category = category, event = event)
    }

    override fun detail(category: DiagnosticCategory, event: () -> String) {
        if (verbosity != DiagnosticVerbosity.DETAIL) return
        write(level = "detail", category = category, event = event)
    }

    override fun error(category: DiagnosticCategory, event: String, error: Throwable) {
        if (verbosity == DiagnosticVerbosity.OFF) return
        emit(
            line = formatDiagnosticLine(
                "level=error category=${category.name} " +
                    redactDiagnosticText(formatDiagnosticThrowable(event, error)),
            ),
            blocking = false,
        )
    }

    override fun errorBlocking(category: DiagnosticCategory, event: String, error: Throwable) {
        if (verbosity == DiagnosticVerbosity.OFF) return
        emit(
            line = formatDiagnosticLine(
                "level=error category=${category.name} " +
                    redactDiagnosticText(formatDiagnosticThrowable(event, error)),
            ),
            blocking = true,
        )
    }

    private fun write(level: String, category: DiagnosticCategory, event: () -> String) {
        runCatching {
            emit(
                line = formatDiagnosticLine(
                    "level=$level category=${category.name} ${redactDiagnosticText(event())}",
                ),
                blocking = false,
            )
        }
    }

    private fun emit(line: String, blocking: Boolean) {
        val sinks = listOfNotNull(baseSink, additionalSink).distinctBy(System::identityHashCode)
        sinks.forEach { sink ->
            runCatching {
                if (blocking) sink.logBlocking(line) else sink.log(line)
            }
        }
    }
}

fun formatDiagnosticLine(event: String, now: () -> String = { Instant.now().toString() }): String =
    "${now()} $event"

fun formatDiagnosticThrowable(event: String, error: Throwable): String =
    "$event error=${error.javaClass.simpleName}: ${error.message}\n${error.stackTraceToString()}"

fun redactDiagnosticText(text: String): String {
    var redacted = text
    redacted = replaceToken(redacted, "uri", "uriId=local")
    redacted = replaceToken(redacted, "folderUri", "uriId=folder")
    redacted = replaceToken(redacted, "path", "pathId=path")
    redacted = replaceFileNameToken(redacted)
    return redacted
}

fun diagnosticId(prefix: String, raw: String): String = "$prefix:${shortHash(raw)}"

private fun replaceToken(text: String, token: String, replacementName: String): String {
    val regex = Regex("""\b$token=(.+?)(?=\s+\w+=|$)""")
    return regex.replace(text) { match ->
        "$replacementName:${shortHash(match.groupValues[1].trim())}"
    }
}

private fun replaceFileNameToken(text: String): String {
    val regex = Regex("""\bfileName=(.+?)(?=\s+\w+=|$)""")
    return regex.replace(text) { match ->
        val raw = match.groupValues[1].trim()
        val extension = raw.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        buildString {
            append("fileId=file:")
            append(shortHash(raw))
            if (extension.isNotBlank()) {
                append(" fileExt=")
                append(extension)
            }
        }
    }
}

private fun shortHash(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { byte -> "%02x".format(byte) }
}
