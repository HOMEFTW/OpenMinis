package com.openminis.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.agent.jobs.UserQuestionRegistry
import com.openminis.app.ui.chat.PendingUserQuestion
import com.openminis.app.ui.chat.UserQuestionKind

/** Separate actionable notifications; the ongoing FGS row still reports overall run state. */
internal class UserQuestionNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val postedSessions = mutableSetOf<String>()

    init {
        manager?.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.question_notification_channel), NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.question_notification_channel_desc) })
    }

    fun update(requests: Map<String, PendingUserQuestion>, isForeground: Boolean) {
        val notificationsEnabled = context.getSharedPreferences(com.openminis.app.tools.AgentToolSwitch.PREFS, Context.MODE_PRIVATE)
            .getBoolean("agent.tools.userAsk.notifyInBackground", true)
        val visible = if (isForeground || !notificationsEnabled ||
            !com.openminis.app.tools.AgentToolSwitch.USER_ASK.isEnabled(context)) emptyMap() else requests.filterValues {
            it.currentQuestion != null && it.remainingMs(SystemClock.elapsedRealtime()) > 0L
        }
        (postedSessions - visible.keys).forEach { manager?.cancel(tag(it), NOTIFICATION_ID) }
        visible.forEach { (sessionId, request) ->
            try { manager?.notify(tag(sessionId), NOTIFICATION_ID, build(sessionId, request)) }
            catch (_: SecurityException) { /* Notifications can be disabled; the chat card remains usable. */ }
        }
        postedSessions.clear()
        postedSessions.addAll(visible.keys)
    }

    fun clear() {
        postedSessions.forEach { manager?.cancel(tag(it), NOTIFICATION_ID) }
        postedSessions.clear()
    }

    internal fun build(sessionId: String, request: PendingUserQuestion): android.app.Notification {
        val question = requireNotNull(request.currentQuestion)
        val title = request.title.ifBlank { context.getString(R.string.chat_question_title) }
        val progress = context.getString(R.string.chat_question_progress, request.currentIndex + 1, request.questions.size)
        fun label(index: Int): String = if (question.kind == UserQuestionKind.CONFIRMATION) {
            context.getString(if (index == 0) R.string.chat_question_allow else R.string.chat_question_cancel)
        } else question.options[index].label
        val body = buildString {
            append(progress).append('\n')
            if (question.header.isNotBlank()) append(question.header).append('\n')
            append(question.question)
            question.options.forEachIndexed { index, option ->
                append("\n• ").append(label(index))
                if (option.description.isNotBlank()) append(" — ").append(option.description)
            }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tool_question)
            .setContentTitle(title)
            .setContentText(question.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(sessionContentIntent(context, sessionId))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(request.remainingMs(SystemClock.elapsedRealtime()))
        // SystemUI displays at most three actions. Additional choices remain
        // accessible as RemoteInput suggestions. SystemUI needs a free-form
        // reply input to open that action; the broker still validates closed choices.
        val buttonCount = minOf(question.options.size, if (question.allowOther || question.options.size > 3) 2 else 3)
        repeat(buttonCount) { index ->
            builder.addAction(0, label(index), actionPendingIntent(context, sessionId, request, "option:$index", mutable = false))
        }
        if (question.options.isEmpty() || question.allowOther || buttonCount < question.options.size) {
            val remainingLabels = question.options.drop(buttonCount).map { it.label }.toTypedArray()
            val input = RemoteInput.Builder(REPLY_KEY)
                .setLabel(context.getString(R.string.chat_question_answer))
                .setAllowFreeFormInput(true)
                .setChoices(remainingLabels)
                .build()
            builder.addAction(NotificationCompat.Action.Builder(
                0,
                context.getString(when {
                    remainingLabels.isEmpty() -> R.string.chat_question_other_action
                    question.allowOther -> R.string.chat_question_more
                    else -> R.string.chat_question_more_options
                }),
                actionPendingIntent(context, sessionId, request, "text", mutable = true),
            ).addRemoteInput(input).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build())
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "minis_user_questions"
        const val NOTIFICATION_ID = 9003
        const val REPLY_KEY = "question_reply"
        const val ACTION_REPLY = "com.openminis.app.ANSWER_QUESTION"
        fun tag(sessionId: String): String = "question:$sessionId"

        fun sessionContentIntent(context: Context, sessionId: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.Builder().scheme("minis").authority("session").appendPath(sessionId).build()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        internal fun actionIntent(context: Context, sessionId: String, request: PendingUserQuestion, action: String): Intent =
            Intent(context, UserQuestionActionReceiver::class.java).apply {
                this.action = ACTION_REPLY
                // Extras are mutable for RemoteInput. Route identity lives in
                // already-populated data, which a fill-in Intent cannot replace.
                data = Uri.Builder().scheme("minis-question").authority("answer")
                    .appendPath(sessionId).appendPath(request.instanceId)
                    .appendPath(requireNotNull(request.currentQuestion).id).appendPath(action).build()
            }

        private fun actionPendingIntent(context: Context, sessionId: String, request: PendingUserQuestion, action: String, mutable: Boolean): PendingIntent {
            val mutability = if (!mutable) PendingIntent.FLAG_IMMUTABLE else if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, 0, actionIntent(context, sessionId, request, action), PendingIntent.FLAG_UPDATE_CURRENT or mutability)
        }
    }
}

/** Only our explicit PendingIntents can reach this non-exported receiver. */
class UserQuestionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UserQuestionNotifications.ACTION_REPLY) return
        val uri = intent.data ?: return
        if (uri.scheme != "minis-question" || uri.host != "answer") return
        val parts = uri.pathSegments
        if (parts.size != 4) return
        val (sessionId, instanceId, questionId, action) = parts
        val request = UserQuestionRegistry.pending.value[sessionId] ?: run {
            context.getSystemService(NotificationManager::class.java)?.cancel(UserQuestionNotifications.tag(sessionId), UserQuestionNotifications.NOTIFICATION_ID)
            return
        }
        if (request.instanceId != instanceId) return
        val question = request.currentQuestion?.takeIf { it.id == questionId } ?: return
        val answer = when {
            action.startsWith("option:") -> action.substringAfter(':').toIntOrNull()?.let { question.options.getOrNull(it)?.label }
            action == "text" -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(UserQuestionNotifications.REPLY_KEY)?.toString()
            else -> null
        } ?: return
        UserQuestionRegistry.answer(sessionId, instanceId, questionId, answer)
        // The FGS observes the same registry and replaces/cancels this row,
        // including the RemoteInput spinner, as soon as the answer is accepted.
    }
}
