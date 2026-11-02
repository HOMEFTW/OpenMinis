package com.openminis.app.forge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForgeApiAccessTest {
    @Test fun `toolbar permission is not cached as denied because sidebar is absent`() {
        val m = JSONObject("""{"permissions":["toolbar"],"capabilities":[{"name":"ui","version":1}]}""")
        assertNull(ForgeApiAccess.denial(m, "ui.register"))
        assertNull(ForgeApiAccess.denial(m, "ui.register", "toolbar"))
        assertNotNull(ForgeApiAccess.denial(m, "ui.register", "sidebar"))
        assertNotNull(ForgeApiAccess.denial(m, "ui.register", "unsupported"))
    }
    @Test fun `declaring capability never grants missing permission`() {
        val m = JSONObject("""{"permissions":[],"capabilities":[{"name":"storage","version":1}]}""")
        assertNotNull(ForgeApiAccess.denial(m, "storage.set"))
        m.getJSONArray("permissions").put("storage")
        assertNull(ForgeApiAccess.denial(m, "storage.set"))
        m.getJSONArray("capabilities").getJSONObject(0).put("version", 2)
        assertNotNull(ForgeApiAccess.denial(m, "storage.set"))
    }
    @Test fun `explicit declarations restrict otherwise granted permissions`() {
        val m = JSONObject("""{"permissions":["storage","composer","conversation.read"],"capabilities":[]}""")
        assertNotNull(ForgeApiAccess.denial(m, "composer.set"))
        assertNull(ForgeApiAccess.denial(m, "capabilities.get"))
        assertNotNull(ForgeApiAccess.denial(m, "devtools.eval"))
    }
    @Test fun `draft and title APIs require conversation permission as well`() {
        val m = JSONObject("""{"permissions":["composer"],"capabilities":[{"name":"composer","version":1}]}""")
        assertNotNull(ForgeApiAccess.denial(m, "composer.read"))
        m.getJSONArray("permissions").put("conversation.read")
        assertNull(ForgeApiAccess.denial(m, "composer.read"))
    }
}
