package com.openminis.app.ui.markdown

/** Callers synchronize access. Oversized entries are returned by the parser, not retained. */
internal class MarkdownCache<K, V : Any>(
    private val budget: Int,
    private val maxEntries: Int = 1_024,
    private val sizer: (K, V) -> Int,
) {
    private val entries = LinkedHashMap<K, V>(64, 0.75f, true)
    var totalChars: Long = 0
        private set
    val size: Int get() = entries.size

    operator fun get(key: K): V? = entries[key]

    operator fun set(key: K, value: V) { put(key, value) }

    fun put(key: K, value: V): V? {
        val prior = remove(key)
        val chars = sizer(key, value).coerceAtLeast(1).toLong()
        if (chars > budget || maxEntries <= 0) return prior
        entries[key] = value
        totalChars += chars
        val iterator = entries.entries.iterator()
        while (totalChars > budget || entries.size > maxEntries) {
            val eldest = iterator.next()
            totalChars -= sizer(eldest.key, eldest.value).coerceAtLeast(1)
            iterator.remove()
        }
        return prior
    }

    fun remove(key: K): V? {
        val value = entries.remove(key) ?: return null
        totalChars -= sizer(key, value).coerceAtLeast(1)
        return value
    }

    fun clear() {
        entries.clear()
        totalChars = 0
    }
}
