package com.openminis.app.ui.chat

import kotlinx.coroutines.Job

/** An inserted run is the foreground work while its original parent is suspended. */
internal fun stopCurrentInsertedRun(job: Job?, runIds: Set<String>, cancelRun: (String) -> Unit): Boolean {
    if (job?.isActive != true || runIds.isEmpty()) return false
    runIds.forEach(cancelRun)
    return true
}
