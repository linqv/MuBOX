package com.example.comicdav

import com.example.comicdav.core.remote.WebDavClientFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AppWebDavPlaybackClientFactoriesTest {
    @Test
    fun activeFactoryIsAvailableUntilTheAccountIsPersisted() = runTest {
        val activeFactory = factory()
        val factories = AppWebDavPlaybackClientFactories(loadSavedFactory = { null })

        factories.remember("active", activeFactory)

        assertSame(activeFactory, factories.load("active"))
        assertNull(factories.load("other"))
    }

    @Test
    fun savedFactoryTakesPriorityOverTheActiveFallback() = runTest {
        val activeFactory = factory()
        val savedFactory = factory()
        val factories = AppWebDavPlaybackClientFactories(
            loadSavedFactory = { accountId -> savedFactory.takeIf { accountId == "active" } },
        )

        factories.remember("active", activeFactory)

        assertSame(savedFactory, factories.load("active"))
    }

    @Test
    fun rememberingAnotherConnectionReplacesThePreviousFallback() = runTest {
        val previousFactory = factory()
        val latestFactory = factory()
        val factories = AppWebDavPlaybackClientFactories(loadSavedFactory = { null })

        factories.remember("previous", previousFactory)
        factories.remember("latest", latestFactory)

        assertNull(factories.load("previous"))
        assertSame(latestFactory, factories.load("latest"))
    }

    private fun factory(): WebDavClientFactory =
        WebDavClientFactory { error("Factory invocation is not needed by this test") }
}
