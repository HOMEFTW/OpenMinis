package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.provider.ToolJsonRepair
import com.openminis.app.provider.parseToolInputObject
import org.json.JSONObject

internal data class PreparedToolArguments(val json: String, val repairs: List<String>, val error: String? = null)

/** Complete structured input may be normalized; an incomplete stream is never executable. */
internal fun prepareToolArguments(
    name: String,
    args: JSONObject,
    rawTail: String?,
    tools: List<AgentToolDefinition>,
    preserveExactInput: Boolean = false,
): PreparedToolArguments {
    if (args.optBoolean("_minisHistoryTruncated", false)) {
        return PreparedToolArguments(args.toString(), emptyList(),
            "Error: This call was NOT executed because these arguments are a truncated historical record. " +
                "Read the original content and issue a new call with complete arguments.")
    }
    if (args.length() == 0 && !rawTail.isNullOrBlank()) {
        if (runCatching { parseToolInputObject(rawTail) }.isFailure) {
            return PreparedToolArguments(args.toString(), emptyList(),
                "Error: This call was NOT executed because its arguments were incomplete or invalid JSON. " +
                    "Re-issue the call with complete arguments; split large operations into smaller calls.")
        }
    }
    // The normal preflight will reject missing fields. Mutating signed input
    // here would invalidate the opaque replay and lose the thinking signature.
    if (preserveExactInput) return PreparedToolArguments(args.toString(), emptyList())
    val repairs = ToolJsonRepair.repair(name, args, rawTail, tools)
    val truncated = repairs.any { it.startsWith("truncation+") && it != "truncation+noop" }
    return PreparedToolArguments(args.toString(), repairs, if (truncated)
        "Error: This call was NOT executed because its argument stream was truncated. Re-issue it with complete arguments."
    else null)
}
