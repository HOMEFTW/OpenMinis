package com.openminis.app.ui.settings

import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun mcpCheckCommand(serverId: String): String {
    require(serverId.isNotBlank() && '\u0000' !in serverId) { "Invalid MCP server id" }
    return "minis-mcp-cli refresh '" + serverId.replace("'", "'\"'\"'") + "'"
}

/** Reject error envelopes, mismatched servers, invalid counts and truncated output. */
internal fun parseMcpCheck(serverId: String, output: String, exitCode: Int): Int {
    require(exitCode == 0) { "MCP test failed (exit $exitCode): ${com.openminis.app.logging.LogRedactor.redact(output).take(500)}" }
    val json = JSONObject(output.trim())
    require(!json.has("error") && json.optString("server") == serverId) { "Unexpected MCP response" }
    val tools = json.optJSONArray("tools") ?: error("MCP response has no tools list")
    val count = json.opt("count")
    require(count is Number && count.toDouble() == tools.length().toDouble()) { "Invalid MCP tool count" }
    for (i in 0 until tools.length()) {
        require(!tools.getJSONObject(i).optString("name").isBlank()) { "Invalid MCP tool entry" }
    }
    return tools.length()
}

private val mcpCheckMutex = Mutex()

internal suspend fun runMcpConnectionCheck(serverId: String): Int = mcpCheckMutex.withLock {
    withContext(Dispatchers.IO) {
        val sessionId = "mcp-connection-check"
        try {
            val result = ExecutionCoordinator.execute(sessionId, mcpCheckCommand(serverId), timeout = 120_000L)
            parseMcpCheck(serverId, result.output, result.exitCode)
        } finally {
            ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
    }
}
