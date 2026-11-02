package com.openminis.app.forge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForgeInformationProbeJobsTest {
    private val context = ForgeInformationContext("s1", "model", "provider", "HIGH", "endpoint")

    @Test fun cancellingTheProbeDoesNotSavePartialResults() = runTest {
        val jobs = ForgeInformationProbeJobs(this)
        var saved = false
        jobs.start(context, { true }, run = { progress -> progress(1); awaitCancellation() }, save = { saved = true })
        runCurrent(); jobs.cancel("s1"); runCurrent()
        assertFalse(saved); assertEquals("cancelled", jobs.snapshot(context)!!.getString("status"))
    }

    @Test fun evenANonCooperativeCompletionCannotCommitAfterCancellation() = runTest {
        val jobs = ForgeInformationProbeJobs(this)
        val release = CompletableDeferred<Unit>()
        var saved = false
        jobs.start(context, { true }, run = { withContext(NonCancellable) { release.await() }; JSONArray() }, save = { saved = true })
        runCurrent(); jobs.cancel("s1"); release.complete(Unit); runCurrent()
        assertFalse(saved); assertEquals("cancelled", jobs.snapshot(context)!!.getString("status"))
    }

    @Test fun changingContextDuringTheProbePreventsSaving() = runTest {
        val jobs = ForgeInformationProbeJobs(this)
        var current = true
        var saved = false
        jobs.start(context, { current }, run = { current = false; JSONArray() }, save = { saved = true })
        runCurrent()
        assertFalse(saved); assertEquals("failed", jobs.snapshot(context)!!.getString("status"))
    }

    @Test fun anotherSessionModelProviderTierOrEndpointCannotReadTheProbe() = runTest {
        val jobs = ForgeInformationProbeJobs(this)
        jobs.start(context, { true }, run = { JSONArray() }, save = {})
        runCurrent()
        for (other in listOf(context.copy(sessionId = "s2"), context.copy(model = "other"), context.copy(provider = "other"), context.copy(effort = "LOW"), context.copy(endpoint = "other"))) assertNull(jobs.snapshot(other))
    }

    @Test fun onlyOneExplicitProbeCanRunAcrossAllSessions() = runTest {
        val jobs = ForgeInformationProbeJobs(this)
        jobs.start(context, { true }, run = { awaitCancellation() }, save = {})
        assertThrows(IllegalArgumentException::class.java) {
            jobs.start(context.copy(sessionId = "s2"), { true }, run = { JSONArray() }, save = {})
        }
        jobs.cancel("s1"); runCurrent()
    }

    @Test fun successfulProbeSavesOnceAndReportsProgress() = runTest {
        val jobs = ForgeInformationProbeJobs(this)
        var saves = 0
        jobs.start(context, { true }, run = { progress -> progress(3); JSONArray() }, save = { saves++ })
        runCurrent(); jobs.cancel("s1")
        assertEquals(1, saves); assertEquals("completed", jobs.snapshot(context)!!.getString("status"))
        assertEquals(3, jobs.snapshot(context)!!.getInt("completed"))
    }
}
