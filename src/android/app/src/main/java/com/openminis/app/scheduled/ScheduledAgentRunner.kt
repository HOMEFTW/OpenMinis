package com.openminis.app.scheduled

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-android-scheduled-tasks-design] Headless agent launch for scheduled
 * tasks. Mirrors the shape of iOS SendPromptIntent.perform():
 *
 *  - resolve a session id (NEW_SESSION → create row; APPEND_TO → reuse)
 *  - run the prompt through the existing agent loop (via [HeadlessChatRunner])
 *  - post a "completed" notification with a deep-link back to the session
 *  - hand the result preview back to [ScheduledTaskManager] for the row
 *
 * Concurrency: serialised per-session by [HeadlessChatRunner]'s VM cache —
 * two scheduled prompts hitting the same session id are queued via
 * ChatViewModel.enqueuePrompt.
 */
object ScheduledAgentRunner {

    private const val TAG = "ScheduledAgentRunner"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L  // 10 min ceiling

    /**
     * App-scoped scope for fire-and-forget completion work when a caller asks
     * NOT to wait (the "Run now" button). Outlives the editor screen so the
     * agent loop + completion notification finish even after the user leaves.
     */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeExecutions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var deliveryQueue: ScheduledDeliveryQueue? = null

    /** Invoked by rescheduleAll on app launch as well as by a new fire. */
    fun recoverPending(context: Context) { queue(context.applicationContext) }

    @Synchronized
    private fun queue(context: Context): ScheduledDeliveryQueue {
        deliveryQueue?.let { return it }
        val prefs = context.getSharedPreferences("minis_scheduled_deliveries", Context.MODE_PRIVATE)
        val queue = ScheduledDeliveryQueue(prefs.getString("pending", null)) { json ->
            check(prefs.edit().putString("pending", json).commit()) { "Scheduled delivery persistence failed" }
        }
        deliveryQueue = queue
        bgScope.launch {
            // A claimed run may already have executed tools before the process
            // stopped. Record interruption instead of replaying uncertain work.
            for (delivery in queue.deliveries.value.filter { it.running }) {
                recordFailure(context, delivery.task, delivery.executionId, delivery.sessionId,
                    "Interrupted: app stopped before completion; check the target chat", delivery.firedAt)
                queue.finish(delivery.executionId)
            }
            while (isActive) {
                val app = context as? MinisApp
                if (app?.subsystemsReady() == true) {
                    for (delivery in queue.deliveries.value.filter { !it.running }) {
                        if (!activeExecutions.add(delivery.executionId)) continue
                        bgScope.launch {
                            try { deliver(app, delivery, queue) }
                            catch (t: kotlinx.coroutines.CancellationException) { throw t }
                            catch (t: Throwable) {
                                if (queue.deliveries.value.any { it.executionId == delivery.executionId && it.superseded }) {
                                    AppLogger.error(TAG, "cancelled delivery history write failed: ${t.message}")
                                    return@launch
                                }
                                recordFailure(app, delivery.task, delivery.executionId, delivery.sessionId,
                                    "delivery_failed: ${t.message}", delivery.firedAt)
                                queue.finish(delivery.executionId)
                                AppLogger.error(TAG, "delivery failed: ${t.message}")
                            } finally { activeExecutions.remove(delivery.executionId) }
                        }
                    }
                }
                delay(1_000)
            }
        }
        return queue
    }

