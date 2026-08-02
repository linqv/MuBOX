package org.mubox.reader.video.player

import org.mubox.reader.core.model.media.LocalVideoOpenRequest
import org.mubox.reader.core.model.media.VideoSubtitleOpenRequest
import org.mubox.reader.core.model.media.WebDavSubtitleOpenRequest
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID

enum class VideoEpisodeSource {
    LOCAL,
    WEB_DAV,
}

data class VideoEpisode(
    val localRequest: LocalVideoOpenRequest? = null,
    val webDavRequest: WebDavVideoOpenRequest? = null,
) {
    init {
        require((localRequest == null) != (webDavRequest == null)) {
            "A video episode must contain exactly one playback request"
        }
    }

    val source: VideoEpisodeSource
        get() = if (localRequest != null) VideoEpisodeSource.LOCAL else VideoEpisodeSource.WEB_DAV

    val displayName: String
        get() = localRequest?.displayName ?: requireNotNull(webDavRequest).displayName

    val playbackKey: String
        get() = localRequest?.let { request ->
            localVideoPlaybackKey(
                uri = request.uri,
                size = request.size,
                lastModified = request.lastModified,
            )
        } ?: requireNotNull(webDavRequest).let { request ->
            webDavVideoPlaybackKey(
                accountId = request.accountId,
                remotePath = request.remotePath,
                size = request.size,
                etag = request.etag,
                lastModified = request.lastModified,
            )
        }

    companion object {
        fun local(request: LocalVideoOpenRequest): VideoEpisode = VideoEpisode(localRequest = request)

        fun webDav(request: WebDavVideoOpenRequest): VideoEpisode = VideoEpisode(webDavRequest = request)
    }
}

class VideoEpisodeQueue(
    val episodes: List<VideoEpisode>,
    currentIndex: Int = 0,
) {
    init {
        require(episodes.distinctBy(VideoEpisode::playbackKey).size == episodes.size) {
            "Episode playback keys must be stable and unique"
        }
    }

    val currentIndex: Int = currentIndex.coerceInEpisodes(episodes)

    val currentEpisode: VideoEpisode?
        get() = episodes.getOrNull(currentIndex)

    val hasPrevious: Boolean
        get() = currentIndex > 0 && episodes.isNotEmpty()

    val hasNext: Boolean
        get() = currentIndex < episodes.lastIndex

    fun indexOf(playbackKey: String?): Int =
        playbackKey?.let { key -> episodes.indexOfFirst { it.playbackKey == key } } ?: -1

    fun withCurrentPlaybackKey(playbackKey: String?): VideoEpisodeQueue {
        val matchingIndex = indexOf(playbackKey)
        return if (matchingIndex >= 0) VideoEpisodeQueue(episodes, matchingIndex) else this
    }
}

private fun Int.coerceInEpisodes(episodes: List<VideoEpisode>): Int =
    if (episodes.isEmpty()) 0 else coerceIn(0, episodes.lastIndex)

/** Compact versioned wire format persisted by [VideoEpisodeQueueStore]. */
internal object VideoEpisodeQueueLaunchCodec {
    private const val MAGIC = 0x4D425651
    private const val VERSION = 1
    private const val SOURCE_LOCAL = 0
    private const val SOURCE_WEB_DAV = 1
    private const val MAX_EPISODES = 5_000
    private const val MAX_SUBTITLES_PER_EPISODE = 256
    private const val MAX_STRING_BYTES = 64 * 1024

