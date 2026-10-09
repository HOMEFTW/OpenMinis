package com.openminis.app.data.repository

import com.openminis.app.data.repository.AppIconRepository.Variant
import org.junit.Assert.*
import org.junit.Test

class AppIconRepositoryTest {
    @Test fun `fresh installs and legacy theme selections default to GPT`() {
        for (id in listOf(null, "", "auto", "classic_light", "classic_dark", "unknown")) {
            assertEquals(Variant.Gpt, Variant.fromId(id))
        }
    }

    @Test fun `all three choices round trip with independent resources and stable aliases`() {
        for (variant in Variant.entries) assertEquals(variant, Variant.fromId(variant.id))
        assertEquals(3, Variant.entries.map { it.iconRes }.toSet().size)
        assertEquals(3, Variant.entries.map { it.notificationRes }.toSet().size)
        assertEquals(setOf("com.openminis.app.MainActivityIconAuto", "com.openminis.app.MainActivityIconLight",
            "com.openminis.app.MainActivityIconDark"), Variant.entries.map { it.aliasClass }.toSet())
    }

    @Test fun `every transition keeps at least one enabled launcher and finishes with exactly one`() {
        for (previous in Variant.entries) for (target in Variant.entries) {
            val enabled = mutableSetOf(previous)
            AppIconRepository.switchAliases(target) { variant, value ->
                if (value) enabled.add(variant) else enabled.remove(variant)
                assertTrue("$previous -> $target", enabled.isNotEmpty())
            }
            assertEquals(setOf(target), enabled)
        }
    }

    @Test fun `failed target activation never disables the previous launcher`() {
        val enabled = mutableSetOf(Variant.Claude)
        try {
            AppIconRepository.switchAliases(Variant.Gpt) { variant, value ->
                if (value) throw IllegalStateException("launcher refused")
                enabled.remove(variant)
            }
            fail("failure must propagate before disabling aliases")
        } catch (_: IllegalStateException) { }
        assertEquals(setOf(Variant.Claude), enabled)
    }
}
