package com.openminis.app.sandbox.offload

import java.io.File

/** A PID alone can be reused; keep the Linux process start time as well. */
internal class CallerProcessGuard(
    private val pid: Int,
    private val readStat: (Int) -> String? = { value -> runCatching { File("/proc/$value/stat").readText() }.getOrNull() },
) {
    private val startTime = identity(readStat(pid))

    fun isAlive(): Boolean = startTime != null && identity(readStat(pid)) == startTime

    private fun identity(stat: String?): String? {
        val end = stat?.lastIndexOf(')') ?: return null
        if (end < 0) return null
        val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
        if (fields.size <= 19 || fields[0] in setOf("Z", "X", "x")) return null
        return fields[19].toLongOrNull()?.toString()
    }
}
