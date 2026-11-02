package com.openminis.app.forge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForgeInformationProbePolicyTest {
    @Test fun imageToolRoutesAreRejectedBeforeCreatingAProvider() {
        assertNotNull(ForgeInformationProbePolicy.unavailableReason(false, false, null))
        assertNotNull(ForgeInformationProbePolicy.unavailableReason(true, true, null))
    }

    @Test fun customBodiesCannotReintroduceToolsHistoryOrRemoteState() {
        for (field in listOf("tools", "functions", "messages", "input", "instructions", "previous_response_id", "conversation", "plugins", "web_search_options", "unknown_capability"))
            assertNotNull(field, ForgeInformationProbePolicy.unavailableReason(true, false, JSONObject().put(field, "value")))
    }

    @Test fun ordinarySamplingAndThinkingParametersRemainUnchanged() {
        val body = JSONObject("""{"temperature":0.8,"top_p":0.9,"reasoning_effort":"high","thinking":{"type":"enabled","budget_tokens":4096}}""")
        val original = body.toString()
        assertNull(ForgeInformationProbePolicy.unavailableReason(true, false, body))
        assertEquals(original, body.toString())
        assertNull(ForgeInformationProbePolicy.unavailableReason(true, false, null))
    }
}
