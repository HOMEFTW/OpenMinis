package com.openminis.app.sandbox.offload

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sessions-cli-update] The `minis-sessions-cli update / groups / list --group`
 * contract (src/shared/cli/sessions-update.md), pure parts. The end-to-end
 * upstream test needs Robolectric, which this fork does not use.
 */
class SessionsUpdateRulesTest {

    private fun fail(r: SessionsUpdateRules.Request) = SessionsUpdateRules.validate(r)?.code

    @Test fun `argument errors, in order`() {
        assertEquals(SessionsUpdateRules.INVALID_ARGUMENTS, fail(SessionsUpdateRules.Request()))
        assertEquals(SessionsUpdateRules.INVALID_ARGUMENTS, fail(SessionsUpdateRules.Request(title = "a", titleAuto = true)))
        assertEquals(SessionsUpdateRules.INVALID_ARGUMENTS, fail(SessionsUpdateRules.Request(title = "   ")))
        assertEquals(SessionsUpdateRules.INVALID_ICON, fail(SessionsUpdateRules.Request(icon = "rocket")))
        assertNull(fail(SessionsUpdateRules.Request(title = " New ", group = "", icon = " CODE ")))
        assertNull(fail(SessionsUpdateRules.Request(titleAuto = true)))
    }

    @Test fun `icons - the edit sheet's 16 keys, any case, blank is the default icon`() {
        assertEquals(16, SessionsUpdateRules.ICON_NAMES.size)
        assertEquals("code", SessionsUpdateRules.normalizedIcon("  Code "))
        assertEquals("", SessionsUpdateRules.normalizedIcon("  "))
        assertNull(SessionsUpdateRules.normalizedIcon("rocket"))
    }

    @Test fun `a repeated group name picks the newest active group, then any with members, then the oldest`() {
        val folders = listOf(
            SessionsUpdateRules.FolderRef("old-empty", "W", createdAt = 1),
            SessionsUpdateRules.FolderRef("busy", "W", createdAt = 5),
            SessionsUpdateRules.FolderRef("quiet", "W", createdAt = 2),
            SessionsUpdateRules.FolderRef("other", "X", createdAt = 0),
        )
        assertEquals("busy", SessionsUpdateRules.pickFolder("W", folders, mapOf("busy" to 50L, "quiet" to 10L)))
        assertEquals("old-empty", SessionsUpdateRules.pickFolder("W", folders, emptyMap()))
        assertEquals("other", SessionsUpdateRules.pickFolder("X", folders, emptyMap()))
        assertNull("names match exactly, case-sensitively", SessionsUpdateRules.pickFolder("w", folders, emptyMap()))
    }

    @Test fun `changed lists the differing fields in title, group, icon order`() {
        val a = SessionsUpdateRules.Snapshot("t", "g", "code")
        assertEquals(listOf("title", "icon"), SessionsUpdateRules.changedFields(a, a.copy(title = "u", icon = "")))
        assertTrue(SessionsUpdateRules.changedFields(a, a).isEmpty())
    }

    @Test fun `wiring - the handler serves update and groups, and title-auto is the list's regenerator`() {
        val handler = ProductionSources.read("sandbox/offload/SessionsOffloadHandler.kt")
        assertTrue(handler.contains("booleanFlags = setOf(\"full\", \"tools\", \"title-auto\")"))
        assertTrue(handler.contains("Valid: list, search, messages, status, update, groups."))
        val app = ProductionSources.read("MinisApp.kt")
        assertTrue(app.contains("com.openminis.app.ui.sessions.SessionTitleRegenerator(chatRepository, providerRepository, this)"))
        val vm = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        assertTrue(vm.contains("titleRegenerator.regenerate(id, origin)"))
    }
}
