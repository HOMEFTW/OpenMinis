package com.openminis.app.sandbox

/** Keep control/status outside stdout, and isolate each command in its own process group. */
internal object ForegroundCommandGroup {
    const val SETSID = "/usr/bin/setsid"

    fun wrapForFreshProcess(command: String, statusPath: String): List<String> =
        listOf(SETSID, "/bin/sh", "-c", FRESH_RUNNER, "sh", command, statusPath)

    // The command and status path are positional arguments, never interpolated as shell code.
    const val FRESH_RUNNER =
        "printf '%s' \"\$\$\" > \"\$2.pid\"; /bin/sh -c \"\$1\"; rc=\$?; printf '%s' \"\$rc\" > \"\$2\"; exit \$rc"
}
