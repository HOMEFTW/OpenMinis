package com.openminis.app.config

import com.openminis.app.config.collections.ModelsCollection
import com.openminis.app.config.fields.ReadOnlyField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-config-path-dotted-id] OpenMinis#390 — config paths address an entry by
 * its RAW id, dots included. Android half of iOS cef38f13b
 * (MinisTests/Standalone/ConfigPathSplitTests.swift); the fixture table and
 * the expected message texts are preserved as Android behavioral fixtures.
 */
class ConfigPathSplitTest {

    private val u = "05B474B3-C297-48C3-A239-688569B3F872"

    // -- Split: the fixture table shared with iOS ---------------------------------

    @Test
    fun `split fixture table (shared with iOS)`() {
        val table: List<Pair<String, Triple<String, String, String>?>> = listOf(
            "models.$u/mimo-v2.6-pro.contextWindow" to Triple("models", "$u/mimo-v2.6-pro", "contextWindow"),
            "models.$u/kimi-k3.contextWindow" to Triple("models", "$u/kimi-k3", "contextWindow"),
            "models.$u/glm-5.1.isHidden" to Triple("models", "$u/glm-5.1", "isHidden"),
            "models.$u/01-ai/yi-large.displayName" to Triple("models", "$u/01-ai/yi-large", "displayName"),
            "models.$u/my.model.v2.displayName" to Triple("models", "$u/my.model.v2", "displayName"),
            "subagents.builtin.general.instructions" to Triple("subagents", "builtin.general", "instructions"),
            "models.$u/mimo-v2~d6-pro.contextWindow" to Triple("models", "$u/mimo-v2~d6-pro", "contextWindow"),
            "models" to null,
            "models.$u" to null,
            "models..x" to null,
            "models.$u." to null,
            ".x.y" to null,
        )
        for ((path, want) in table) {
            assertEquals(path, want, ConfigRegistry.splitCollectionPath(path))
        }
    }

    @Test
    fun `the old splitter cut the reported id at its first dot (the bug)`() {
        val old = "models.$u/mimo-v2.6-pro.contextWindow".split('.', limit = 3)
        assertEquals("$u/mimo-v2", old[1])
        assertEquals("6-pro.contextWindow", old[2])
    }

    // -- End to end through a real registry ---------------------------------------

    /**
     * Behaves like ModelsCollection: canonical paths carry the RAW id, and a
     * path id resolves raw first, then by the legacy `~d` decode (the REAL
     * ModelsCollection.lookupIn rule).
     */
    private class FakeModels(private val ids: List<String>) : ConfigCollection {
        override val basePath = "models"
        override val displayName = "LLM models"
        override val description = "test"
        override fun childIds() = ids
        override fun fields(forId: String): List<ConfigField> {
            val id = ModelsCollection.lookupIn(forId) { c -> c.takeIf { it in ids } } ?: return emptyList()
            return LEAVES.map { leaf ->
                ReadOnlyField(
                    path = "models.$id.$leaf", displayName = leaf, description = leaf,
                    valueSchema = ConfigSchema.Str(), reader = { ConfigValue.Str(id) },
                )
            }
        }
        override fun add(payload: ConfigValue) = error("unused")
        override fun remove(id: String) = error("unused")

        companion object {
            val LEAVES = listOf(
                "providerInstanceId", "modelId", "isCustom", "displayName", "maxOutputTokens",
                "isHidden", "modalities", "modalitiesOverride", "contextWindow",
                "contextWindowOverride", "supportsTools", "supportsVision",
            )
        }
    }

    private val registry = ConfigRegistry().apply {
        register(FakeModels(listOf("$u/mimo-v2.6-pro", "$u/glm-5.1", "$u/kimi-k3", "$u/lit~deral")))
        register(
            ReadOnlyField(
                path = "appearance.theme", displayName = "Theme", description = "",
                valueSchema = ConfigSchema.Str(), reader = { ConfigValue.Str("dark") },
            ),
        )
    }

    private fun resolved(path: String) = registry.resolveField(path)?.path

    @Test
    fun `the reported dotted id resolves (the #390 acceptance case)`() {
        assertEquals(
            "models.$u/mimo-v2.6-pro.contextWindow",
            resolved("models.$u/mimo-v2.6-pro.contextWindow"),
        )
        assertEquals("models.$u/glm-5.1.isHidden", resolved("models.$u/glm-5.1.isHidden"))
    }

    @Test
    fun `the dot-free control still resolves`() {
        assertNotNull(resolved("models.$u/kimi-k3.contextWindow"))
    }

    @Test
    fun `the legacy ~d spelling resolves to the canonical raw path`() {
        assertEquals(
            "models.$u/mimo-v2.6-pro.contextWindow",
            resolved("models.$u/mimo-v2~d6-pro.contextWindow"),
        )
    }

    @Test
    fun `an id that literally contains ~d is found raw, not decoded`() {
        assertEquals("models.$u/lit~deral.displayName", resolved("models.$u/lit~deral.displayName"))
    }

    @Test
    fun `unknown leaf and two-part paths do not resolve`() {
        assertNull(resolved("models.$u/glm-5.1.nope"))
        assertNull(resolved("models.$u/glm-5.1"))
    }

    @Test
    fun `flat fields still win, exactly as before`() {
        assertEquals("appearance.theme", resolved("appearance.theme"))
    }

    @Test
    fun `whole-path matching would still miss a legacy path (why leaf matching is needed)`() {
        val legacy = "models.$u/mimo-v2~d6-pro.contextWindow"
        val (_, id, _) = ConfigRegistry.splitCollectionPath(legacy)!!
        val children = FakeModels(listOf("$u/mimo-v2.6-pro")).fields(id)
        assertTrue(children.isNotEmpty())
        assertTrue(children.none { it.path == legacy })
    }

    // -- Guided unknown_path reasons (same texts as iOS) --------------------------

    private fun noEntry(base: String, id: String) =
        "No entry '$id' under '$base'. Run `minis-config get $base` and use an entry_id verbatim — ids may contain dots and slashes, no escaping needed: $base.<entry_id>.<field>."

    @Test
    fun `two-part path says it names an entry and gives an example`() {
        val e = registry.explainUnknownPath("models.$u/mimo-v2.6-pro")
        assertEquals(
            "'models.$u/mimo-v2.6-pro' names an entry, not a field. Append a field, e.g. " +
                "models.$u/mimo-v2.6-pro.providerInstanceId. Fields: ${FakeModels.LEAVES.joinToString(", ")}.",
            e,
        )
    }

    @Test
    fun `unknown field names the entry and lists fields`() {
        assertEquals(
            "Unknown field 'nope' for models entry '$u/glm-5.1'. Fields: ${FakeModels.LEAVES.joinToString(", ")}.",
            registry.explainUnknownPath("models.$u/glm-5.1.nope"),
        )
    }

    @Test
    fun `unknown entry points at get models and says no escaping`() {
        assertEquals(
            noEntry("models", "$u/nosuch-1.0"),
            registry.explainUnknownPath("models.$u/nosuch-1.0.contextWindow"),
        )
        assertEquals(noEntry("models", "nosuch"), registry.explainUnknownPath("models.nosuch"))
    }

    @Test
    fun `unknown topic points at list-topics`() {
        assertEquals("No topic 'modles'. Run `minis-config list-topics`.", registry.explainUnknownPath("modles.x.y"))
    }

    @Test
    fun `an unknown field on a flat topic keeps the plain reason`() {
        assertEquals("No registered field at 'appearance.nope'.", registry.explainUnknownPath("appearance.nope"))
    }

}
