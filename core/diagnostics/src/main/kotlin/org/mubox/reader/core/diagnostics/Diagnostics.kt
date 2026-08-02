package org.mubox.reader.core.diagnostics

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

enum class DiagnosticCategory {
    APPLICATION,
    SESSION,
    WEBDAV_NETWORK,
    VIDEO,
    STORAGE,
}

enum class DiagnosticSeverity(val wireName: String) {
    ERROR("error"),
    FATAL("fatal"),
}

interface DiagnosticSink {
    fun log(severity: DiagnosticSeverity, line: String)

    fun logBlocking(severity: DiagnosticSeverity, line: String) {
        log(severity, line)
    }
}

object NoopDiagnosticSink : DiagnosticSink {
    override fun log(severity: DiagnosticSeverity, line: String) = Unit
}

class CompositeDiagnosticSink(
    private vararg val sinks: DiagnosticSink,
) : DiagnosticSink {
    override fun log(severity: DiagnosticSeverity, line: String) {
        sinks.forEach { sink -> runCatching { sink.log(severity, line) } }
    }

    override fun logBlocking(severity: DiagnosticSeverity, line: String) {
        sinks.forEach { sink -> runCatching { sink.logBlocking(severity, line) } }
    }
}

interface Diagnostics {
    fun error(category: DiagnosticCategory, event: String, error: Throwable? = null)
    fun fatal(category: DiagnosticCategory, event: String, error: Throwable)
    fun fatalBlocking(category: DiagnosticCategory, event: String, error: Throwable)

    fun error(event: String, error: Throwable? = null) {
        error(DiagnosticCategory.APPLICATION, event, error)
    }

    fun fatal(event: String, error: Throwable) {
        fatal(DiagnosticCategory.APPLICATION, event, error)
    }

    fun fatalBlocking(event: String, error: Throwable) {
        fatalBlocking(DiagnosticCategory.APPLICATION, event, error)
    }
}

object NoopDiagnostics : Diagnostics {
    override fun error(category: DiagnosticCategory, event: String, error: Throwable?) = Unit
    override fun fatal(category: DiagnosticCategory, event: String, error: Throwable) = Unit
    override fun fatalBlocking(category: DiagnosticCategory, event: String, error: Throwable) = Unit
}

/** Process-scoped exception recorder. Sink failures must never affect app behavior. */
class ExceptionDiagnostics(
    private val sink: DiagnosticSink = NoopDiagnosticSink,
) : Diagnostics {
    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun error(category: DiagnosticCategory, event: String, error: Throwable?) {
        write(DiagnosticSeverity.ERROR, category, event, error, blocking = false)
    }

    override fun fatal(category: DiagnosticCategory, event: String, error: Throwable) {
        write(DiagnosticSeverity.FATAL, category, event, error, blocking = false)
    }

    override fun fatalBlocking(category: DiagnosticCategory, event: String, error: Throwable) {
        write(DiagnosticSeverity.FATAL, category, event, error, blocking = true)
    }

    private fun write(
        severity: DiagnosticSeverity,
        category: DiagnosticCategory,
        event: String,
        error: Throwable?,
        blocking: Boolean,
    ) {
        if (!enabled) return

        val payload = buildString {
            append("level=")
            append(severity.wireName)
            append(" category=")
            append(category.name)
            append(" thread=")
            append(Thread.currentThread().name)
            append(' ')
            append(formatDiagnosticThrowable(event, error))
        }
        val line = formatDiagnosticLine(redactDiagnosticText(payload))
        runCatching {
            if (blocking) sink.logBlocking(severity, line) else sink.log(severity, line)
        }
    }
}

fun formatDiagnosticLine(event: String, now: () -> String = { Instant.now().toString() }): String =
    "${now()} $event"

fun formatDiagnosticThrowable(event: String, error: Throwable?): String =
    if (error == null) {
        event
    } else {
        "$event exception=${error.javaClass.name} message=${error.message.orEmpty()}\n${error.stackTraceToString()}"
    }

fun redactDiagnosticText(text: String): String {
    var redacted = text
    redacted = BASIC_AUTH_URL.replace(redacted) { match ->
        "${match.groupValues[1]}<redacted>@"
    }
    redacted = URL_RESOURCE.replace(redacted, "$1/<redacted>")
    redacted = ABSOLUTE_FILE_PATH.replace(redacted, "<redacted-path>")
    redacted = BEARER_TOKEN.replace(redacted, "$1<redacted>")
    redacted = SECRET_TOKEN.replace(redacted) { match ->
        "${match.groupValues[1]}=<redacted>"
    }
    redacted = replaceToken(redacted, "folderUri", "uriId=folder")
    redacted = replaceToken(redacted, "uri", "uriId=local")
    redacted = replaceToken(redacted, "path", "pathId=path")
    redacted = replaceFileNameToken(redacted)
    return redacted
}

fun diagnosticId(prefix: String, raw: String): String = "$prefix:${shortHash(raw)}"

private fun replaceToken(text: String, token: String, replacementName: String): String {
    val regex = Regex("""\b$token=(.+?)(?=\s+\w+=|\s+at\s|\r?\n|$)""")
    return regex.replace(text) { match ->
        "$replacementName:${shortHash(match.groupValues[1].trim())}"
    }
}

private fun replaceFileNameToken(text: String): String {
    val regex = Regex("""\bfileName=(.+?)(?=\s+\w+=|\s+at\s|\r?\n|$)""")
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

private val BASIC_AUTH_URL = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^\s/@:]+:[^\s/@]+@""")
private val URL_RESOURCE = Regex(
    """(?i)\b([a-z][a-z0-9+.-]*://(?:<redacted>@)?[^/\s?#]+)(?:/[^\s?#]*|\?[^\s#]*)""",
)
private val ABSOLUTE_FILE_PATH = Regex(
    """(?<![:\w])/(?:[^\s/]+/)*[^\s/]+\.[A-Za-z0-9]{1,12}(?=[:?\s]|$)""",
)
private val BEARER_TOKEN = Regex("""(?i)(\bBearer\s+)[^\s]+""")
private val SECRET_TOKEN = Regex(
    """(?i)\b(password|passwd|token|access[_-]?token|refresh[_-]?token|secret|api[_-]?key|key|signature|sig|credential|authorization)=([^\s&]+)""",
)
