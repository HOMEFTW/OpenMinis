package com.openminis.app.ui.chat

import com.openminis.app.data.repository.MCPRepository.MCPServerConfig
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.data.repository.SkillRepository.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSheetActionsTest {
    private val skills = listOf(
        Skill(id = "writer", name = "Write 中文", description = "Editorial work"),
        Skill(id = "review", name = "Review", description = "Check CODE", isEnabled = false),
        Skill(id = "other", name = "Other", description = "Unrelated"),
    )

    private val servers = listOf(
        MCPServerConfig(id = "github", note = "Review CODE", command = "npx", args = listOf("--token", "arg-secret")),
        MCPServerConfig(id = "docs", url = "https://docs.example.com/mcp?custom=endpoint-secret"),
        MCPServerConfig(id = "other", note = "Unrelated", command = "python"),
    )

    @Test
    fun skillSearchMatchesNameDescriptionAndUnicodeIgnoringCaseAndSurroundingWhitespace() {
        assertEquals(listOf("review"), filterSessionSkills(skills, " code ").map { it.id })
        assertEquals(listOf("writer"), filterSessionSkills(skills, "中文").map { it.id })
        assertEquals(listOf("writer"), filterSessionSkills(skills, "WRITE").map { it.id })
    }

    @Test
    fun mcpSearchMatchesNameNoteAndSafeTransportWithoutSearchingCredentialFields() {
        assertEquals(listOf("github"), filterSessionMcps(servers, " GITHUB ").map { it.id })
        assertEquals(listOf("github"), filterSessionMcps(servers, "code").map { it.id })
        assertEquals(listOf("docs"), filterSessionMcps(servers, "DOCS.EXAMPLE.COM").map { it.id })
        assertEquals(listOf("github", "other"), filterSessionMcps(servers, "stdio").map { it.id })
        assertTrue(filterSessionMcps(servers, "endpoint-secret").isEmpty())
        assertTrue(filterSessionMcps(servers, "arg-secret").isEmpty())
    }

    @Test
    fun enableFilteredMcpsWritesOnlyMatchingIdsAndCurrentSession() {
        val overrides = mutableMapOf("github" to false, "docs" to false, "other" to false)
        val writes = mutableListOf<Triple<String, String, Boolean>>()
        setSessionSheetOverrides("draft-42", filterSessionMcps(servers, "code").map { it.id }, true, overrides) { session, id, enabled ->
            writes += Triple(session, id, enabled)
        }
        assertEquals(listOf(Triple("draft-42", "github", true)), writes)
        assertEquals(mapOf("github" to true, "docs" to false, "other" to false), overrides)
    }

    @Test
    fun disableFilteredSkillsAndClearingSearchPreserveOtherOverridesAndGlobalDefaults() {
        val overrides = mutableMapOf("writer" to true, "review" to true, "other" to false)
        val writes = mutableListOf<Triple<String, String, Boolean>>()
        setSessionSheetOverrides("session-7", filterSessionSkills(skills, "CODE").map { it.id }, false, overrides) { session, id, enabled ->
            writes += Triple(session, id, enabled)
        }
        assertEquals(skills, filterSessionSkills(skills, ""))
        assertEquals(listOf(Triple("session-7", "review", false)), writes)
        assertEquals(mapOf("writer" to true, "review" to false, "other" to false), overrides)
        assertEquals(listOf(true, false, true), skills.map { it.isEnabled })
    }

    @Test
    fun noSearchMatchesMeansNoBulkWrites() {
        val overrides = mutableMapOf("github" to false)
        setSessionSheetOverrides("session", filterSessionMcps(servers, "missing").map { it.id }, true, overrides) { _, _, _ ->
            error("No hidden server may be toggled")
        }
        assertEquals(mapOf("github" to false), overrides)
        assertEquals(servers, filterSessionMcps(servers, " \t "))
    }

    @Test
    fun failedPersistenceDoesNotDisplayAnUnwrittenOverride() {
        val overrides = mutableMapOf("writer" to true)
        val failure = assertThrows(IllegalStateException::class.java) {
            setSessionSheetOverrides("session", listOf("writer"), false, overrides) { _, _, _ ->
                throw IllegalStateException("write failed")
            }
        }
        assertEquals("write failed", failure.message)
        assertEquals(mapOf("writer" to true), overrides)
    }

    @Test
    fun urlSkillCopyUsesActualStoredSourceIncludingEscapesQueryAndFragment() {
        val source = "https://github.com/acme/repo/blob/feature/skills/实际%20name/SKILL.md?ref=branch#section"
        val skill = Skill(name = "Different display name", body = "https://example.com/incorrect", importSource = SkillRepository.ImportSource.URL, sourceURL = source)
        assertEquals(source, sessionSkillSourceUrl(skill))
    }

    @Test
    fun longPressSourceActionIsAbsentForNonUrlOrMissingSources() {
        for (source in SkillRepository.ImportSource.entries.filter { it != SkillRepository.ImportSource.URL }) {
            assertNull(sessionSkillSourceUrl(Skill(name = "Local", importSource = source, sourceURL = "https://example.com/stale")))
        }
        for (url in listOf(null, "", " \t ")) {
            assertNull(sessionSkillSourceUrl(Skill(name = "Missing", importSource = SkillRepository.ImportSource.URL, sourceURL = url)))
        }
    }
}
