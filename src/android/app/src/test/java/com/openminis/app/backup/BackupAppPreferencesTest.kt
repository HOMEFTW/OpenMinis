package com.openminis.app.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class BackupAppPreferencesTest {
    private fun document(json: String) = Json.parseToJsonElement(json) as JsonObject
    private val defaults = BackupAppPreferences.UpdateSettings("stable", true)

    @Test fun `snapshot only carries portable update choices`() {
        assertEquals(document("""{
            "update_preferences": {"updates.channel":"beta", "updates.autoCheck":false}
        }"""), BackupAppPreferences.snapshot(BackupAppPreferences.UpdateSettings("beta", false)))
    }

    @Test fun `backup choices replace both existing device choices`() {
        val incoming = BackupAppPreferences.UpdateSettings("beta", false)
        val plan = BackupAppPreferences.plan(defaults, BackupAppPreferences.snapshot(incoming))
        assertEquals(incoming, plan.settings)
        assertEquals(2, plan.updated)
        assertEquals(0, plan.skipped)
        assertEquals(0, plan.unreadable)
    }

    @Test fun `backed up default values reset nondefault target values`() {
        val plan = BackupAppPreferences.plan(
            BackupAppPreferences.UpdateSettings("beta", false), BackupAppPreferences.snapshot(defaults),
        )
        assertEquals(defaults, plan.settings)
        assertEquals(2, plan.updated)
    }

    @Test fun `partial restore preserves missing fields and ignores local timestamps and other preference files`() {
        val plan = BackupAppPreferences.plan(defaults, document("""{
            "update_preferences": {"updates.channel":"beta", "lastCheck.beta":99999, "future.key":true},
            "unrelated_preferences": {"value":"must not restore"}
        }"""))
        assertEquals(BackupAppPreferences.UpdateSettings("beta", true), plan.settings)
        assertEquals(1, plan.updated)
        assertEquals(0, plan.skipped)
        assertEquals(0, plan.unreadable)
    }

    @Test fun `repeating the same restore only counts unchanged fields`() {
        val snapshot = BackupAppPreferences.snapshot(defaults)
        val first = BackupAppPreferences.plan(BackupAppPreferences.UpdateSettings("beta", false), snapshot)
        val second = BackupAppPreferences.plan(first.settings, snapshot)
        assertEquals(0, second.updated)
        assertEquals(2, second.skipped)
        assertEquals(first.settings, second.settings)
    }

    @Test fun `invalid channels and string booleans do not corrupt device values`() {
        val plan = BackupAppPreferences.plan(defaults, document("""{
            "update_preferences": {"updates.channel":"nightly", "updates.autoCheck":"false"}
        }"""))
        assertEquals(defaults, plan.settings)
        assertEquals(0, plan.updated)
        assertEquals(0, plan.skipped)
        assertEquals(2, plan.unreadable)
    }

    @Test fun `valid fields still restore when another field is invalid`() {
        val plan = BackupAppPreferences.plan(defaults, document("""{
            "update_preferences": {"updates.channel":null, "updates.autoCheck":false}
        }"""))
        assertEquals(BackupAppPreferences.UpdateSettings("stable", false), plan.settings)
        assertEquals(1, plan.updated)
        assertEquals(1, plan.unreadable)
    }

    @Test fun `old and cross platform packages without Android preferences are no ops`() {
        val plan = BackupAppPreferences.plan(defaults, document("""{"future_preferences":{}}"""))
        assertEquals(BackupAppPreferences.RestorePlan(defaults), plan)
        assertEquals(1, BackupAppPreferences.plan(defaults, document("""{"update_preferences":[]} """)).unreadable)
    }
}
