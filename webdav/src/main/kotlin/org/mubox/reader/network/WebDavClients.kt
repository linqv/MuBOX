package org.mubox.reader.network

import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.diagnostics.NoopDiagnostics
import org.mubox.reader.core.remote.WebDavClient

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
