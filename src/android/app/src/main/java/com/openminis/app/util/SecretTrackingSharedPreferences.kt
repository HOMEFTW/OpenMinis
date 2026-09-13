package com.openminis.app.util

import android.content.SharedPreferences
import com.openminis.app.logging.LogRedactor

/**
 * Registers values read or written through a credential preference store so
 * arbitrary command text can be redacted too. It never loads a store itself;
 * the caller supplies an already-created delegate, avoiding Keystore/logger
 * initialization recursion.
 */
internal class SecretTrackingSharedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences {
    init {
        runCatching { track(delegate.all.values) }
    }

    override fun getAll(): Map<String, *> = delegate.all.also { track(it.values) }

    override fun getString(key: String, defValue: String?): String? =
        delegate.getString(key, defValue).also { value -> track(value) }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        delegate.getStringSet(key, defValues).also { it?.forEach(::track) }

    override fun getInt(key: String, defValue: Int): Int = delegate.getInt(key, defValue)

    override fun getLong(key: String, defValue: Long): Long = delegate.getLong(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float = delegate.getFloat(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean = delegate.getBoolean(key, defValue)

    override fun contains(key: String): Boolean = delegate.contains(key)

    override fun edit(): SharedPreferences.Editor = TrackingEditor(
        delegate = delegate.edit(),
        trackValue = { value -> track(value) },
    )

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = delegate.registerOnSharedPreferenceChangeListener(listener)

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = delegate.unregisterOnSharedPreferenceChangeListener(listener)

    private fun track(value: Any?) {
        when (value) {
            is String -> {
                LogRedactor.registerKnownSecret(value)
                jsonSecretValue.findAll(value).forEach { match ->
                    LogRedactor.registerKnownSecret(match.groupValues[1])
                }
            }
            is Set<*> -> value.filterIsInstance<String>().forEach { item ->
                LogRedactor.registerKnownSecret(item)
            }
        }
    }

    private fun track(values: Collection<*>) {
        values.forEach(::track)
    }

    private class TrackingEditor(
        private val delegate: SharedPreferences.Editor,
        private val trackValue: (Any?) -> Unit,
    ) : SharedPreferences.Editor by delegate {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            trackValue(value)
            delegate.putString(key, value)
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            values?.forEach(trackValue)
            delegate.putStringSet(key, values)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            delegate.putInt(key, value)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            delegate.putLong(key, value)
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            delegate.putFloat(key, value)
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            delegate.putBoolean(key, value)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            delegate.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            delegate.clear()
            return this
        }
    }

    private companion object {
        private val jsonSecretValue = Regex(
            """(?i)\"(?:access_token|refresh_token|id_token|api_key|apikey|client_secret|password|token)\"\s*:\s*\"([^\"]+)\"""",
        )
    }
}
