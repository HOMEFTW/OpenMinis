package com.openminis.app.ui.settings

import com.openminis.app.util.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowSettingsTest {

    @Test
    fun `missing preference uses the product default`() {
        assertEquals(
            ContextWindowSettings.DEFAULT,
            ContextWindowSettings.read(MemorySharedPreferences()),
        )
    }

    @Test
    fun `a saved value is read back from the preference store`() {
        val preferences = MemorySharedPreferences()

        assertTrue(ContextWindowSettings.write(preferences, 272_000))

        assertEquals(272_000, ContextWindowSettings.read(preferences))
    }

    @Test
    fun `minimum and maximum values are accepted`() {
        val preferences = MemorySharedPreferences()

        assertTrue(ContextWindowSettings.write(preferences, ContextWindowSettings.MIN))
        assertEquals(ContextWindowSettings.MIN, ContextWindowSettings.read(preferences))

        assertTrue(ContextWindowSettings.write(preferences, ContextWindowSettings.MAX))
        assertEquals(ContextWindowSettings.MAX, ContextWindowSettings.read(preferences))
    }

    @Test
    fun `invalid values do not overwrite the saved value`() {
        val preferences = MemorySharedPreferences()
        ContextWindowSettings.write(preferences, ContextWindowSettings.DEFAULT)

        listOf(
            ContextWindowSettings.MIN - 1,
            ContextWindowSettings.MAX + 1,
        ).forEach { invalid ->
            assertFalse(ContextWindowSettings.write(preferences, invalid))
        }

        assertEquals(ContextWindowSettings.DEFAULT, ContextWindowSettings.read(preferences))
    }

    @Test
    fun `empty malformed and out of range drafts are rejected`() {
        listOf("", "   ", "8000x", "-1", "7999", "1000001").forEach { raw ->
            assertNull(ContextWindowSettings.parse(raw))
        }

        assertEquals(ContextWindowSettings.MIN, ContextWindowSettings.parse(" 8000 "))
        assertEquals(ContextWindowSettings.MAX, ContextWindowSettings.parse("1000000"))
    }

    @Test
    fun `a draft does not change the saved value until it is written`() {
        val preferences = MemorySharedPreferences()
        ContextWindowSettings.write(preferences, ContextWindowSettings.DEFAULT)
        val draft = "272000"

        assertEquals(ContextWindowSettings.DEFAULT, ContextWindowSettings.read(preferences))

        val parsed = ContextWindowSettings.parse(draft)
        assertTrue(parsed != null)
        ContextWindowSettings.write(preferences, parsed!!)
        assertEquals(272_000, ContextWindowSettings.read(preferences))
    }

    @Test
    fun `reset writes the product default`() {
        val preferences = MemorySharedPreferences()
        ContextWindowSettings.write(preferences, 272_000)

        ContextWindowSettings.reset(preferences)

        assertEquals(ContextWindowSettings.DEFAULT, ContextWindowSettings.read(preferences))
    }

    @Test
    fun `quick choice labels keep one million as 1M`() {
        assertEquals("64K", ContextWindowSettings.formatPreset(64_000))
        assertEquals("105K", ContextWindowSettings.formatPreset(105_000))
        assertEquals("272K", ContextWindowSettings.formatPreset(272_000))
        assertEquals("1M", ContextWindowSettings.formatPreset(1_000_000))
    }
}
