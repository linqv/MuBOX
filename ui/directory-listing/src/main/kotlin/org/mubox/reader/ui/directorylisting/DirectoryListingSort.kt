package org.mubox.reader.ui.directorylisting

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

fun <T> filterAndSortDirectoryEntries(
    entries: List<T>,
    query: String,
    sortField: DirectorySortField,
    sortDirection: DirectorySortDirection,
    nameOf: (T) -> String,
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
                sizeOf = sizeOf,
            ),
        )
}

private fun <T> directoryEntryComparator(
    sortField: DirectorySortField,
    sortDirection: DirectorySortDirection,
    nameOf: (T) -> String,
    sizeOf: (T) -> Long?,
): Comparator<T> = Comparator { left, right ->
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
    naturalCompare(left.lowercase(Locale.ROOT), right.lowercase(Locale.ROOT))
        .takeIf { it != 0 }
        ?: left.compareTo(right)

private fun naturalCompare(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0

    while (leftIndex < left.length && rightIndex < right.length) {
        val leftChar = left[leftIndex]
        val rightChar = right[rightIndex]
        if (!leftChar.isDigit() || !rightChar.isDigit()) {
            val comparison = leftChar.compareTo(rightChar)
            if (comparison != 0) return comparison
            leftIndex += 1
            rightIndex += 1
            continue
        }

        val leftEnd = left.indexAfterDigitRun(leftIndex)
        val rightEnd = right.indexAfterDigitRun(rightIndex)
        val leftSignificantStart = left.indexAfterLeadingZeroes(leftIndex, leftEnd)
        val rightSignificantStart = right.indexAfterLeadingZeroes(rightIndex, rightEnd)
        val leftSignificantLength = leftEnd - leftSignificantStart
        val rightSignificantLength = rightEnd - rightSignificantStart

        if (leftSignificantLength != rightSignificantLength) {
            return leftSignificantLength.compareTo(rightSignificantLength)
        }
        for (offset in 0 until leftSignificantLength) {
            val comparison = left[leftSignificantStart + offset]
                .compareTo(right[rightSignificantStart + offset])
            if (comparison != 0) return comparison
        }

        val leftRunLength = leftEnd - leftIndex
        val rightRunLength = rightEnd - rightIndex
        if (leftRunLength != rightRunLength) {
            return leftRunLength.compareTo(rightRunLength)
        }
        leftIndex = leftEnd
        rightIndex = rightEnd
    }

    return (left.length - leftIndex).compareTo(right.length - rightIndex)
}

private fun String.indexAfterDigitRun(startIndex: Int): Int {
    var index = startIndex
    while (index < length && this[index].isDigit()) index += 1
    return index
}

private fun String.indexAfterLeadingZeroes(startIndex: Int, endIndex: Int): Int {
    var index = startIndex
    while (index < endIndex && this[index] == '0') index += 1
    return index
}

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
