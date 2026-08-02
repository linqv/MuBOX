package com.example.comicdav

import com.example.comicdav.core.diagnostics.DiagnosticCategory
import com.example.comicdav.core.diagnostics.Diagnostics

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
