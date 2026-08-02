package org.mubox.reader.data.filedirectory

import org.mubox.reader.data.WebDavAccountStore
import org.mubox.reader.security.CredentialCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FileDirectoryCredentialMigrationResult(
    val migratedSourceIds: List<Long>,
    val failedSourceIds: List<Long>,
) {
    val isComplete: Boolean
        get() = failedSourceIds.isEmpty()
}

/**
 * Moves the credential columns from old directory rows into the application-wide account store.
 * A row is cleared only after its account has been saved, so rerunning this migration safely retries
 * interrupted or failed rows.
 */
class FileDirectoryCredentialMigrator internal constructor(
    private val dao: FileDirectoryDao,
    private val accountStore: WebDavAccountStore,
    private val cipher: CredentialCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun migrateLegacyCredentials(): FileDirectoryCredentialMigrationResult = withContext(ioDispatcher) {
        val migratedSourceIds = mutableListOf<Long>()
        val failedSourceIds = mutableListOf<Long>()

        for (source in dao.getSourcesWithLegacyCredentials()) {
            try {
                migrateSource(source)
                migratedSourceIds += source.id
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedSourceIds += source.id
            }
        }

        FileDirectoryCredentialMigrationResult(
            migratedSourceIds = migratedSourceIds,
            failedSourceIds = failedSourceIds,
        )
    }

    private suspend fun migrateSource(source: FileDirectorySourceEntity) {
        val legacyBaseUrl = source.webDavBaseUrl
            ?.takeIf { it.isNotBlank() }
            ?: error("Legacy WebDAV source ${source.id} has no base URL")
        val baseUrl = legacyBaseUrl.trim()
        val username = source.webDavUsername.orEmpty()
        val password = cipher.decrypt(source.webDavPassword.orEmpty())
        val accountId = source.webDavAccountId
            ?.takeIf { it.isNotBlank() }
            ?: WebDavAccountStore.accountId(baseUrl, username)

        accountStore.ensureAccountForId(
            accountId = accountId,
            baseUrl = baseUrl,
            username = username,
            password = password,
        )
        check(
            dao.clearLegacyCredentialsAfterMigration(
                id = source.id,
                expectedBaseUrl = legacyBaseUrl,
                accountId = accountId,
            ) == 1,
        ) {
            "Legacy WebDAV source ${source.id} changed during credential migration"
        }
    }
}
