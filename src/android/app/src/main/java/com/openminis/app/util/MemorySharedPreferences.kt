package com.openminis.app.util

import android.content.SharedPreferences
import java.util.Collections

/**
 * Process-only SharedPreferences implementation used when Android Keystore is
 * temporarily unavailable. It deliberately never writes a backing file.
 */
class MemorySharedPreferences(initialValues: Map<String, *> = emptyMap<String, Any?>()) : SharedPreferences {
    private val lock = Any()
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        synchronized(lock) {
            initialValues.forEach { (key, value) -> values[key] = copyValue(value) }
        }
    }

    override fun getAll(): Map<String, *> = synchronized(lock) {
        Collections.unmodifiableMap(values.mapValues { (_, value) -> copyValue(value) })
    }

    override fun getString(key: String, defValue: String?): String? = synchronized(lock) {
        readValue(key, defValue, String::class.java)
    }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(lock) {
        val value = values[key] ?: return@synchronized copySet(defValues)
        requireType(key, value, Set::class.java)
        copySet(value as Set<String>)
    }

    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) {
        readValue(key, defValue, java.lang.Integer::class.java)
    }

    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) {
        readValue(key, defValue, java.lang.Long::class.java)
    }

    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) {
        readValue(key, defValue, java.lang.Float::class.java)
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) {
        readValue(key, defValue, java.lang.Boolean::class.java)
    }

    override fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(lock) { listeners += listener }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(lock) { listeners -= listener }
    }

    /** Seed values from an old fallback without replacing values entered this process. */
    internal fun putAllIfAbsent(source: Map<String, *>) {
        synchronized(lock) {
            source.forEach { (key, value) ->
                if (!values.containsKey(key)) values[key] = copyValue(value)
            }
        }
    }

    private fun <T> readValue(key: String, default: T, expected: Class<*>): T {
        val value = values[key] ?: return default
        requireType(key, value, expected)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun requireType(key: String, value: Any, expected: Class<*>) {
        if (!expected.isInstance(value)) {
            throw ClassCastException("Preference $key is ${value::class.java.name}, expected ${expected.name}")
        }
    }

    private fun copyValue(value: Any?): Any? = when (value) {
        is Set<*> -> copySet(value.filterIsInstance<String>().toSet())
        else -> value
    }

    private fun copySet(value: Set<String>?): MutableSet<String>? = value?.toMutableSet()

    private inner class Editor : SharedPreferences.Editor {
        private val mutations = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            mutations[key] = value ?: REMOVE
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            mutations[key] = values?.toSet()?.let(::copyValue) ?: REMOVE
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            mutations[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            mutations[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            mutations[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            mutations[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            mutations[key] = REMOVE
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            val changedKeys: List<String>
            val callbackListeners: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(lock) {
                val before = values.mapValues { (_, value) -> copyValue(value) }
                if (clearRequested) values.clear()
                mutations.forEach { (key, value) ->
                    if (value === REMOVE) values.remove(key) else values[key] = copyValue(value)
                }
                changedKeys = (before.keys + values.keys).filter { key ->
                    !values.containsKey(key) || !before.containsKey(key) || !sameValue(before[key], values[key])
                }.distinct()
                callbackListeners = listeners.toList()
            }
            // Callbacks run outside the lock, matching Android's listener behavior
            // and allowing a listener to read or edit this store safely.
            changedKeys.forEach { key -> callbackListeners.forEach { it.onSharedPreferenceChanged(this@MemorySharedPreferences, key) } }
        }
    }

    companion object {
        private val REMOVE = Any()

        private fun sameValue(left: Any?, right: Any?): Boolean = when {
            left is Set<*> && right is Set<*> -> left == right
            else -> left == right
        }
    }
}
