package com.openminis.app.service

import com.openminis.app.ui.chat.PendingUserQuestion
import com.openminis.app.ui.chat.UserQuestionPrompt
import org.junit.Assert.*
import org.junit.Test

class UserQuestionNotificationTimeoutTest {
    private fun request(timeout: Long, deferred: Boolean = false, deadline: Long = 1000) =
        PendingUserQuestion("ask", listOf(UserQuestionPrompt("q", "Question")), timeoutMs = timeout,
            deferred = deferred, expiresAtElapsedMs = deadline)

    @Test fun unlimitedAndDeferredCardsNeverSetAnAndroidTimeout() {
        assertNull(UserQuestionNotifications.timeoutAfterMs(request(0), 100))
        assertNull(UserQuestionNotifications.timeoutAfterMs(request(500, deferred = true), 100))
        assertNull(UserQuestionNotifications.timeoutAfterMs(request(Long.MAX_VALUE, deadline = Long.MAX_VALUE), 0))
    }

    @Test fun finiteCountdownKeepsItsActualRemainingTimeout() {
        assertEquals(900L, UserQuestionNotifications.timeoutAfterMs(request(1000), 100))
        assertNull(UserQuestionNotifications.timeoutAfterMs(request(1000), 1000))
    }

    @Test fun expirationObserverIgnoresInfiniteWaitsAndRetainsDeferredRetentionExpiry() {
        assertNull(UserQuestionNotifications.nextExpirationDelayMs(listOf(request(0)), 100))
        assertEquals(900L, UserQuestionNotifications.nextExpirationDelayMs(listOf(request(0), request(0, deferred = true)), 100))
        assertEquals(200L, UserQuestionNotifications.nextExpirationDelayMs(listOf(request(0), request(500, deadline = 300)), 100))
    }
}
