package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection

private const val MCP_NOTE_TRUNC = 200

/**
 * Bottom sheet showing all configured MCP servers with per-session
 * enable/disable toggles, search, filtered bulk actions and read-only details.
 * Mirrors [SessionSkillsSheet] and writes through [MCPRepository.setSessionOverride].
 */
@Composable
fun SessionMcpsSheet(
    sessionId: String,
    mcpRepository: MCPRepository,
    onDismiss: () -> Unit,
) {
    val servers by mcpRepository.servers.collectAsState()

    // [T-mcp-review-fixes-android] Key the seed on `servers` so the override map
    // re-derives when the live server list arrives. A keyless remember{} seeds
    // once from a possibly-empty first-composition list and then never re-runs,
    // leaving every toggle stuck at its default (the review's stale-override race).
    val overrides = remember(servers, sessionId, mcpRepository) {
        mutableStateMapOf<String, Boolean>().apply {
            for (server in servers) {
                put(server.id, mcpRepository.isEnabledForSession(server.id, sessionId))
            }
        }
    }

    var searchQuery by remember(sessionId) { mutableStateOf("") }
    val filteredServers = remember(servers, searchQuery) { filterSessionMcps(servers, searchQuery) }
    var selectedServerId by remember(sessionId) { mutableStateOf<String?>(null) }
    val selectedServer = servers.find { it.id == selectedServerId }
    val listScroll = rememberScrollState()

    fun setEnabled(ids: List<String>, enabled: Boolean) {
        setSessionSheetOverrides(sessionId, ids, enabled, overrides, mcpRepository::setSessionOverride)
    }

    StandardChatSheet(
        title = stringResource(R.string.session_mcps_title),
        onDismiss = onDismiss,
        leadingAction = if (selectedServer != null) {
            { SessionSheetBackAction { selectedServerId = null } }
        } else null,
    ) {
        if (selectedServer != null) {
            SessionMcpDetails(selectedServer)
            return@StandardChatSheet
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listScroll),
        ) {
            if (servers.isEmpty()) {
                EmptyMcpsCard()
            } else {
                DialogTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.sessionlist_search_action),
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.model_picker_search_clear))
                            }
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).padding(start = 32.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mcp_section_servers),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { setEnabled(filteredServers.map { it.id }, true) },
                        enabled = filteredServers.isNotEmpty(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.session_skills_enable_all), fontSize = 12.sp)
                    }
                    MinisTextButton(
                        onClick = { setEnabled(filteredServers.map { it.id }, false) },
                        enabled = filteredServers.isNotEmpty(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.session_skills_disable_all), fontSize = 12.sp)
                    }
                }
                if (filteredServers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_no_results, searchQuery),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                    )
                } else {
                    SettingsSection(
                        footer = stringResource(R.string.session_mcps_footer),
                    ) {
                        filteredServers.forEachIndexed { index, server ->
                            val note = sessionMcpDisplayText(server, server.note?.trim().orEmpty())
                            val sub = note
                                .ifEmpty { sessionMcpSummary(server) }
                                .let { if (it.length > MCP_NOTE_TRUNC) it.substring(0, MCP_NOTE_TRUNC) + "…" else it }
                            SettingsRow(
                                title = sessionMcpDisplayText(server, server.id),
                                subtitle = sub.takeIf { it.isNotBlank() },
                                onClick = { selectedServerId = server.id },
                                showDivider = index < filteredServers.size - 1,
                                trailing = {
                                    SettingsSwitch(
                                        checked = overrides[server.id] ?: server.enabled,
                                        onCheckedChange = { enabled -> setEnabled(listOf(server.id), enabled) },
                                    )
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun SessionMcpDetails(server: MCPRepository.MCPServerConfig) {
    val hidden = stringResource(R.string.voice_service_hidden_badge)
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection(
            header = stringResource(if (server.isStdio) R.string.mcp_transport_stdio else R.string.mcp_transport_http),
        ) {
            SessionSheetDetailField(stringResource(R.string.mcp_form_name), sessionMcpDisplayText(server, server.id))
            server.note?.takeIf { it.isNotBlank() }?.let {
                SessionSheetDetailField(stringResource(R.string.mcp_form_note), sessionMcpDisplayText(server, it))
            }
            if (server.isStdio) {
                SessionSheetDetailField(
                    stringResource(R.string.mcp_form_command),
                    sessionMcpCommand(server.command)?.let { sessionMcpDisplayText(server, it) } ?: hidden,
                )
                if (server.args.isNotEmpty()) {
                    SessionSheetDetailField(stringResource(R.string.mcp_form_args), server.args.joinToString(" ") { "••••" })
                }
                if (server.env.isNotEmpty()) {
                    SessionSheetDetailField(stringResource(R.string.mcp_form_env), server.env.keys.joinToString("\n") { "$it=••••" })
                }
                SessionSheetDetailField(
                    stringResource(R.string.mcp_form_startup_timeout),
                    (server.startupTimeoutSeconds ?: 60).toString(),
                )
            } else {
                SessionSheetDetailField(
                    stringResource(R.string.mcp_form_url),
                    sessionMcpEndpoint(server.url)?.let { sessionMcpDisplayText(server, it) } ?: hidden,
                )
                if (server.headers.isNotEmpty()) {
                    SessionSheetDetailField(stringResource(R.string.mcp_form_headers), server.headers.keys.joinToString("\n") { "$it: ••••" })
                }
            }
        }
        Spacer(modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun EmptyMcpsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                stringResource(R.string.session_mcps_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.session_mcps_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
