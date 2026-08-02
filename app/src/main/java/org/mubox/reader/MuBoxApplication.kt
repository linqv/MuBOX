package org.mubox.reader

import android.app.Application
import org.mubox.reader.core.model.settings.DiagnosticLogLevel
import org.mubox.reader.video.VideoPlaybackMemoryBudget
import org.mubox.reader.infrastructure.diagnostics.createAppDiagnostics
import org.mubox.reader.video.player.VideoPlayerDependencies
import org.mubox.reader.video.player.VideoPlayerDependenciesOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MuBoxApplication : Application(), VideoPlayerDependenciesOwner {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var processCrashLogger: ProcessCrashLogger? = null
    private val diagnostics by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createAppDiagnostics(this)
    }

    internal val appContainer: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this, diagnostics)
    }

    override val videoPlayerDependencies: VideoPlayerDependencies
        get() = appContainer.videoPlayerDependencies

    override fun onCreate() {
        super.onCreate()
        installProcessCrashLogger()
        VideoPlaybackMemoryBudget.configure(this)
        observeDiagnosticLogLevel()
    }

    override fun onTerminate() {
        val installed = processCrashLogger
        if (installed != null && Thread.getDefaultUncaughtExceptionHandler() === installed) {
            Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler)
        }
        processCrashLogger = null
        previousCrashHandler = null
        applicationScope.cancel()
        super.onTerminate()
    }

    private fun installProcessCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val logger = ProcessCrashLogger(diagnostics, previous)
        previousCrashHandler = previous
        processCrashLogger = logger
        Thread.setDefaultUncaughtExceptionHandler(logger)
    }

    private fun observeDiagnosticLogLevel() {
        applicationScope.launch {
            var migrationsStarted = false
            appContainer.appSettingsStore.settings
                .map { settings -> settings.diagnostics.logLevel }
                .distinctUntilChanged()
                .collect { level ->
                    diagnostics.setEnabled(level != DiagnosticLogLevel.OFF)
                    if (!migrationsStarted) {
                        migrationsStarted = true
                        appContainer.startBackgroundMigrations(applicationScope)
                    }
                }
        }
    }
}
