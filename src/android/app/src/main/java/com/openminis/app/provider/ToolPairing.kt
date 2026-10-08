package com.openminis.app.provider

/** Responses stores call_id|item_id; only call_id identifies a tool invocation. */
internal object ToolPairing {
    fun key(id: String): String = id.substringBefore('|')
}
