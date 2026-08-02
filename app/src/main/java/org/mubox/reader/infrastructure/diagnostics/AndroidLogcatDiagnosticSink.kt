package org.mubox.reader.infrastructure.diagnostics

import android.content.Context
import android.util.Log
import org.mubox.reader.core.diagnostics.CompositeDiagnosticSink
import org.mubox.reader.core.diagnostics.DiagnosticSeverity
import org.mubox.reader.core.diagnostics.DiagnosticSink
import org.mubox.reader.core.diagnostics.ExceptionDiagnostics
import java.io.File

internal fun createAppDiagnostics(context: Context): ExceptionDiagnostics =
    ExceptionDiagnostics(
        sink = CompositeDiagnosticSink(
            AndroidLogcatDiagnosticSink(),
            RotatingFileDiagnosticSink(
                file = File(context.filesDir, "diagnostics/exceptions.log"),
                failureReporter = { event, error ->
                    if (error == null) Log.e("MuBOX", event) else Log.e("MuBOX", event, error)
                },
            ),
        ),
    )

class AndroidLogcatDiagnosticSink(
    private val tag: String = "MuBOX",
) : DiagnosticSink {
    override fun log(severity: DiagnosticSeverity, line: String) {
        when (severity) {
            DiagnosticSeverity.ERROR -> Log.e(tag, line)
            DiagnosticSeverity.FATAL -> Log.wtf(tag, line)
        }
    }

    override fun logBlocking(severity: DiagnosticSeverity, line: String) {
        log(severity, line)
    }
}
