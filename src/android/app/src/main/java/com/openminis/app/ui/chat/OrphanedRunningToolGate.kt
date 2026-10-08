package com.openminis.app.ui.chat

/** A historical tool may animate only while its owning run is still alive. */
internal object OrphanedRunningToolGate {
    fun resolve(status: ToolBlockStatus?, isLiveRun: Boolean, jobIsAlive: Boolean): ToolBlockStatus? =
        if (isSpinner(status) && !isLiveRun && !jobIsAlive) ToolBlockStatus.TIMEOUT else status

    fun isSpinner(status: ToolBlockStatus?): Boolean =
        status == ToolBlockStatus.RUNNING || status == ToolBlockStatus.STREAMING || status == ToolBlockStatus.PENDING

    fun overrideMayApply(overrideStatus: ToolBlockStatus?, isLiveRun: Boolean, jobIsAlive: Boolean): Boolean =
        !isSpinner(overrideStatus) || isLiveRun || jobIsAlive
}
