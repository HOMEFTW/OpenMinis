package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.data.repository.EnvVarRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Each invocation owns its process and pipes; admission is shared across sessions. */
object ExecutionCoordinator {
    data class CommandResult(val output: String, val exitCode: Int, val durationMs: Long, val queueNote: String? = null)

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null
    private val commands = SessionCommandRegistry()
    private val bootLock = Mutex()

    fun init(context: Context) { appContext = context.applicationContext }

    fun mountedSessionIdFor(sessionId: String): String = SessionWorkspaceRegistry.owner(sessionId)

    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        fsSessionId: String = sessionId,
    ): CommandResult = commands.run(sessionId) {
        bootLock.withLock {
            if (!PRootKernel.isBooted) PRootKernel.boot(appContext)
        }
        val outcome = GlobalShellThrottle.run(sessionId, isStartFailure = ::isStartFailure) {
            currentCoroutineContext().ensureActive()
            executeFresh(sessionId, command, timeout, lineCallback, fsSessionId)
        }
        when (outcome) {
            is GlobalShellThrottle.Outcome.Ran -> if (!outcome.queued) outcome.value
                else outcome.value.copy(queueNote = queueNote(outcome.queuedMs, outcome.startRetries))
            is GlobalShellThrottle.Outcome.QueueTimedOut -> CommandResult(
                "Not run: waited ${outcome.waitedMs / 1000}s for available shell processes. Nothing was executed.",
                124, outcome.waitedMs,
            )
        }
    }

    private suspend fun executeFresh(
        sessionId: String, command: String, timeout: Long, lineCallback: ((String) -> Unit)?, fsSessionId: String,
    ): CommandResult {
        val started = System.nanoTime()
        SessionWorkspaceRegistry.register(sessionId, fsSessionId)
        val built = SessionMounts.forContext(appContext, fsSessionId)
        check(built.skipped.isEmpty()) { "Cannot prepare session directories: ${built.skipped}" }
        val mounts = built.mounts
        PRootKernel.noteLegacySession(appContext, fsSessionId)
        val env = envVarRepository?.allAsDict() ?: emptyMap()
        suspend fun attempt(noSeccomp: Boolean): Pair<String, Int> =
            FreshProcessShell(appContext, sessionId, mounts, noSeccomp)
                .execute(command, timeout, env, lineCallback)
        var result = attempt(false)
        val firstMs = (System.nanoTime() - started) / 1_000_000
        if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(result.second, firstMs, result.first.isNotEmpty(), false)) {
            currentCoroutineContext().ensureActive()
            result = attempt(true)
        }
        val text = TerminalSanitizer.truncateIfNeeded(TerminalSanitizer.sanitize(result.first))
        val output = if (result.second != 0 && result.second != 124) ShellExitCode.ensureSuffix(text, result.second) else text
        return CommandResult(output, result.second, (System.nanoTime() - started) / 1_000_000)
    }

    internal fun isStartFailure(result: CommandResult): Boolean =
        result.exitCode == -1 && result.output.startsWith(FreshProcessShell.SPAWN_FAILED_PREFIX)

    internal fun queueNote(queuedMs: Long, retries: Int): String {
        val seconds = String.format(java.util.Locale.US, "%.1f", queuedMs / 1000.0)
        val retry = if (retries > 0) " Its process failed to start $retries time(s) and was retried." else ""
        return "<system-reminder>This command was queued for ${seconds}s before it started.$retry " +
            "It then ran to completion; its measured duration excludes queue time.</system-reminder>"
    }

    fun sessionDidTerminate(sessionId: String) { commands.stop(sessionId) }

    fun stopCurrentCommand(sessionId: String? = null) {
        commands.stop(sessionId)
        if (sessionId == null) ShellExecutor.destroyCurrent()
    }

    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        TerminalSession.broadcastTimezone(PRootKernel.updateTimezone())
    }

    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        TerminalSession.broadcastProxy(PRootKernel.updateProxy(appContext))
    }
}
