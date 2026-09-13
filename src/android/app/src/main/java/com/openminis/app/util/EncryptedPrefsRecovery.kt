package com.openminis.app.util

import android.content.SharedPreferences

enum class EncryptedPrefsStorage {
    ENCRYPTED,
    MEMORY,
}

data class EncryptedPrefsResolution(
    val prefs: SharedPreferences,
    val storage: EncryptedPrefsStorage,
    val warning: String? = null,
)

/** Controls whether a failed encrypted-store creation receives one retry. */
fun interface EncryptedPrefsRecoveryPolicy {
    fun shouldRetry(fileName: String, firstFailure: Throwable): Boolean

    companion object {
        val RETRY_ONCE = EncryptedPrefsRecoveryPolicy { _, _ -> true }
        val MEMORY_ONLY = EncryptedPrefsRecoveryPolicy { _, _ -> false }
    }
}

/**
 * Small, context-free recovery state machine so Keystore failure handling can
 * be tested without a device or real credentials.
 */
class EncryptedPrefsRecovery(
    private val fileName: String,
    private val createEncrypted: () -> SharedPreferences,
    private val memoryPrefs: SharedPreferences,
    private val legacyPrefs: SharedPreferences?,
    private val clearLegacy: () -> Boolean,
    private val policy: EncryptedPrefsRecoveryPolicy = EncryptedPrefsRecoveryPolicy.RETRY_ONCE,
    private val onFailure: (stage: String, failure: Throwable) -> Unit = { _, _ -> },
) {
    fun resolve(): EncryptedPrefsResolution {
        val firstAttempt = runCatching { createEncrypted() }
        firstAttempt.getOrNull()?.let { encrypted ->
            return migrateToEncrypted(encrypted)
        }

        val firstFailure = firstAttempt.exceptionOrNull() ?: IllegalStateException("encrypted store unavailable")
        runCatching { onFailure("first_create", firstFailure) }
        val shouldRetry = runCatching { policy.shouldRetry(fileName, firstFailure) }.getOrDefault(false)
        if (shouldRetry) {
            val retryAttempt = runCatching { createEncrypted() }
            retryAttempt.getOrNull()?.let { encrypted ->
                return migrateToEncrypted(encrypted)
            }
            retryAttempt.exceptionOrNull()?.let { runCatching { onFailure("retry_create", it) } }
        }
        return memoryResolution("encryption_unavailable")
    }

    private fun migrateToEncrypted(encrypted: SharedPreferences): EncryptedPrefsResolution {
        return try {
            // Reading all values is an explicit health check. A successful
            // EncryptedSharedPreferences.create() can still return an object
            // whose keyset fails on first access.
            val encryptedSnapshot = encrypted.all
            // Preserve values already readable from the encrypted store in case
            // a previous process-memory fallback contains only a subset.
            copyMissingValues(encryptedSnapshot, memoryPrefs)

            val legacy = legacyPrefs
            val legacySnapshot = legacy?.all ?: emptyMap()
            // Seed the process-memory copy before touching the encrypted store
            // so a failed migration still leaves the legacy credential usable
            // without writing it back to disk.
            copyMissingValues(legacySnapshot, memoryPrefs)
            if (!migrateSnapshot(legacySnapshot, encrypted)) {
                return memoryResolution("legacy_migration_failed")
            }
            if (!migrateSnapshot(memoryPrefs.all, encrypted)) {
                return memoryResolution("memory_migration_failed")
            }
            if (legacy != null && legacySnapshot.isNotEmpty() && !clearLegacy()) {
                return memoryResolution("legacy_cleanup_failed")
            }
            // Keep the process-memory snapshot alive for callers that already
            // hold the fallback instance. New safeCreate calls receive the
            // encrypted instance; clearing this object here would make those
            // older callers observe an unexplained credential disappearance.
            EncryptedPrefsResolution(encrypted, EncryptedPrefsStorage.ENCRYPTED)
        } catch (failure: Throwable) {
            runCatching { onFailure("migration", failure) }
            memoryResolution("encrypted_store_unreadable")
        }
    }

    private fun migrateSnapshot(snapshot: Map<String, *>, target: SharedPreferences): Boolean {
        if (snapshot.isEmpty()) return true
        val before = target.all
        val editor = target.edit()
        for ((key, value) in snapshot) {
            if (before.containsKey(key)) {
                // Never replace an existing encrypted value with stale legacy
                // data. Keeping the source is safer than silently choosing.
                if (!sameValue(value, before[key])) return false
            } else if (!putValue(editor, key, value)) {
                return false
            }
        }
        if (!editor.commit()) return false
        val after = target.all
        return snapshot.all { (key, value) -> sameValue(value, after[key]) }
    }

    private fun copyMissingValues(sourceValues: Map<String, *>, target: SharedPreferences) {
        if (sourceValues.isEmpty()) return
        val targetValues = target.all
        val editor = target.edit()
        var changed = false
        sourceValues.forEach { (key, value) ->
            if (!targetValues.containsKey(key) && putValue(editor, key, value)) changed = true
        }
        if (changed && !editor.commit()) {
            // MemorySharedPreferences commits cannot fail; an injected store
            // may, and the enclosing recovery catches the resulting health
            // failure before returning encrypted mode.
            throw IllegalStateException("unable to seed memory fallback")
        }
    }

    private fun memoryResolution(reason: String): EncryptedPrefsResolution =
        EncryptedPrefsResolution(memoryPrefs, EncryptedPrefsStorage.MEMORY, reason)

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?): Boolean {
        return when (value) {
            is String -> { editor.putString(key, value); true }
            is Set<*> -> {
                val strings = value.filterIsInstance<String>().toMutableSet()
                if (strings.size != value.size) false else { editor.putStringSet(key, strings); true }
            }
            is Int -> { editor.putInt(key, value); true }
            is Long -> { editor.putLong(key, value); true }
            is Float -> { editor.putFloat(key, value); true }
            is Boolean -> { editor.putBoolean(key, value); true }
            else -> false
        }
    }

    private fun sameValue(left: Any?, right: Any?): Boolean = when {
        left is Set<*> && right is Set<*> -> left == right
        else -> left == right
    }
}
