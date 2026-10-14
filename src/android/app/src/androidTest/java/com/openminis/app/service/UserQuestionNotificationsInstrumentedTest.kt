package com.openminis.app.service

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.agent.jobs.UserQuestionBroker
import com.openminis.app.agent.jobs.UserQuestionRegistry
import com.openminis.app.agent.jobs.UserQuestionTool
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Android Intent/RemoteInput APIs without posting alerts or contacting a model. */
@RunWith(AndroidJUnit4::class)
class UserQuestionNotificationsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun extraChoicesAndTextUseOneMutableReplyActionWithAnExplicitPrivateReceiver() = runBlocking {
        lateinit var broker: UserQuestionBroker
        broker = UserQuestionBroker(onChanged = { UserQuestionRegistry.update("notification-test", it, broker) })
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            broker.ask(UserQuestionTool.parse("batch", """{"questions":[{"id":"q","question":"Choose","options":["A","B","C","D"]},{"id":"text","question":"Explain"}]}"""))
        }
        try {
            val request = broker.pending.value!!
            val notification = UserQuestionNotifications(context).build("notification-test", request)
            assertEquals(3, NotificationCompat.getActionCount(notification))
            val reply = NotificationCompat.getAction(notification, 2)!!
            val input = reply.remoteInputs!!.single()
            assertArrayEquals(arrayOf<CharSequence>("C", "D"), input.choices)
            assertTrue(input.allowFreeFormInput)
            if (Build.VERSION.SDK_INT >= 31) {
                assertFalse(reply.actionIntent!!.isImmutable)
                assertTrue(NotificationCompat.getAction(notification, 0)!!.actionIntent!!.isImmutable)
            }
            val intent = UserQuestionNotifications.actionIntent(context, "notification-test", request, "text")
            assertEquals(UserQuestionActionReceiver::class.java.name, intent.component!!.className)
            @Suppress("DEPRECATION")
            assertFalse(context.packageManager.getReceiverInfo(intent.component!!, 0).exported)
            RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(UserQuestionNotifications.REPLY_KEY, "D") })
            UserQuestionActionReceiver().onReceive(context, intent)
            assertEquals("text", broker.pending.value!!.currentQuestion!!.id)
            assertEquals("D", broker.pending.value!!.answers["q"])
            UserQuestionActionReceiver().onReceive(context, intent) // Previous question cannot answer the next one.
            assertFalse(response.isCompleted)
            val next = broker.pending.value!!
            val textInput = NotificationCompat.getAction(UserQuestionNotifications(context).build("notification-test", next), 0)!!.remoteInputs!!.single()
            val textIntent = UserQuestionNotifications.actionIntent(context, "notification-test", next, "text")
            RemoteInput.addResultsToIntent(arrayOf(textInput), textIntent, Bundle().apply { putCharSequence(UserQuestionNotifications.REPLY_KEY, "Explanation") })
            UserQuestionActionReceiver().onReceive(context, textIntent)
            assertEquals(mapOf("q" to "D", "text" to "Explanation"), response.await())
        } finally { broker.cancel() }
    }

    @Test fun confirmationButtonsNeverAddAFreelyEditablePermissionAnswer() = runBlocking {
        lateinit var broker: UserQuestionBroker
        broker = UserQuestionBroker(onChanged = { UserQuestionRegistry.update("confirmation-test", it, broker) })
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            broker.ask(UserQuestionTool.parse("confirmation", """{"questions":[{"id":"q","question":"Proceed?","type":"confirmation"}]}"""))
        }
        try {
            val request = broker.pending.value!!
            val notification = UserQuestionNotifications(context).build("confirmation-test", request)
            assertEquals(2, NotificationCompat.getActionCount(notification))
            assertNull(NotificationCompat.getAction(notification, 0)!!.remoteInputs)
            val cancel = UserQuestionNotifications.actionIntent(context, "confirmation-test", request, "option:1")
            UserQuestionActionReceiver().onReceive(context, cancel)
            assertEquals(mapOf("q" to "Cancel"), response.await())
            assertFalse(UserQuestionRegistry.pending.value.containsKey("confirmation-test"))
        } finally { broker.cancel() }
    }

    @Test fun closedExtraChoicesOpenAReplyInputButRejectAnswersOutsideTheList() = runBlocking {
        lateinit var broker: UserQuestionBroker
        broker = UserQuestionBroker(onChanged = { UserQuestionRegistry.update("closed-choice-test", it, broker) })
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            broker.ask(UserQuestionTool.parse("closed", """{"questions":[{"id":"q","question":"Choose","allow_other":false,"options":["A","B","C","D"]}]}"""))
        }
        try {
            val request = broker.pending.value!!
            val notification = UserQuestionNotifications(context).build("closed-choice-test", request)
            val action = NotificationCompat.getAction(notification, 2)!!
            val input = action.remoteInputs!!.single()
            assertTrue(input.allowFreeFormInput)
            assertArrayEquals(arrayOf<CharSequence>("C", "D"), input.choices)
            assertEquals(context.getString(com.openminis.app.R.string.chat_question_more_options), action.title.toString())
            val intent = UserQuestionNotifications.actionIntent(context, "closed-choice-test", request, "text")
            RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(UserQuestionNotifications.REPLY_KEY, "not a choice") })
            UserQuestionActionReceiver().onReceive(context, intent)
            assertFalse(response.isCompleted)
            RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(UserQuestionNotifications.REPLY_KEY, "D") })
            UserQuestionActionReceiver().onReceive(context, intent)
            assertEquals(mapOf("q" to "D"), response.await())
        } finally { broker.cancel() }
    }
}
