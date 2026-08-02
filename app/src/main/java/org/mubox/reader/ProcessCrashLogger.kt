package org.mubox.reader

import org.mubox.reader.core.diagnostics.DiagnosticCategory
import org.mubox.reader.core.diagnostics.Diagnostics

internal class ProcessCrashLogger(
    private val diagnostics: Diagnostics,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        diagnostics.fatalBlocking(
            DiagnosticCategory.APPLICATION,
            "uncaught_exception threadName=${thread.name}",
            error,
        )
        if (delegate !== this) {
            delegate?.uncaughtException(thread, error)
        }
    }
}
