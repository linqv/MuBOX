package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingProgressStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun savesAndLoadsPageByComicKey() = runTest {
        val store = ReadingProgressStore(dataStore("reading-progress.preferences_pb"))

        store.savePage("comic-key", 7)

        assertEquals(7, store.loadPage("comic-key"))
    }

    @Test
    fun deleteAndClearRemoveStoredComicProgress() = runTest {
        val store = ReadingProgressStore(dataStore("reading-progress-clear.preferences_pb"))
        store.savePage("comic-1", 3)
        store.savePage("comic-2", 8)

        store.deletePage("comic-1")
        assertEquals(0, store.loadPage("comic-1"))
        assertEquals(8, store.loadPage("comic-2"))

        store.clear()
        assertEquals(0, store.loadPage("comic-2"))
    }

    @Test
    fun returnsNullWhenFolderUriHasNotBeenSaved() = runTest {
        val store = AppDataFolderStore(dataStore("app-data-folder-empty.preferences_pb"))

        assertNull(store.loadFolderUri())
    }

    @Test
    fun savesAndLoadsFolderUri() = runTest {
        val store = AppDataFolderStore(dataStore("app-data-folder.preferences_pb"))

        store.saveFolderUri("content://com.example.tree/comics")

        assertEquals("content://com.example.tree/comics", store.loadFolderUri())
    }

    private fun TestScope.dataStore(fileName: String): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temp.newFile(fileName) },
        )
    }
}
