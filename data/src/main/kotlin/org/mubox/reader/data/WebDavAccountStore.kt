package org.mubox.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.mubox.reader.security.CredentialCipher
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class SavedWebDavAccount(
    val accountId: String,
    val baseUrl: String,
    val username: String,
    val password: String,
)

class WebDavAccountStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: CredentialCipher? = null,
    private val credentialDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun saveAccount(baseUrl: String, username: String, password: String): SavedWebDavAccount {
        val normalizedBaseUrl = baseUrl.trim()
        return saveAccountForId(
            accountId = accountId(normalizedBaseUrl, username),
            baseUrl = normalizedBaseUrl,
            username = username,
            password = password,
        )
    }

    internal suspend fun saveAccountForId(
        accountId: String,
        baseUrl: String,
        username: String,
        password: String,
    ): SavedWebDavAccount {
        val account = SavedWebDavAccount(
            accountId = accountId,
            baseUrl = baseUrl.trim(),
            username = username,
            password = password,
        )
        val suffix = keySuffix(account.accountId)
        val storedPassword = encryptPassword(password)
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("${PREFIX}_${suffix}_base_url")] = account.baseUrl
            preferences[stringPreferencesKey("${PREFIX}_${suffix}_username")] = account.username
            preferences[stringPreferencesKey("${PREFIX}_${suffix}_password")] = storedPassword
        }
        return account
    }

    internal suspend fun ensureAccountForId(
        accountId: String,
        baseUrl: String,
        username: String,
        password: String,
    ): SavedWebDavAccount {
        loadAccount(accountId)?.let { return it }

        val candidate = SavedWebDavAccount(
            accountId = accountId,
            baseUrl = baseUrl.trim(),
            username = username,
            password = password,
        )
        val suffix = keySuffix(accountId)
        val baseUrlKey = stringPreferencesKey("${PREFIX}_${suffix}_base_url")
        val usernameKey = stringPreferencesKey("${PREFIX}_${suffix}_username")
        val passwordKey = stringPreferencesKey("${PREFIX}_${suffix}_password")
        val storedPassword = encryptPassword(password)
        dataStore.edit { preferences ->
            val completeAccountAlreadyExists = preferences[baseUrlKey] != null &&
                preferences[usernameKey] != null &&
                preferences[passwordKey] != null
            if (!completeAccountAlreadyExists) {
                preferences[baseUrlKey] = candidate.baseUrl
                preferences[usernameKey] = candidate.username
                preferences[passwordKey] = storedPassword
            }
        }
        return checkNotNull(loadAccount(accountId)) {
            "WebDAV account was not available after migration"
        }
    }

    suspend fun loadAccount(accountId: String): SavedWebDavAccount? {
        val suffix = keySuffix(accountId)
        val preferences = dataStore.data.first()
        val baseUrl = preferences[stringPreferencesKey("${PREFIX}_${suffix}_base_url")] ?: return null
        val username = preferences[stringPreferencesKey("${PREFIX}_${suffix}_username")] ?: return null
        val storedPassword = preferences[stringPreferencesKey("${PREFIX}_${suffix}_password")] ?: return null
        val password = decryptPassword(storedPassword)
        return SavedWebDavAccount(
            accountId = accountId,
            baseUrl = baseUrl,
            username = username,
            password = password,
        )
    }

    suspend fun migratePlaintextPasswords() {
        if (cipher == null) return
        val preferences = dataStore.data.first()
        val valuesToMigrate = preferences.asMap().keys
            .filter { it.name.endsWith("_password") && it.name.startsWith(PREFIX) }
            .mapNotNull { key -> (preferences[key] as? String)?.let { key to it } }
            .filter { (_, value) -> !value.startsWith("v1:") }
        if (valuesToMigrate.isEmpty()) return
        val encryptedValues = withContext(credentialDispatcher) {
            valuesToMigrate.map { (key, plaintext) ->
                Triple(key, plaintext, cipher.encrypt(plaintext))
            }
        }
        dataStore.edit { prefs ->
            for ((key, expectedPlaintext, encrypted) in encryptedValues) {
                @Suppress("UNCHECKED_CAST")
                val stringKey = key as Preferences.Key<String>
                if (prefs[stringKey] != expectedPlaintext) continue
                prefs[stringKey] = encrypted
            }
        }
    }

    private suspend fun encryptPassword(password: String): String {
        val credentialCipher = cipher ?: return password
        return withContext(credentialDispatcher) {
            credentialCipher.encrypt(password)
        }
    }

    private suspend fun decryptPassword(storedPassword: String): String {
        val credentialCipher = cipher ?: return storedPassword
        return withContext(credentialDispatcher) {
            credentialCipher.decrypt(storedPassword)
        }
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
