package org.mubox.reader.data.history

import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.WatchSourceType
import org.mubox.reader.core.model.history.decodePercentEncodedMediaTitle
import org.mubox.reader.core.ports.WatchHistoryGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class WatchHistoryRepository(
    private val dao: WatchHistoryDao,
) : WatchHistoryGateway {
    override val history: Flow<List<WatchHistoryEntry>> =
        dao.observeAll().map { entries -> entries.mapNotNull(WatchHistoryEntity::toModelOrNull) }

    override suspend fun get(mediaKey: String): WatchHistoryEntry? =
        dao.find(mediaKey)?.toModelOrNull()

    override suspend fun upsert(entry: WatchHistoryEntry) {
        dao.upsert(entry.toEntity())
    }

    override suspend fun delete(mediaKey: String) {
        dao.delete(mediaKey)
    }

    override suspend fun clear() {
        dao.clear()
    }

    override suspend fun prune(
        retentionDays: Int,
        maxRecords: Int,
        nowMillis: Long,
    ): List<WatchHistoryEntry> {
        val all = dao.loadAll().mapNotNull(WatchHistoryEntity::toModelOrNull)
        val cutoff = retentionDays
            .takeIf { it > 0 }
            ?.let { days -> nowMillis - days.toLong() * MILLIS_PER_DAY }
        val retainedByAge = if (cutoff == null) all else all.filter { it.lastWatchedAt >= cutoff }
        val keep = retainedByAge.take(maxRecords.coerceAtLeast(1)).mapTo(mutableSetOf()) { it.mediaKey }
        val removed = all.filterNot { it.mediaKey in keep }
        if (removed.isNotEmpty()) {
            dao.delete(removed.map(WatchHistoryEntry::mediaKey))
        }
        return removed
    }
}

private fun WatchHistoryEntry.toEntity(): WatchHistoryEntity =
    WatchHistoryEntity(
        mediaKey = mediaKey,
        mediaType = mediaType.name,
        title = decodePercentEncodedMediaTitle(title),
        sourceType = sourceType.name,
        sourceLocator = sourceLocator,
        accountId = accountId,
        size = size,
        etag = etag,
        lastModified = lastModified,
        progress = progress,
        total = total,
        lastWatchedAt = lastWatchedAt,
    )

private fun WatchHistoryEntity.toModelOrNull(): WatchHistoryEntry? {
    val parsedMediaType = runCatching { WatchMediaType.valueOf(mediaType) }.getOrNull() ?: return null
    val parsedSourceType = runCatching { WatchSourceType.valueOf(sourceType) }.getOrNull() ?: return null
    return WatchHistoryEntry(
        mediaKey = mediaKey,
        mediaType = parsedMediaType,
        title = decodePercentEncodedMediaTitle(title),
        sourceType = parsedSourceType,
        sourceLocator = sourceLocator,
        accountId = accountId,
        size = size,
        etag = etag,
        lastModified = lastModified,
        progress = progress.coerceAtLeast(0L),
        total = total.coerceAtLeast(0L),
        lastWatchedAt = lastWatchedAt,
    )
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
