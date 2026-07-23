package com.example.comicdav.infrastructure.diagnostics

import android.util.Log
import com.example.comicdav.core.diagnostics.DiagnosticSink

class AndroidLogcatDiagnosticSink(
    private val tag: String = "MuBOX",
) : DiagnosticSink {
    override fun log(line: String) {
        Log.d(tag, line)
    }

    override fun logBlocking(line: String) {
        Log.e(tag, line)
    }
}
