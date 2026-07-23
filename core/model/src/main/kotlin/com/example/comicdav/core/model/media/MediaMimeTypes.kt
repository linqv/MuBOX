package com.example.comicdav.core.model.media

fun mimeTypeForMediaFileName(fileName: String): String? =
    when (fileName.mediaExtension()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "3gp" -> "video/3gpp"
        "3g2" -> "video/3gpp2"
        "mpg", "mpeg" -> "video/mpeg"
        "ts", "mts", "m2ts" -> "video/mp2t"
        "vob" -> "video/dvd"
        "ogv" -> "video/ogg"
        "rm", "rmvb" -> "application/vnd.rn-realmedia"
        "asf" -> "video/x-ms-asf"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wma" -> "audio/x-ms-wma"
        "srt" -> "application/x-subrip"
        "ass" -> "text/x-ass"
        "ssa" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        "sub" -> "text/plain"
        else -> null
    }
