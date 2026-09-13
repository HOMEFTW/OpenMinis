package com.openminis.app.scheduled

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledRunPolicyTest {

    @Test
    fun `only completed is successful`() {
        assertTrue(ScheduledRunPolicy.isSuccess("Completed"))
        listOf("Cancelled", "Timeout", "BudgetExceeded", "NotStarted", "Error", "Running", "Queued")
            .forEach { status -> assertFalse(status, ScheduledRunPolicy.isSuccess(status)) }
    }
}
