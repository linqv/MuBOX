package com.example.comicdav.infrastructure.diagnostics

import com.example.comicdav.core.diagnostics.DiagnosticSeverity
import com.example.comicdav.core.diagnostics.DiagnosticSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Small process-local exception log with one previous generation retained. */
class RotatingFileDiagnosticSink(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val failureReporter: (String, Throwable?) -> Unit = ::reportFileSinkFailure,
    private val moveFile: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
) : DiagnosticSink {
    private val lock = Any()

    init {
        require(maxBytes > 0L) { "maxBytes must be positive" }
    }

    override fun log(severity: DiagnosticSeverity, line: String) {
        append(line, forceSync = false)
    }

    override fun logBlocking(severity: DiagnosticSeverity, line: String) {
        append(line, forceSync = true)
    }

    private fun append(line: String, forceSync: Boolean) {
        synchronized(lock) {
            try {
                ensureParentDirectory()
                val bytes = boundedLineBytes(line)
                var appendToCurrent = true
                if (file.isFile && file.length() > maxBytes - bytes.size) {
                    appendToCurrent = rotate()
                }
                FileOutputStream(file, appendToCurrent).use { output ->
                    output.write(bytes)
                    output.flush()
                    if (forceSync) output.fd.sync()
                }
            } catch (error: Throwable) {
                reportFailure("exception_log_write_failed", error)
            }
        }
    }

    /** Returns false when the active file must be replaced instead of appended. */
    private fun rotate(): Boolean {
        val previous = File(file.parentFile, "${file.nameWithoutExtension}.previous.${file.extension}")
        return try {
            if (previous.exists() && !previous.delete()) {
                reportFailure("exception_log_rotation_delete_failed", null)
                false
            } else if (!moveFile(file, previous)) {
                reportFailure("exception_log_rotation_move_failed", null)
                false
            } else {
                true
            }
        } catch (error: Throwable) {
            reportFailure("exception_log_rotation_failed", error)
            false
        }
    }

    private fun ensureParentDirectory() {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create exception log directory")
        }
    }

    private fun boundedLineBytes(line: String): ByteArray {
        val fullLine = "$line\n".toByteArray(Charsets.UTF_8)
        if (fullLine.size.toLong() <= maxBytes) return fullLine

        val byteLimit = maxBytes.toInt()
        val marker = "<truncated>\n".toByteArray(Charsets.UTF_8)
        if (byteLimit <= marker.size) return marker.copyOf(byteLimit)

        var low = 0
        var high = line.length
        var best = marker
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val candidate = (line.substring(0, middle) + "<truncated>\n").toByteArray(Charsets.UTF_8)
            if (candidate.size <= byteLimit) {
                best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun reportFailure(event: String, error: Throwable?) {
        runCatching { failureReporter(event, error) }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024L
    }
}

private fun reportFileSinkFailure(event: String, error: Throwable?) {
    val suffix = error?.let { " ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
    System.err.println("$event$suffix")
}
