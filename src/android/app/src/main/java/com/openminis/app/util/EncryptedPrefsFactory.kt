package com.openminis.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openminis.app.logging.LogRedactor
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Creates encrypted preference stores without allowing one broken store to
 * destroy another store's key material. Keystore failures use a process-only
 * store and publish a visible health signal for the settings/provider UI.
 */
object EncryptedPrefsFactory {
    private const val TAG = "EncryptedPrefsFactory"
    private const val LEGACY_SUFFIX = "_plain_fallback"

    data class StorageStatus(
        val fileName: String,
        val storage: EncryptedPrefsStorage,
        val warning: String? = null,
    )

    private val memoryStores = mutableMapOf<String, MemorySharedPreferences>()
    private val statusLock = Any()
    private val _storageStatuses = MutableStateFlow<Map<String, StorageStatus>>(emptyMap())

    /** Current encrypted/memory mode for every preference store touched here. */
    val storageStatuses: StateFlow<Map<String, StorageStatus>> = _storageStatuses.asStateFlow()

    fun statusFor(fileName: String): StorageStatus? = _storageStatuses.value[fileName]

    fun hasMemoryFallback(): Boolean = _storageStatuses.value.values.any {
        it.storage == EncryptedPrefsStorage.MEMORY
    }

    fun safeCreate(context: Context, fileName: String): SharedPreferences {
        val appContext = context.applicationContext
        val storeKey = "${appContext.filesDir.absolutePath}:$fileName"
        val memory = synchronized(memoryStores) {
            memoryStores.getOrPut(storeKey) { MemorySharedPreferences() }
        }
        val legacyName = fileName + LEGACY_SUFFIX
        val legacy = appContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        // Bring a legacy value into process memory before trying Keystore. This
        // never writes or deletes the legacy file; cleanup is gated by a later
        // successful encrypted write and verification.
        runCatching { legacy.all }
            .onSuccess(memory::putAllIfAbsent)
            .onFailure { failure ->
                Log.w(
                    TAG,
                    LogRedactor.redact(
                        "legacy fallback read($fileName) failed: " +
                            "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}",
                    ),
                )
            }

        val resolution = EncryptedPrefsRecovery(
            fileName = fileName,
            createEncrypted = { build(appContext, fileName) },
            memoryPrefs = memory,
            legacyPrefs = legacy,
            clearLegacy = { clearLegacy(appContext, legacyName, legacy) },
            policy = EncryptedPrefsRecoveryPolicy.RETRY_ONCE,
            onFailure = { stage, failure ->
                val details = "${stage}($fileName) failed: ${failure.javaClass.simpleName}: ${failure.message ?: "no message"}"
                Log.w(TAG, LogRedactor.redact(details))
            },
        ).resolve()

        updateStatus(fileName, StorageStatus(fileName, resolution.storage, resolution.warning))
        if (resolution.storage == EncryptedPrefsStorage.ENCRYPTED) {
            synchronized(memoryStores) { memoryStores.remove(storeKey) }
        }
        return SecretTrackingSharedPreferences(resolution.prefs)
    }

    private fun build(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun clearLegacy(
        context: Context,
        fileName: String,
        prefs: SharedPreferences,
    ): Boolean {
        if (!prefs.edit().clear().commit()) return false
        return runCatching {
            context.deleteSharedPreferences(fileName)
            val path = File(context.applicationInfo.dataDir, "shared_prefs/$fileName.xml")
            !path.exists()
        }.getOrDefault(false)
    }

    private fun updateStatus(fileName: String, status: StorageStatus) {
        synchronized(statusLock) {
            _storageStatuses.value = _storageStatuses.value + (fileName to status)
        }
    }
}
