package com.openminis.app.forge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ForgeInformationStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun historySurvivesRecreationAndIsBoundedAndSessionIsolated() {
        val root = directory.newFolder("information")
        val store = ForgeInformationStore(root)
        val context = ForgeInformationContext("s1", "model-a", "p1", "HIGH", "endpoint")
        repeat(25) { store.save(context, JSONObject().put("prediction", "candidate"), "manual", it.toLong()) }
        store.save(context.copy(sessionId = "s2"), JSONObject().put("prediction", "other"), "manual", 30)
        val reopened = ForgeInformationStore(root)
        val history = reopened.read("s1").getJSONArray("history")
        assertEquals(20, history.length()); assertEquals(24L, history.getJSONObject(0).getLong("createdAt"))
        assertEquals(1, reopened.read("s2").getJSONArray("history").length())
        assertTrue(history.objects().all { it.getString("threadId") == "s1" })
    }

    @Test fun identifiersNeverBecomeFilesystemPaths() {
        val root = directory.newFolder("information")
        val store = ForgeInformationStore(root)
        store.update("../../escape") { it.put("value", 1) }
        assertEquals(1, store.read("../../escape").getInt("value"))
        assertTrue(root.listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.json")) })
    }
}
