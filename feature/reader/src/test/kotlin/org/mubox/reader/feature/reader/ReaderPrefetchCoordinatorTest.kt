package org.mubox.reader.feature.reader

import android.net.NetworkCapabilities
import org.mubox.reader.core.ports.PlannedRemoteRange
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPrefetchCoordinatorTest {
    @Test
    fun regularPagePrefetchUsesExactDesiredWindow() {
        val desired = setOf(4, 5, 6, 7, 8, 9)

        val retained = retainedPagePrefetchWindow(
            pageIndex = 5,
            pageCount = 20,
            forwardPages = 4,
            desiredWindow = desired,
            reason = "viewport",
        )

        assertEquals(desired, retained)
    }

    @Test
    fun continuousPagePrefetchRetainsNearbyInFlightWork() {
        val retained = retainedPagePrefetchWindow(
            pageIndex = 5,
            pageCount = 20,
            forwardPages = 4,
            desiredWindow = setOf(4, 5, 6, 7, 8, 9),
            reason = "continuous_visible",
        )

        assertEquals((3..11).toSet(), retained)
    }

    @Test
    fun wrappedReaderCancellationIsExpectedButUnrelatedFailureIsNot() {
        val wrapped = IllegalStateException(
            "range callback failed",
            CancellationException("range request cancelled"),
        )

        assertTrue(wrapped.isExpectedReaderCancellation())
        assertFalse(IllegalStateException("network disconnected").isExpectedReaderCancellation())
    }

    @Test
    fun nativeSessionClosedMessageIsAnExpectedCancellation() {
        assertTrue(
            IllegalStateException("remote range session closed").isExpectedReaderCancellation(),
        )
    }

    @Test
    fun networkClassPrefersWifiOverCellularAndDefaultsToUnknown() {
        assertEquals(
            NETWORK_CLASS_WIFI,
            networkClassFromTransports(setOf(NetworkCapabilities.TRANSPORT_WIFI)),
        )
        assertEquals(
            NETWORK_CLASS_WIFI,
            networkClassFromTransports(setOf(NetworkCapabilities.TRANSPORT_ETHERNET)),
        )
        assertEquals(
            NETWORK_CLASS_WIFI,
            networkClassFromTransports(
                setOf(
                    NetworkCapabilities.TRANSPORT_CELLULAR,
                    NetworkCapabilities.TRANSPORT_WIFI,
                ),
            ),
        )
        assertEquals(
            NETWORK_CLASS_MOBILE,
            networkClassFromTransports(setOf(NetworkCapabilities.TRANSPORT_CELLULAR)),
        )
        assertEquals(NETWORK_CLASS_UNKNOWN, networkClassFromTransports(emptySet()))
        assertEquals(
            NETWORK_CLASS_UNKNOWN,
            networkClassFromTransports(setOf(NetworkCapabilities.TRANSPORT_BLUETOOTH)),
        )
    }

    private fun plannedRange(
        start: Long,
        endInclusive: Long,
        pages: List<Int>,
        priority: Int,
    ): PlannedRemoteRange = PlannedRemoteRange(
        start = start,
        endInclusive = endInclusive,
        pages = pages,
        priority = priority,
    )
}
