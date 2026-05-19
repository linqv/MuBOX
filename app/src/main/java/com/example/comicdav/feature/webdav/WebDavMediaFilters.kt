package com.example.comicdav.feature.webdav

import com.example.comicdav.network.WebDavItem
import com.example.comicdav.video.MediaKind
import com.example.comicdav.video.isBrowsableInSources
import com.example.comicdav.video.mediaKindFor

internal val WebDavItem.mediaKind: MediaKind
    get() = mediaKindFor(name = name, isDirectory = isDirectory)

internal fun filterBrowsableWebDavItems(items: List<WebDavItem>): List<WebDavItem> =
    items.filter { it.mediaKind.isBrowsableInSources }
