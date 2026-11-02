package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages environment variables with encrypted value storage.
 * Metadata (key names, IDs) stored in JSON file.
 * Values stored in EncryptedSharedPreferences (AES256-GCM).
 * Mirrors iOS EnvVarStore.
 */
class EnvVarRepository(private val context: Context) {

    companion object {
        private const val TAG = "EnvVarRepository"
        private const val METADATA_FILE = "env-vars.json"
        private const val ENCRYPTED_PREFS_NAME = "env_var_values"
        private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }

    data class EnvVarEntry(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        /**
         * Optional human-readable description of what this variable is for.
         * Empty when omitted. Stored in the JSON metadata file (not secret),
         * mirrors iOS EnvVarEntry.note.
         */
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        /** Not stored in env-vars.json. Absent state means enabled. */
        val enabled: Boolean = true,
    )

    private val _entries = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val entries: StateFlow<List<EnvVarEntry>> = _entries.asStateFlow()

    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: self-healing wrapper.
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, ENCRYPTED_PREFS_NAME)
    }

    private val metadataFile: File
        get() = File(context.filesDir, METADATA_FILE)

    private val states = EnvVarStateStore(context.filesDir)

    init {
        loadMetadata()
    }

    // -- Validation --

    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    // Keep printable ASCII (0x20-0x7E) and tab (0x09); iOS paste can inject
    // invisible control scalars (e.g. \u009B) that break shell env injection.
    private fun sanitizeValue(value: String): String =
        value.filter { ch ->
            val c = ch.code
            (c in 0x20..0x7E) || c == 0x09
        }

    fun isDuplicateKey(key: String, excludeId: String? = null): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && it.id != excludeId }

    // -- CRUD --

    fun add(key: String, value: String, note: String = "", enabled: Boolean = true): Boolean {
        val normalizedKey = key.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        if (isDuplicateKey(normalizedKey)) return false

        val entry = EnvVarEntry(key = normalizedKey, note = note.trim(), enabled = enabled)
        _entries.value = _entries.value + entry
        encryptedPrefs.edit().putString(normalizedKey, sanitizeValue(value)).apply()
        saveMetadata()
        if (!enabled) states.setEnabled(entry.id, normalizedKey, false)
        Log.i(TAG, "Added env var: $normalizedKey enabled=$enabled")
        return true
    }

    fun update(id: String, newKey: String, newValue: String, newNote: String = ""): Boolean {
        val normalizedKey = newKey.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false

        val current = _entries.value.find { it.id == id } ?: return false

        // Check for duplicate (excluding self)
        if (isDuplicateKey(normalizedKey, excludeId = id)) return false

        // Delete old Keychain entry if key changed
        if (current.key != normalizedKey) {
            encryptedPrefs.edit().remove(current.key).apply()
        }

        // Update metadata
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(key = normalizedKey, note = newNote.trim()) else it
        }

        // Save new value
        encryptedPrefs.edit().putString(normalizedKey, sanitizeValue(newValue)).apply()
        saveMetadata()
        states.renamed(id, normalizedKey)
        Log.i(TAG, "Updated env var: ${current.key} → $normalizedKey")
        return true
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val current = _entries.value.find { it.id == id } ?: return false
        if (current.enabled == enabled && states.state(id) != null) return true
        states.setEnabled(id, current.key, enabled)
        _entries.value = _entries.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        Log.i(TAG, "Env var ${current.key} enabled=$enabled")
        return true
    }

    fun delete(id: String) {
        val entry = _entries.value.find { it.id == id } ?: return
        encryptedPrefs.edit().remove(entry.key).apply()
        _entries.value = _entries.value.filter { it.id != id }
        saveMetadata()
        states.remove(id)
        Log.i(TAG, "Deleted env var: ${entry.key}")
    }

    fun getValue(key: String): String? = encryptedPrefs.getString(key, null)

    fun isDisabled(key: String): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && !it.enabled }

    /**
     * Enabled variables for shell, terminal and MCP injection.
     */
    fun allAsDict(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in _entries.value) {
            if (!entry.enabled) continue
            val value = encryptedPrefs.getString(entry.key, null)
            if (value != null) {
                result[entry.key] = value
            }
        }
        return result
    }

    /** Includes disabled secrets so tool output stays masked. */
    fun allValuesForRedaction(): List<String> =
        _entries.value.mapNotNull { encryptedPrefs.getString(it.key, null) }

    // -- Metadata Persistence --

    private fun saveMetadata() {
        try {
            val array = JSONArray()
            for (entry in _entries.value) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("key", entry.key)
                if (entry.note.isNotEmpty()) obj.put("note", entry.note)
                obj.put("createdAt", entry.createdAt)
                array.put(obj)
            }
            metadataFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
        }
    }

    private fun loadMetadata() {
        try {
            if (!metadataFile.exists()) {
                states.prune(emptySet())
                return
            }
            val array = JSONArray(metadataFile.readText())
            val entries = mutableListOf<EnvVarEntry>()
            var mintedIds = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "").ifEmpty {
                    mintedIds = true
                    UUID.randomUUID().toString()
                }
                entries.add(EnvVarEntry(
                    id = id,
                    key = obj.optString("key", ""),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", 0),
                    enabled = states.isEnabled(id),
                ))
            }
            _entries.value = entries
            if (mintedIds) saveMetadata()
            states.prune(entries.map { it.id }.toSet())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata: ${e.message}")
        }
    }
}
