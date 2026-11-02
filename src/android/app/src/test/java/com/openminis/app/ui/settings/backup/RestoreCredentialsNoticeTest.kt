package com.openminis.app.ui.settings.backup

import com.openminis.app.backup.BackupImporter
import org.junit.Assert.*
import org.junit.Test

class RestoreCredentialsNoticeTest {
    private fun providers() = BackupImporter.CategoryReport("providers")

    @Test fun `unreadable and unchanged providers do not claim restored providers have missing keys`() {
        assertNull(restoreCredentialsNotice(providers().copy(unreadable = 1)))
        assertNull(restoreCredentialsNotice(providers().copy(failed = "decode failed")))
        assertNull(restoreCredentialsNotice(providers().copy(skipped = 5)))
    }

    @Test fun `actual credential writes and kept keys take priority over the configuration counts`() {
        assertEquals(RestoreCredentialsNotice.RESTORED, restoreCredentialsNotice(providers().copy(credentialsRestored = 1)))
        assertEquals(RestoreCredentialsNotice.KEPT, restoreCredentialsNotice(providers().copy(credentialsKept = 2)))
        assertEquals(RestoreCredentialsNotice.NONE_IN_PACKAGE, restoreCredentialsNotice(providers().copy(imported = 1)))
    }

    @Test fun `fork subagent or thinking rule updates alone do not create a missing provider credentials warning`() {
        assertNull(restoreCredentialsNotice(providers().copy(imported = 2, updated = 1, declaredAccounted = 0)))
    }
}