    private suspend fun deliver(app: MinisApp, delivery: ScheduledDelivery, queue: ScheduledDeliveryQueue) {
        if (queue.deliveries.value.firstOrNull { it.executionId == delivery.executionId }?.superseded == true) {
            // Do not swallow a history write failure: the tombstone stays queued
            // and can be retried after restart instead of silently disappearing.
            ScheduledTaskManager(app).markFired(
                taskId = delivery.task.id, sessionId = delivery.sessionId,
                resultPreview = "Cancelled: superseded before delivery", ok = false,
                executionId = delivery.executionId, firedAt = delivery.firedAt,
            )
            queue.finish(delivery.executionId)
            return
        }
        if (app.chatRepository.getSession(delivery.sessionId) == null || ScheduledTaskManager(app).get(delivery.task.id) == null) {
            recordFailure(app, delivery.task, delivery.executionId, delivery.sessionId,
                "Target chat or scheduled task was deleted", delivery.firedAt)
            queue.finish(delivery.executionId)
            return
        }
        val prefillError = delivery.task.prefillToolCall?.validationError()
        if (prefillError != null) {
            recordFailure(app, delivery.task, delivery.executionId, delivery.sessionId, prefillError, delivery.firedAt)
            queue.finish(delivery.executionId)
            return
        }
        val result = ScheduledSessionDispatcher.dispatch(app, delivery, queue, RUN_TIMEOUT_MS) ?: return
        finishRun(app, delivery.task, delivery.sessionId, delivery.executionId, result, delivery.firedAt, delivery.scheduledFire)
        queue.finish(delivery.executionId)
    }

    /**
     * Fire a scheduled task.
     *
     * @param waitForCompletion when true, suspend until the agent loop
     *   finishes, then mark-fired + post the completion notification before
     *   returning. When false, return as soon as the session is resolved and
     *   the prompt is dispatched — the agent keeps running in the background
     *   and the completion (mark-fired + notification) is finished off an
     *   app-scoped coroutine. Mirrors iOS SendPromptIntent's waitForResult
     *   flag.
     *
     *   [GH#197] NEVER pass true from a BroadcastReceiver. Waiting here can
     *   take up to [RUN_TIMEOUT_MS] (10 min) and the wait lands on the main
     *   thread (HeadlessChatRunner.prompt/retry are
     *   `withContext(Dispatchers.Main)`), so a receiver that waits blows its
     *   ~10s broadcast budget and gets the whole process ANR-killed, along
     *   with every PRoot sandbox child. The alarm path passing the default
     *   `true` was exactly that bug. Waiting is only safe off a broadcast —
     *   e.g. the minis-scheduled CLI, which runs in its own offload thread.
     * @return the session id once the action has been DISPATCHED (resolved +
     *   prompt sent), or null when the runner couldn't even start (no provider,
     *   target chat gone, MinisApp not initialized).
     */
    suspend fun run(
        context: Context,
        task: ScheduledTask,
        waitForCompletion: Boolean = true,
        executionId: String = "scheduled_${UUID.randomUUID()}",
        scheduledFire: Boolean = false,
    ): String? {
        val firedAt = System.currentTimeMillis()
        // [T-android-scheduled-lateinit-crash-156] `as? MinisApp` only rules out
        // a null / wrong-type Application — it does NOT mean the Application is
        // INITIALIZED, which is what the old comment here claimed. Every
        // `app.chatRepository` read below goes through a lateinit getter that
        // throws UninitializedPropertyAccessException when onCreate
        // early-returned (safe-mode) or aborted partway.
        //
        // GH#156: an alarm fires while the process is in that state and the app
        // crashes. A scheduled task is the worst case for this because the alarm
        // can START the process — Android creates the Application, onCreate
        // early-returns under safe-mode, and the receiver runs against a
        // permanently half-built app with no Activity anywhere in the picture
        // (so MainActivity's guard never gets a say).
        //
        // Skipping the run is the right degradation: the task stays scheduled
        // and its next occurrence was already armed by the receiver before this
        // call, so a skipped fire self-heals on the following launch.
        val app = context.applicationContext as? MinisApp ?: run {
            AppLogger.error(TAG, "Application is not MinisApp — skipping task ${task.id}")
            recordFailure(context, task, executionId, null, "application_not_initialized")
            return null
        }
        if (!app.subsystemsReady()) {
            AppLogger.error(
                TAG,
                "MinisApp subsystems not initialized (safe-mode or failed init) — skipping task ${task.id}",
            )
            recordFailure(app, task, executionId, null, "application_not_initialized")
            return null
        }

        // Kick the FGS so the agent loop survives Doze / screen-off. The
        // existing service is idempotent and reused by chat UI; we pass a
        // generic status string so it shows up in the ongoing notification.
        try {
            AgentForegroundService.startService(
                context = app,
                sessionCount = 1,
                toolStatus = "Scheduled: ${task.label.ifBlank { "task" }}",
            )
        } catch (t: Throwable) {
            AppLogger.error(TAG, "task ${task.id} handoff failed: ${t.message}")
            recordFailure(app, task, executionId, null, "handoff_failed: ${t.message}")
            return null
        }

        val sessionId = try {
            withContext(Dispatchers.IO) { resolveSessionId(app, task) }
        } catch (t: Throwable) {
            AppLogger.error(TAG, "task ${task.id} session resolution failed: ${t.message}")
            recordFailure(app, task, executionId, null, "session_resolution_failed: ${t.message}")
            return null
        } ?: run {
            recordFailure(app, task, executionId, null, "session_not_found_or_provider_unavailable")
            return null
        }

        AppLogger.info(
            TAG,
            "running task=${task.id} label=\"${task.label}\" " +
                "mode=${task.targetMode.encode()} session=$sessionId wait=$waitForCompletion",
        )

        val queue = queue(app)
        queue.enqueue(ScheduledDelivery(task, sessionId, executionId, firedAt, scheduledFire))
        if (waitForCompletion) queue.deliveries.first { pending -> pending.none { it.executionId == executionId } }
        return sessionId
    }

