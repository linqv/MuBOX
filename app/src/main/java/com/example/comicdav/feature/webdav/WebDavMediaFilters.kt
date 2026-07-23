package com.example.comicdav.feature.webdav

import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.core.model.media.isBrowsableInSources
import com.example.comicdav.core.model.media.mediaKindFor

internal val WebDavItem.mediaKind: MediaKind
    get() = mediaKindFor(name = name, isDirectory = isDirectory)

internal fun filterBrowsableWebDavItems(items: List<WebDavItem>): List<WebDavItem> =
    items.filter { it.mediaKind.isBrowsableInSources }
