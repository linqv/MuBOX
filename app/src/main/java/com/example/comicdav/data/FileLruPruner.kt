package com.example.comicdav.data

import java.io.File

object FileLruPruner {
    private val stateLock = Any()
    private val directoryStates = mutableMapOf<String, DirectoryState>()

    fun prune(
        root: File,
        maxBytes: Long,
        protectedFiles: Set<File> = emptySet(),
    ): Int {
        val rootKey = canonicalOrAbsolute(root).absolutePath
        if (maxBytes <= 0L || !root.exists()) {
            synchronized(stateLock) {
                directoryStates.remove(rootKey)
            }
            return 0
        }
        val protectedCanonical = protectedFiles.mapNotNull { file ->
            runCatching { file.canonicalFile.absolutePath }.getOrNull()
        }.toSet()

        return synchronized(stateLock) {
            val state = if (protectedFiles.isEmpty()) {
                scan(root).also { directoryStates[rootKey] = it }
            } else {
                directoryStates.getOrPut(rootKey) { scan(root) }
                    .also { it.syncKnownFiles(protectedFiles) }
            }
            state.pruneTo(maxBytes, protectedCanonical)
        }
    }

    private fun scan(root: File): DirectoryState {
        val rootCanonical = canonicalOrAbsolute(root)
        val files = root
            .walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file -> cachedFileFor(file, rootCanonical) }
            .associateByTo(linkedMapOf()) { cached -> cached.canonicalPath }
        return DirectoryState(rootCanonical, files)
    }

    private fun cachedFileFor(file: File, rootCanonical: File): CachedFile? {
        val canonicalFile = canonicalOrAbsolute(file)
        if (!canonicalFile.isInside(rootCanonical) || !file.isFile) return null
        return CachedFile(
            file = file,
            canonicalPath = canonicalFile.absolutePath,
            bytes = file.length(),
            modified = file.lastModified(),
        )
    }

    private fun canonicalOrAbsolute(file: File): File =
        runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

    private fun File.isInside(root: File): Boolean {
        val rootPath = root.absolutePath
        val path = absolutePath
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    private class DirectoryState(
        private val rootCanonical: File,
        private val files: MutableMap<String, CachedFile>,
    ) {
        private var totalBytes: Long = files.values.sumOf { it.bytes }

        fun syncKnownFiles(knownFiles: Set<File>) {
            knownFiles.forEach { file ->
                if (file.isFile) {
                    upsert(file)
                } else {
                    remove(canonicalOrAbsolute(file).absolutePath)
                }
            }
        }

        fun pruneTo(maxBytes: Long, protectedCanonical: Set<String>): Int {
            var removed = 0
            val candidates = files.values
                .filterNot { cached -> cached.canonicalPath in protectedCanonical }
                .sortedWith(compareBy<CachedFile> { it.modified }.thenBy { it.file.absolutePath })

            for (cached in candidates) {
                if (totalBytes <= maxBytes) break
                val refreshed = refresh(cached) ?: continue
                if (refreshed.canonicalPath in protectedCanonical) continue
                if (refreshed.file.delete()) {
                    remove(refreshed.canonicalPath)
                    removed += 1
                }
            }
            return removed
        }

        private fun upsert(file: File): CachedFile? {
            val cached = cachedFileFor(file, rootCanonical) ?: return null
            val previous = files.put(cached.canonicalPath, cached)
            totalBytes = (totalBytes - (previous?.bytes ?: 0L) + cached.bytes).coerceAtLeast(0L)
            return cached
        }

        private fun refresh(cached: CachedFile): CachedFile? {
            if (!cached.file.isFile) {
                remove(cached.canonicalPath)
                return null
            }
            val refreshed = cachedFileFor(cached.file, rootCanonical)
            if (refreshed == null) {
                remove(cached.canonicalPath)
                return null
            }
            if (refreshed.canonicalPath != cached.canonicalPath) {
                remove(cached.canonicalPath)
            }
            val previous = files.put(refreshed.canonicalPath, refreshed)
            totalBytes = (totalBytes - (previous?.bytes ?: 0L) + refreshed.bytes).coerceAtLeast(0L)
            return refreshed
        }

        private fun remove(canonicalPath: String) {
            val removed = files.remove(canonicalPath) ?: return
            totalBytes = (totalBytes - removed.bytes).coerceAtLeast(0L)
        }
    }

    private data class CachedFile(
        val file: File,
        val canonicalPath: String,
        val bytes: Long,
        val modified: Long,
    )
}
