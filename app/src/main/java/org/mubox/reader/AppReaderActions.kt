package org.mubox.reader

import org.mubox.reader.core.model.settings.AppSettings
import org.mubox.reader.data.AppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class AppReaderActionCallbacks(
    val isLandscapeModeEnabled: () -> Boolean,
    val setLandscapeModeEnabled: (Boolean) -> Unit,
    val setLandscapeOrientationLocked: (Boolean) -> Unit,
    val setReaderOpen: (Boolean) -> Unit,
    val setForceMainPortrait: (Boolean) -> Unit,
    val setActionMessage: (String?) -> Unit,
)

internal class AppReaderActions(
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val appSettingsStore: AppSettingsStore,
    private val activityLaunchers: AppActivityLaunchers,
    private val viewModels: AppViewModels,
    private val callbacks: AppReaderActionCallbacks,
) {
    fun changeLandscapeMode(enabled: Boolean) {
        if (
            shouldForcePortraitAfterReaderLandscapeModeChange(
                currentReaderLandscapeModeEnabled = callbacks.isLandscapeModeEnabled(),
                nextReaderLandscapeModeEnabled = enabled,
            )
        ) {
            callbacks.setForceMainPortrait(true)
        } else if (enabled) {
            callbacks.setForceMainPortrait(false)
        }
        callbacks.setLandscapeModeEnabled(enabled)
        if (!enabled) {
            callbacks.setLandscapeOrientationLocked(false)
        }
    }

    fun changeLandscapeOrientationLocked(locked: Boolean) {
        callbacks.setLandscapeOrientationLocked(locked)
    }

    fun cancelLoading() {
        closeReader(event = "reader_open_cancel")
    }

    fun close() {
        closeReader(event = "reader_close")
    }

    fun closeFromNavigation() {
        closeReader(event = "reader_navigation_close")
        callbacks.setActionMessage(null)
    }

    fun updateAutoPageEnabled(enabled: Boolean) {
        scope.launch {
            appSettingsStore.updateReaderSettings { reader ->
                reader.copy(autoPageEnabled = enabled)
            }
        }
    }

    private fun closeReader(event: String) {
        val shouldRestoreMainPortrait = callbacks.isLandscapeModeEnabled()
        viewModels.reader.closeReader()
        callbacks.setLandscapeModeEnabled(readerLandscapeModeAfterReaderClosed())
        callbacks.setLandscapeOrientationLocked(false)
        callbacks.setReaderOpen(false)
        if (shouldRestoreMainPortrait) {
            callbacks.setForceMainPortrait(true)
        }
    }
}
