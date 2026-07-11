package com.example.comicdav.feature.directorylisting

import java.util.Locale

enum class DirectorySortField {
    NAME,
    SIZE,
    TYPE,
}

enum class DirectorySortDirection {
    ASCENDING,
    DESCENDING,
}

fun DirectorySortDirection.opposite(): DirectorySortDirection = when (this) {
    DirectorySortDirection.ASCENDING -> DirectorySortDirection.DESCENDING
    DirectorySortDirection.DESCENDING -> DirectorySortDirection.ASCENDING
}

internal fun <T> filterAndSortDirectoryEntries(
    entries: List<T>,
    query: String,
    sortField: DirectorySortField,
    sortDirection: DirectorySortDirection,
    nameOf: (T) -> String,
    isDirectory: (T) -> Boolean,
    sizeOf: (T) -> Long?,
): List<T> {
    val normalizedQuery = query.trim()
    return entries
        .filter { normalizedQuery.isBlank() || nameOf(it).contains(normalizedQuery, ignoreCase = true) }
        .sortedWith(
            directoryEntryComparator(
                sortField = sortField,
                sortDirection = sortDirection,
                nameOf = nameOf,
                isDirectory = isDirectory,
                sizeOf = sizeOf,
            ),
        )
}

private fun <T> directoryEntryComparator(
    sortField: DirectorySortField,
    sortDirection: DirectorySortDirection,
    nameOf: (T) -> String,
    isDirectory: (T) -> Boolean,
    sizeOf: (T) -> Long?,
): Comparator<T> = Comparator { left, right ->
    val leftIsDirectory = isDirectory(left)
    val rightIsDirectory = isDirectory(right)
    if (leftIsDirectory != rightIsDirectory) {
        return@Comparator if (leftIsDirectory) -1 else 1
    }

    if (leftIsDirectory) {
        return@Comparator directionalNameCompare(nameOf(left), nameOf(right), sortDirection)
    }

    when (sortField) {
        DirectorySortField.NAME -> directionalNameCompare(nameOf(left), nameOf(right), sortDirection)
        DirectorySortField.SIZE -> {
            val leftSize = sizeOf(left)
            val rightSize = sizeOf(right)
            when {
                leftSize == null && rightSize != null -> 1
                leftSize != null && rightSize == null -> -1
                leftSize != null && rightSize != null -> {
                    directionalCompare(leftSize, rightSize, sortDirection)
                        .takeIf { it != 0 }
                        ?: compareNames(nameOf(left), nameOf(right))
                }
                else -> compareNames(nameOf(left), nameOf(right))
            }
        }
        DirectorySortField.TYPE -> {
            directionalCompare(fileTypeOf(nameOf(left)), fileTypeOf(nameOf(right)), sortDirection)
                .takeIf { it != 0 }
                ?: directionalNameCompare(nameOf(left), nameOf(right), sortDirection)
        }
    }
}

private fun fileTypeOf(name: String): String =
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)

private fun compareNames(left: String, right: String): Int =
    left.lowercase(Locale.ROOT).compareTo(right.lowercase(Locale.ROOT))
        .takeIf { it != 0 }
        ?: left.compareTo(right)

private fun directionalNameCompare(
    left: String,
    right: String,
    direction: DirectorySortDirection,
): Int = when (direction) {
    DirectorySortDirection.ASCENDING -> compareNames(left, right)
    DirectorySortDirection.DESCENDING -> compareNames(right, left)
}

private fun <T : Comparable<T>> directionalCompare(
    left: T,
    right: T,
    direction: DirectorySortDirection,
): Int = when (direction) {
    DirectorySortDirection.ASCENDING -> left.compareTo(right)
    DirectorySortDirection.DESCENDING -> right.compareTo(left)
}
