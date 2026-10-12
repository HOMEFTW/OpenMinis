package com.openminis.app.sandbox

import kotlinx.coroutines.flow.MutableStateFlow

/** Guards PTY ownership so an old boot, reader or waiter cannot mutate a replacement. */
internal class TerminalSessionLifecycle {
    val state = MutableStateFlow(TerminalSession.State.IDLE)
    private var generation = 0L

    @Synchronized fun begin(): Long? {
        if (state.value == TerminalSession.State.BOOTING || state.value == TerminalSession.State.RUNNING) return null
        state.value = TerminalSession.State.BOOTING
        return ++generation
    }

    @Synchronized fun isCurrent(token: Long): Boolean =
        token == generation && (state.value == TerminalSession.State.BOOTING || state.value == TerminalSession.State.RUNNING)

    @Synchronized fun runningToken(): Long? =
        generation.takeIf { state.value == TerminalSession.State.RUNNING }

    @Synchronized fun withCurrent(token: Long, action: () -> Unit): Boolean {
        if (!isCurrent(token)) return false
        action()
        return true
    }

    @Synchronized fun stop(action: () -> Unit) {
        ++generation
        action()
        state.value = TerminalSession.State.STOPPED
    }
}
