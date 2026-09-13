package com.openminis.app.data

import org.junit.Assert.*
import org.junit.Test

class TaskBudgetTest {
    @Test fun invalidSettingsKeepExistingSafeDefault() {
        for (n in listOf(-1, 0, 201, Int.MAX_VALUE)) assertEquals(200, TaskBudgetPrefs.validated(n))
        for (n in TaskBudgetPrefs.options) assertEquals(n, TaskBudgetPrefs.validated(n))
    }
    @Test fun warningStartsAtEightyPercent() {
        assertFalse(TaskBudgetProgress(0, 10).nearLimit)
        assertFalse(TaskBudgetProgress(7, 10).nearLimit)
        assertTrue(TaskBudgetProgress(8, 10).nearLimit)
        assertTrue(TaskBudgetProgress(200, 200).nearLimit)
    }
}
