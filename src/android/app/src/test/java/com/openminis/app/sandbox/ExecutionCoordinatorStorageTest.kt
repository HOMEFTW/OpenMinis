package com.openminis.app.sandbox

import com.openminis.app.data.storage.SessionStorageMaintenanceGate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionCoordinatorStorageTest {
    @Test fun stopCancelsTheCommandWhileWaitingForStorageMaintenanceBeforeBoot() = runBlocking {
        val sid = "storage-wait-${java.util.UUID.randomUUID()}"
        SessionStorageMaintenanceGate.shared.withOrphanMaintenance { maintenance ->
            val claim = checkNotNull(maintenance.claimIfUnreserved(sid))
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                // No Android context is needed: Stop must cancel before boot or spawn.
                ExecutionCoordinator.execute(sid, "must never run")
            }
            try {
                ExecutionCoordinator.stopCurrentCommand(sid)
                withTimeout(2000) { job.join() }
                assertTrue(job.isCancelled)
            } finally {
                job.cancelAndJoin()
                claim.close()
            }
        }
    }
}