    fun encode(queue: VideoEpisodeQueue): ByteArray {
        require(queue.episodes.size <= MAX_EPISODES) {
            "Episode queue exceeds the launch contract limit of $MAX_EPISODES"
        }
        val bytes = BoundedByteArrayOutputStream(MAX_EPISODE_QUEUE_PAYLOAD_BYTES)
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(queue.currentIndex)
            output.writeInt(queue.episodes.size)
            queue.episodes.forEach { episode ->
                when (episode.source) {
                    VideoEpisodeSource.LOCAL -> {
                        output.writeByte(SOURCE_LOCAL)
                        output.writeLocalRequest(requireNotNull(episode.localRequest))
                    }

                    VideoEpisodeSource.WEB_DAV -> {
                        output.writeByte(SOURCE_WEB_DAV)
                        output.writeWebDavRequest(requireNotNull(episode.webDavRequest))
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(payload: ByteArray?): VideoEpisodeQueue? {
        if (payload == null || payload.isEmpty()) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC) { "Unknown episode queue payload" }
                require(input.readInt() == VERSION) { "Unsupported episode queue payload version" }
                val currentIndex = input.readInt()
                val episodeCount = input.readCount(MAX_EPISODES, "episode")
                val episodes = List(episodeCount) {
                    when (input.readUnsignedByte()) {
                        SOURCE_LOCAL -> VideoEpisode.local(input.readLocalRequest())
                        SOURCE_WEB_DAV -> VideoEpisode.webDav(input.readWebDavRequest())
                        else -> error("Unknown episode source")
                    }
                }
                require(input.read() == -1) { "Trailing episode queue payload data" }
                VideoEpisodeQueue(episodes = episodes, currentIndex = currentIndex)
            }
        }.getOrNull()
    }

    private fun DataOutputStream.writeLocalRequest(request: LocalVideoOpenRequest) {
        writeWireString(request.uri)
        writeWireString(request.displayName)
        writeNullableLong(request.size)
        writeNullableLong(request.lastModified)
        writeCount(request.subtitles.size, MAX_SUBTITLES_PER_EPISODE, "subtitle")
        request.subtitles.forEach { subtitle ->
            writeWireString(subtitle.uri)
            writeWireString(subtitle.displayName)
        }
    }

    private fun DataInputStream.readLocalRequest(): LocalVideoOpenRequest =
        LocalVideoOpenRequest(
            uri = readWireString(),
            displayName = readWireString(),
            size = readNullableLong(),
            lastModified = readNullableLong(),
            subtitles = List(readCount(MAX_SUBTITLES_PER_EPISODE, "subtitle")) {
                VideoSubtitleOpenRequest(
                    uri = readWireString(),
                    displayName = readWireString(),
                )
            },
        )

    private fun DataOutputStream.writeWebDavRequest(request: WebDavVideoOpenRequest) {
        writeWireString(request.accountId)
        writeWireString(request.remotePath)
        writeWireString(request.displayName)
        writeNullableLong(request.size)
        writeNullableString(request.etag)
        writeNullableLong(request.lastModified)
        writeNullableString(request.mimeType)
        writeCount(request.subtitles.size, MAX_SUBTITLES_PER_EPISODE, "subtitle")
        request.subtitles.forEach { subtitle ->
            writeWireString(subtitle.remotePath)
            writeWireString(subtitle.displayName)
            writeNullableLong(subtitle.size)
            writeNullableString(subtitle.etag)
            writeNullableLong(subtitle.lastModified)
            writeNullableString(subtitle.mimeType)
        }
    }

    private fun DataInputStream.readWebDavRequest(): WebDavVideoOpenRequest =
        WebDavVideoOpenRequest(
            accountId = readWireString(),
            remotePath = readWireString(),
            displayName = readWireString(),
            size = readNullableLong(),
            etag = readNullableString(),
            lastModified = readNullableLong(),
            mimeType = readNullableString(),
            subtitles = List(readCount(MAX_SUBTITLES_PER_EPISODE, "subtitle")) {
                WebDavSubtitleOpenRequest(
                    remotePath = readWireString(),
                    displayName = readWireString(),
                    size = readNullableLong(),
                    etag = readNullableString(),
                    lastModified = readNullableLong(),
                    mimeType = readNullableString(),
                )
            },
        )

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeWireString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readWireString() else null

    private fun DataOutputStream.writeWireString(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_STRING_BYTES) {
            "Episode queue string exceeds $MAX_STRING_BYTES bytes"
        }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readWireString(): String {
        val byteCount = readCount(MAX_STRING_BYTES, "string byte")
        val encoded = ByteArray(byteCount)
        readFully(encoded)
        return encoded.toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeCount(value: Int, maximum: Int, label: String) {
        require(value in 0..maximum) { "$label count exceeds $maximum" }
        writeInt(value)
    }

    private fun DataInputStream.readCount(maximum: Int, label: String): Int =
        readInt().also { value ->
            require(value in 0..maximum) { "Invalid $label count: $value" }
        }
}

/**
 * Persists launch payloads outside Binder while keeping the Intent token process-independent.
 * Reads are deliberately non-consuming because Android may recreate the same Activity repeatedly.
 */
internal object VideoEpisodeQueueStore {
    private const val DIRECTORY_NAME = "video-episode-queues"
    private const val FILE_PREFIX = "queue-"
    private const val FILE_SUFFIX = ".bin"
    private const val TEMP_SUFFIX = ".tmp"
    private const val MAX_ACTIVE_PAYLOADS = 16
    private const val MAX_PAYLOAD_AGE_MS = 7L * 24L * 60L * 60L * 1_000L

    @Synchronized
    fun save(storageRoot: File, queue: VideoEpisodeQueue): String {
        val directory = checkNotNull(trustedPayloadDirectory(storageRoot, create = true)) {
            "Unable to create trusted episode queue payload directory"
        }
        cleanup(directory)

        val payload = VideoEpisodeQueueLaunchCodec.encode(queue)
        val token = UUID.randomUUID().toString()
        val tokenName = token.toPayloadFileName()
        val target = File(directory, tokenName)
        val temporary = File(directory, "$tokenName$TEMP_SUFFIX")
        try {
            temporary.writeBytes(payload)
            check(temporary.renameTo(target)) { "Unable to atomically finalize episode queue payload" }
            target.setLastModified(System.currentTimeMillis())
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        }
        cleanup(directory, protectedFile = target)
        return token
    }

    @Synchronized
    fun read(storageRoot: File, token: String?): VideoEpisodeQueue? {
        val payloadFile = resolvePayloadFile(storageRoot, token) ?: return null
        if (!payloadFile.isFile || payloadFile.length() !in 1L..MAX_EPISODE_QUEUE_PAYLOAD_BYTES.toLong()) {
            payloadFile.delete()
            return null
        }
        val queue = runCatching {
            VideoEpisodeQueueLaunchCodec.decode(payloadFile.readBytes())
        }.getOrNull()
        if (queue == null) {
            payloadFile.delete()
            return null
        }
        payloadFile.setLastModified(System.currentTimeMillis())
        cleanup(requireNotNull(payloadFile.parentFile), protectedFile = payloadFile)
        return queue
    }

    @Synchronized
    internal fun clearForTests(storageRoot: File) {
        trustedPayloadDirectory(storageRoot, create = false)?.deleteRecursively()
    }

    @Synchronized
    internal fun activePayloadCountForTests(storageRoot: File): Int {
        val directory = trustedPayloadDirectory(storageRoot, create = false) ?: return 0
        return directory
            .listFiles()
            .orEmpty()
            .count { it.isPayloadFile() }
    }

    @Synchronized
    internal fun payloadFileForTests(storageRoot: File, token: String?): File? =
        resolvePayloadFile(storageRoot, token)

    private fun resolvePayloadFile(storageRoot: File, token: String?): File? {
        if (token.isNullOrBlank()) return null
        val normalizedToken = runCatching { UUID.fromString(token).toString() }.getOrNull() ?: return null
        if (normalizedToken != token) return null
        val trustedDirectory = trustedPayloadDirectory(storageRoot, create = false) ?: return null
        val requestedFile = File(trustedDirectory, token.toPayloadFileName())
        if (Files.isSymbolicLink(requestedFile.toPath())) return null
        val canonicalFile = runCatching { requestedFile.canonicalFile }.getOrNull() ?: return null
        if (canonicalFile.parentFile != trustedDirectory || !canonicalFile.isPayloadFile()) return null
        return canonicalFile
    }

    private fun trustedPayloadDirectory(storageRoot: File, create: Boolean): File? {
        val trustedRoot = runCatching { storageRoot.canonicalFile }.getOrNull() ?: return null
        val requestedDirectory = File(trustedRoot, DIRECTORY_NAME)
        if (Files.isSymbolicLink(requestedDirectory.toPath())) return null
        if (create && !requestedDirectory.exists() && !requestedDirectory.mkdirs()) return null
        val canonicalDirectory = runCatching { requestedDirectory.canonicalFile }.getOrNull() ?: return null
        if (canonicalDirectory.parentFile != trustedRoot) return null
        return canonicalDirectory
    }

    private fun cleanup(directory: File, protectedFile: File? = null) {
        val now = System.currentTimeMillis()
        directory.listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(TEMP_SUFFIX) -> file.delete()
                !file.isPayloadFile() -> Unit
                now - file.lastModified() > MAX_PAYLOAD_AGE_MS && file != protectedFile -> file.delete()
            }
        }
        directory.listFiles()
            .orEmpty()
            .filter { it.isPayloadFile() }
            .sortedByDescending { it.lastModified() }
            .filterNot { it == protectedFile }
            .drop(if (protectedFile == null) MAX_ACTIVE_PAYLOADS else MAX_ACTIVE_PAYLOADS - 1)
            .forEach { it.delete() }
    }

    private fun File.isPayloadFile(): Boolean =
        isFile && name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    private fun String.toPayloadFileName(): String = "$FILE_PREFIX$this$FILE_SUFFIX"
}

private class BoundedByteArrayOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream(minOf(maximumBytes, 32 * 1024)) {
    override fun write(value: Int) {
        ensureCapacityFor(1)
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        ensureCapacityFor(length)
        super.write(bytes, offset, length)
    }

    private fun ensureCapacityFor(additionalBytes: Int) {
        require(additionalBytes >= 0 && count <= maximumBytes - additionalBytes) {
            "Episode queue launch payload exceeds $maximumBytes bytes"
        }
    }
}

private const val MAX_EPISODE_QUEUE_PAYLOAD_BYTES = 1 * 1024 * 1024
