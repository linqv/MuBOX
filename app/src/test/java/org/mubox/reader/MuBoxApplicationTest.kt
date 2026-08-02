package org.mubox.reader

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = MuBoxApplication::class)
class MuBoxApplicationTest {
    @Test
    fun appContainerIsStableForTheApplicationLifetime() {
        val application = ApplicationProvider.getApplicationContext<MuBoxApplication>()

        assertSame(application.appContainer, application.appContainer)
        assertSame(application.videoPlayerDependencies, application.videoPlayerDependencies)
    }
}
