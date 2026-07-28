package com.example.comicdav.core.model.format

import java.util.Locale

fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var unitIndex = 0
    var value = bytes.toDouble()
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${bytes} B"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
