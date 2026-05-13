package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.example.comicdav.feature.reader.ReadingProgressGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ReadingProgressStore(
    private val dataStore: DataStore<Preferences>,
) : ReadingProgressGateway {
    override suspend fun savePage(comicKey: String, pageIndex: Int) {
        dataStore.edit { preferences ->
            preferences[keyFor(comicKey)] = pageIndex
        }
    }

    override suspend fun loadPage(comicKey: String): Int {
        return dataStore.data
            .map { preferences -> preferences[keyFor(comicKey)] ?: 0 }
            .first()
    }

    private fun keyFor(comicKey: String): Preferences.Key<Int> {
        return intPreferencesKey("page_$comicKey")
    }
}
