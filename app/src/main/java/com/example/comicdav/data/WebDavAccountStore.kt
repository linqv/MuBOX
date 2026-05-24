package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.comicdav.security.CredentialCipher
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
    private val cipher: CredentialCipher? = null,
) {
    suspend fun saveAccount(baseUrl: String, username: String, password: String): SavedWebDavAccount {
        val account = SavedWebDavAccount(
            accountId = accountId(baseUrl, username),
            baseUrl = baseUrl.trim(),
            username = username,
            password = password,
        )
        val suffix = keySuffix(account.accountId)
        val storedPassword = cipher?.encrypt(password) ?: password
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("${PREFIX}_${suffix}_base_url")] = account.baseUrl
            preferences[stringPreferencesKey("${PREFIX}_${suffix}_username")] = account.username
            preferences[stringPreferencesKey("${PREFIX}_${suffix}_password")] = storedPassword
        }
        return account
    }

    suspend fun loadAccount(accountId: String): SavedWebDavAccount? {
        val suffix = keySuffix(accountId)
        val preferences = dataStore.data.first()
        val baseUrl = preferences[stringPreferencesKey("${PREFIX}_${suffix}_base_url")] ?: return null
        val username = preferences[stringPreferencesKey("${PREFIX}_${suffix}_username")] ?: return null
        val storedPassword = preferences[stringPreferencesKey("${PREFIX}_${suffix}_password")] ?: return null
        val password = cipher?.decrypt(storedPassword) ?: storedPassword
        // Lazy migration: if stored value was plaintext (no v1: prefix) and cipher is available, re-encrypt
        if (cipher != null && !storedPassword.startsWith("v1:")) {
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("${PREFIX}_${suffix}_password")] = cipher.encrypt(password)
            }
        }
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
