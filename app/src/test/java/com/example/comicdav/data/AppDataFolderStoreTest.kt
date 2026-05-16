package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppDataFolderStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

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

    @Test
    fun savesFolderUriWithStablePreferenceKey() = runTest {
        val dataStore = dataStore("app-data-folder-key.preferences_pb")
        val store = AppDataFolderStore(dataStore)

        store.saveFolderUri("content://com.example.tree/library")

        assertEquals(
            "content://com.example.tree/library",
            dataStore.data.first()[stringPreferencesKey("selected_data_folder_uri")],
        )
    }

    private fun TestScope.dataStore(fileName: String): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temp.newFile(fileName) },
        )
    }
}
