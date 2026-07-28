package com.example.comicdav.network

import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.core.diagnostics.NoopDiagnostics
import com.example.comicdav.core.remote.WebDavClient

fun createWebDavClient(
    baseUrl: String,
    username: String?,
    password: String?,
    diagnostics: Diagnostics = NoopDiagnostics,
): WebDavClient =
    OkHttpWebDavClient(
        baseUrl = baseUrl,
        username = username?.takeIf(String::isNotBlank),
        password = password?.takeIf(String::isNotBlank),
        diagnostics = WebDavNetworkDiagnostics(diagnostics),
    )
