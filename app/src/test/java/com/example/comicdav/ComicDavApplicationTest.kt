package com.example.comicdav

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ComicDavApplication::class)
class ComicDavApplicationTest {
    @Test
    fun appContainerIsStableForTheApplicationLifetime() {
        val application = ApplicationProvider.getApplicationContext<ComicDavApplication>()

        assertSame(application.appContainer, application.appContainer)
        assertSame(application.videoPlayerDependencies, application.videoPlayerDependencies)
    }
}
