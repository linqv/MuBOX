package com.example.comicdav

import com.example.comicdav.core.diagnostics.DiagnosticSink
import com.example.comicdav.core.diagnostics.DiagnosticSeverity

class CollectingReaderLogSink : DiagnosticSink {
    val lines = mutableListOf<String>()

    override fun log(severity: DiagnosticSeverity, line: String) {
        lines += line
    }

    override fun logBlocking(severity: DiagnosticSeverity, line: String) {
        lines += line
    }
}
