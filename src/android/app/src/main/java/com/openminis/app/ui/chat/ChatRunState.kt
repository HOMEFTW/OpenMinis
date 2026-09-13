package com.openminis.app.ui.chat

/** Stable outcomes for one prompt/retry/rerun invocation. */
internal enum class ChatRunStatus(val wireName: String) {
    NotStarted("NotStarted"),
    Queued("Queued"),
    Running("Running"),
    Completed("Completed"),
    Error("Error"),
    Cancelled("Cancelled"),
    Timeout("Timeout"),
    BudgetExceeded("BudgetExceeded"),
    ;

    val isTerminal: Boolean
        get() = this != Queued && this != Running

    val isSuccess: Boolean
        get() = this == Completed
}

/** Observable state belonging to exactly one agent invocation. */
internal data class ChatRunState(
    val runId: String,
    val status: ChatRunStatus = ChatRunStatus.NotStarted,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val error: String? = null,
    val assistantMessageId: String? = null,
    val queued: Boolean = status == ChatRunStatus.Queued,
)

/** Synchronous result of admitting an invocation into the VM. */
internal data class ChatRunAdmission(
    val runId: String,
    val accepted: Boolean,
    val state: ChatRunState,
)

/** Pure status selection shared by the VM and tests. */
internal object ChatRunStatePolicy {
    fun statusFor(
        accepted: Boolean,
        queued: Boolean = false,
        completed: Boolean = false,
        cancelled: Boolean = false,
        timedOut: Boolean = false,
        budgetExceeded: Boolean = false,
        error: Boolean = false,
    ): ChatRunStatus {
        if (!accepted) return ChatRunStatus.NotStarted
        if (queued) return ChatRunStatus.Queued
        if (cancelled) return ChatRunStatus.Cancelled
        if (timedOut) return ChatRunStatus.Timeout
        if (budgetExceeded) return ChatRunStatus.BudgetExceeded
        if (error) return ChatRunStatus.Error
        if (completed) return ChatRunStatus.Completed
        return ChatRunStatus.Running
    }

    /**
     * Apply one requested status transition while preserving terminal
     * outcomes. The stream cleanup path can run after cancellation or a
     * budget stop, so a late `Completed` request must leave that outcome
     * unchanged.
     */
    fun transition(
        current: ChatRunState,
        requested: ChatRunStatus,
        error: String? = null,
        nowMs: Long,
    ): ChatRunState {
        if (current.status.isTerminal && current.status != ChatRunStatus.NotStarted) {
            return current
        }
        val resolvedStatus = statusFor(
            accepted = requested != ChatRunStatus.NotStarted,
            queued = requested == ChatRunStatus.Queued,
            completed = requested == ChatRunStatus.Completed,
            cancelled = requested == ChatRunStatus.Cancelled,
            timedOut = requested == ChatRunStatus.Timeout,
            budgetExceeded = requested == ChatRunStatus.BudgetExceeded,
            error = requested == ChatRunStatus.Error,
        )
        val startedAt = current.startedAtMs
            ?: if (resolvedStatus == ChatRunStatus.Running || resolvedStatus.isTerminal &&
                resolvedStatus != ChatRunStatus.NotStarted
            ) nowMs else null
        return current.copy(
            status = resolvedStatus,
            startedAtMs = startedAt,
            finishedAtMs = resolvedStatus.takeIf { it.isTerminal }?.let { nowMs },
            error = error ?: current.error,
            queued = resolvedStatus == ChatRunStatus.Queued,
        )
    }

    /** Return only runs that have not yet claimed an assistant bubble. */
    fun runsNeedingAssistantBinding(
        runIds: Iterable<String>,
        states: Map<String, ChatRunState>,
    ): List<String> = runIds.filter { states[it]?.assistantMessageId == null }
}
