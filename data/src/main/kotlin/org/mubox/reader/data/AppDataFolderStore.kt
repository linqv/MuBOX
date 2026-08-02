package org.mubox.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AppDataFolderStore(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun saveFolderUri(uri: String) {
        dataStore.edit { preferences ->
            preferences[SELECTED_DATA_FOLDER_URI] = uri
        }
    }

    suspend fun loadFolderUri(): String? {
        return dataStore.data
            .map { preferences -> preferences[SELECTED_DATA_FOLDER_URI] }
            .first()
    }

    private companion object {
        val SELECTED_DATA_FOLDER_URI = stringPreferencesKey("selected_data_folder_uri")
    }
}
