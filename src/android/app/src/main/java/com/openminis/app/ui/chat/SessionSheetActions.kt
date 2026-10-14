package com.openminis.app.ui.chat

import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository

internal fun filterSessionMcps(
    servers: List<MCPRepository.MCPServerConfig>,
    query: String,
): List<MCPRepository.MCPServerConfig> {
    val term = query.trim()
    if (term.isEmpty()) return servers
    return servers.filter { server ->
        sessionMcpDisplayText(server, server.id).contains(term, ignoreCase = true) ||
            sessionMcpDisplayText(server, server.note.orEmpty()).contains(term, ignoreCase = true) ||
            sessionMcpSummary(server).contains(term, ignoreCase = true)
    }
}

internal fun filterSessionSkills(
    skills: List<SkillRepository.Skill>,
    query: String,
): List<SkillRepository.Skill> {
    val term = query.trim()
    if (term.isEmpty()) return skills
    return skills.filter {
        it.name.contains(term, ignoreCase = true) || it.description.contains(term, ignoreCase = true)
    }
}

/** Writes only the visible IDs, without changing global defaults or clearing other overrides. */
internal fun setSessionSheetOverrides(
    sessionId: String,
    ids: List<String>,
    enabled: Boolean,
    overrides: MutableMap<String, Boolean>,
    persist: (String, String, Boolean) -> Unit,
) {
    for (id in ids) {
        persist(sessionId, id, enabled)
        overrides[id] = enabled
    }
}

/** Preserve the imported URL verbatim; never reconstruct it from the skill's name or body. */
internal fun sessionSkillSourceUrl(skill: SkillRepository.Skill): String? =
    skill.sourceURL?.takeIf { skill.importSource == SkillRepository.ImportSource.URL && it.isNotBlank() }