    /** Persist and announce one terminal result for one trigger. */
    private suspend fun finishRun(
        app: MinisApp,
        task: ScheduledTask,
        sessionId: String,
        executionId: String,
        result: HeadlessChatRunner.PromptResult,
        firedAt: Long,
        scheduledFire: Boolean,
    ) {
        val ok = ScheduledRunPolicy.isSuccess(result.status)
        val response = result.responseText.orEmpty().ifBlank { "(no response)" }
        val preview = (if (ok) response else "${result.status}: $response").take(200)
        val recorded = ScheduledTaskManager(app).markFired(
            taskId = task.id,
            sessionId = sessionId,
            resultPreview = preview,
            ok = ok,
            executionId = executionId,
            firedAt = firedAt,
        )
        if (recorded) postCompletionNotification(app, task, sessionId, preview)
        if (scheduledFire && result.status != "Cancelled") {
            ScheduledCompletionTriggers.onScheduledRunFinished(app, task, result.responseText)
        }
    }

    private fun recordFailure(
        context: Context,
        task: ScheduledTask,
        executionId: String,
        sessionId: String?,
        reason: String,
        firedAt: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            ScheduledTaskManager(context.applicationContext).markFired(
                taskId = task.id,
                sessionId = sessionId,
                resultPreview = reason.take(200),
                ok = false,
                executionId = executionId,
                firedAt = firedAt,
            )
        }.onFailure { t ->
            AppLogger.error(TAG, "task ${task.id} failure history write failed: ${t.message}")
        }
    }

    private suspend fun resolveSessionId(app: MinisApp, task: ScheduledTask): String? {
        return when (val mode = task.targetMode) {
            is ScheduledTargetMode.ChildOfCurrent -> {
                val parent = app.chatRepository.getSession(mode.sessionId) ?: return null
                val binding = task.modelBinding ?: if (task.modelId == null) parent.modelBinding else null
                val seedModelId = binding?.let(::parseEntryIdFromBinding)
                    ?.let { id -> app.providerRepository.config.value.modelEntries.firstOrNull { it.id == id }?.model?.id }
                    ?: binding?.let(::parseGroupIdFromBinding)?.let(app.providerRepository::group)
                        ?.let { app.providerRepository.availableMemberEntries(it).firstOrNull()?.model?.id }
                    ?: task.modelId ?: parent.modelId
                val child = app.chatRepository.createSession(
                    modelId = seedModelId,
                    title = task.label.ifBlank { "Scheduled task" },
                    memoryEnabled = false,
                    parentSessionId = parent.id,
                    thinkingOverride = task.thinkingLevel?.name ?: parent.thinkingOverride,
                )
                if (binding != null) app.chatRepository.updateSessionBinding(child.id, binding, seedModelId)
                app.chatRepository.dao.updateSource(child.id, "scheduled")
                val workspaceRoot = com.openminis.app.sandbox.SessionWorkspaceRegistry.restore(parent.id) {
                    app.chatRepository.getSession(it)?.parentSessionId
                }
                com.openminis.app.sandbox.SessionWorkspaceRegistry.register(child.id, workspaceRoot)
                child.id
            }
            is ScheduledTargetMode.AppendToSession -> {
                // Follow-up: the target session must still exist. If the user
                // deleted it, abort rather than silently spawning a new chat.
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: follow-up session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            is ScheduledTargetMode.RerunMessage -> {
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: re-run session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            ScheduledTargetMode.NewSession -> {
                // Resolution priority (mirrors the UI's normal-chat boot):
                //   1. task.modelBinding (user picked a group or specific entry
                //      on the task editor) → write it through verbatim so the
                //      new session lights up the same group/entry chip.
                //   2. legacy task.modelId (pre-binding, kept for backcompat) →
                //      pin to that entry, no binding.
                //   3. default primary group → write a group binding so the
                //      session boots through the user's default group.
                //   4. first visible entry → last-resort fallback.
                val explicitBinding = task.modelBinding
                val pinnedModelId = task.modelId
                val defaultGroupId =
                    if (explicitBinding == null && pinnedModelId == null) {
                        app.providerRepository.defaultPrimaryGroupId
                    } else null

                // Derive the seed modelId for the session row (must be valid
                // even before restoreFromBinding runs).
                val seedModelId: String = pinnedModelId
                    ?: run {
                        // Try to peek a member from whichever binding we'll
                        // write — explicit takes priority, then default group.
                        val groupIdForSeed: String? = explicitBinding
                            ?.let { parseGroupIdFromBinding(it) }
                            ?: defaultGroupId
                        val entryIdForSeed: String? = explicitBinding
                            ?.let { parseEntryIdFromBinding(it) }

                        // Entry binding → use that entry's model id.
                        entryIdForSeed
                            ?.let { eid -> app.providerRepository.config.value.modelEntries.firstOrNull { it.id == eid }?.model?.id }
                            ?: groupIdForSeed
                                ?.let { gid -> app.providerRepository.group(gid) }
                                // [T-android-group-resolve-skip-uncredentialed]
                                // Credential-aware filter — a scheduled run
                                // seeded with an uncredentialed member would
                                // fail unattended, with no user around to see
                                // the auth error.
                                ?.let { g -> app.providerRepository.availableMemberEntries(g).firstOrNull()?.model?.id }
                    }
                    ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                    ?: run {
                        AppLogger.warning(TAG, "task ${task.id}: no provider — abort")
                        return null
                    }
                val title = task.label.ifBlank { "Scheduled task" }
                val memoryOn = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(app)
                val session = app.chatRepository.createSession(
                    modelId = seedModelId,
                    title = title,
                    memoryEnabled = memoryOn,
                )
                app.chatRepository.dao.updateSource(session.id, "scheduled")

                // Write a model_binding when the task carried one OR when we
                // fell back to defaultPrimaryGroupId. Pinned modelId (no
                // binding) leaves modelBinding null so the chat layer treats
                // it as a hard-pinned entry, matching what the user picked.
                val bindingToWrite: String? = explicitBinding
                    ?: defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }
                if (bindingToWrite != null) {
                    app.chatRepository.updateSessionBinding(session.id, bindingToWrite, seedModelId)
                }
                session.id
            }
        }
    }

    // Mirrors ChatViewModel.restoreFromBinding's JSON shape. Robust against
    // malformed JSON — both parsers return null and the caller fall-backs.
    private fun parseGroupIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "group") {
            o.optString("groupId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun parseEntryIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "entry") {
            o.optString("entryId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun postCompletionNotification(
        context: Context,
        task: ScheduledTask,
        sessionId: String,
        preview: String,
    ) {
        val deepLink = Uri.parse("minis://session/$sessionId")
        val openIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            setPackage(context.packageName)
        }
        val notificationId = task.id.hashCode() and 0x7FFFFFFF
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Minis: ${task.label.ifBlank { "Scheduled task" }}"
        val notification = NotificationCompat.Builder(context, ScheduledTaskManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "POST_NOTIFICATIONS denied — completion notice suppressed")
        }
    }
}
