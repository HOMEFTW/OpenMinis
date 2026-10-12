package com.openminis.app.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Database cancellation is covered by BackupMergeDatabaseInstrumentedTest using
 * Room: sessions, messages and markers now roll back as one merge transaction.
 */
class RestoreCancelEmptySessionsTest {
    @Test
    fun `restore documentation does not claim a staging database or partial batch durability`() {
        val importer = ProductionSources.read("backup/BackupImporter.kt")
        assertFalse(importer.contains("already runs into a staging DB"))
        val vm = ProductionSources.read("ui/settings/backup/BackupViewModel.kt")
        assertFalse(vm.contains("What survives: every batch the importer has already committed."))
    }
}
