package com.example.comicdav.nativebridge

import com.example.comicdav.core.diagnostics.DiagnosticSink

internal class CollectingDiagnosticSink : DiagnosticSink {
    val lines = mutableListOf<String>()

    override fun log(line: String) {
        lines += line
    }

    override fun logBlocking(line: String) {
        lines += line
    }
}
