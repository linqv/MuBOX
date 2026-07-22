package com.example.comicdav

import android.app.Application
import com.example.comicdav.video.VideoPlaybackMemoryBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ComicDavApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val appContainer: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        VideoPlaybackMemoryBudget.configure(this)
        appContainer.startBackgroundMigrations(applicationScope)
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
