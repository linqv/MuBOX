package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Base64
import kotlinx.coroutines.flow.first

data class SavedWebDavAccount(
    val accountId: String,
    val baseUrl: String,
    val username: String,
    val password: String,
)

class WebDavAccountStore(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun saveAccount(baseUrl: String, username: String, password: String): SavedWebDavAccount {
        val account = SavedWebDavAccount(
            accountId = accountId(baseUrl, username),
            baseUrl = baseUrl.trim(),
            username = username,
            password = password,
        )
        val suffix = keySuffix(account.accountId)
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("${PREFIX}_${suffix}_base_url")] = account.baseUrl
            preferences[stringPreferencesKey("${PREFIX}_${suffix}_username")] = account.username
            preferences[stringPreferencesKey("${PREFIX}_${suffix}_password")] = account.password
        }
        return account
    }

    suspend fun loadAccount(accountId: String): SavedWebDavAccount? {
        val suffix = keySuffix(accountId)
        val preferences = dataStore.data.first()
        val baseUrl = preferences[stringPreferencesKey("${PREFIX}_${suffix}_base_url")] ?: return null
        val username = preferences[stringPreferencesKey("${PREFIX}_${suffix}_username")] ?: return null
        val password = preferences[stringPreferencesKey("${PREFIX}_${suffix}_password")] ?: return null
        return SavedWebDavAccount(
            accountId = accountId,
            baseUrl = baseUrl,
            username = username,
            password = password,
        )
    }

    companion object {
        fun accountId(baseUrl: String, username: String): String = "${baseUrl.trim()}|$username"

        private const val PREFIX = "webdav_account"

        private fun keySuffix(accountId: String): String {
            return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(accountId.toByteArray(Charsets.UTF_8))
        }
    }
}
