package org.mubox.reader.feature.webdav

import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.core.model.media.MediaKind
import org.mubox.reader.core.model.media.isBrowsableInSources
import org.mubox.reader.core.model.media.mediaKindFor

val WebDavItem.mediaKind: MediaKind
    get() = mediaKindFor(name = name, isDirectory = isDirectory)

internal fun filterBrowsableWebDavItems(items: List<WebDavItem>): List<WebDavItem> =
    items.filter { it.mediaKind.isBrowsableInSources }

fun shouldShowWebDavAccountForm(
    isAddingWebDavPath: Boolean,
    editingWebDavSourceId: Long?,
    webDavStatus: String,
): Boolean =
    webDavStatus != WEB_DAV_STATUS_CONNECTED && (isAddingWebDavPath || editingWebDavSourceId != null)
