package com.openminis.app.service

import org.junit.Assert.*
import org.junit.Test

class SessionMailboxTest {
    @Test fun `oversized replies are visibly truncated and bounded`() {
        val box = SessionMailbox(null) {}
        val mail = box.enqueue("a", "b", "hi", false)
        box.claim(mail.id)
        box.finish(mail.id, "completed", "x".repeat(40_000))
        val reply = box.messages.value.single().reply
        assertTrue(reply.length <= 32_000)
        assertTrue(reply.contains("Reply truncated"))
    }
    @Test fun `defer before dispatch permits a later claim without duplicating the message`() {
        val box = SessionMailbox(null) {}
        val mail = box.enqueue("a", "b", "hello", false)
        box.claim(mail.id)
        box.defer(mail.id)
        assertEquals("queued", box.messages.value.single().status)
        assertNotNull(box.claim(mail.id))
        assertEquals(1, box.messages.value.size)
        box.finish(mail.id, "completed", "ok")
        box.defer(mail.id)
        assertNull(box.claim(mail.id))
    }
    @Test fun `only participants can read a message even with its exact id`() {
        val box = SessionMailbox(null) {}
        val mail = box.enqueue("a", "b", "private", false)
        assertEquals(listOf(mail), box.forSession("a", mail.id))
        assertEquals(listOf(mail), box.forSession("b", mail.id))
        assertTrue(box.forSession("c", mail.id).isEmpty())
        assertTrue(box.forSession("c").isEmpty())
    }

    @Test fun `manual send works without AI opt in but AI send is rejected`() {
        val box = SessionMailbox(null) {}
        box.enqueue("a", "b", "hello", false)
        assertThrows(IllegalArgumentException::class.java) { box.enqueue("a", "b", "hello", true) }
        box.setAllowed("b", true)
        assertTrue(box.enqueue("a", "b", "hello", true).fromAgent)
    }

    @Test fun `disabling AI reception revokes queued requests`() {
        val box = SessionMailbox(null) {}
        box.setAllowed("b", true)
        val mail = box.enqueue("a", "b", "hello", true)
        box.setAllowed("b", false)
        assertNull(box.claim(mail.id))
        assertEquals("failed", box.messages.value.single().status)
    }

    @Test fun `messages and replies survive reload with exact routing`() {
        var stored = ""
        val box = SessionMailbox(null) { stored = it }
        box.setAllowed("b", true)
        val mail = box.enqueue("a", "b", "hello", true)
        box.claim(mail.id)
        box.finish(mail.id, "completed", "result")
        val reloaded = SessionMailbox(stored) {}
        assertEquals(box.messages.value, reloaded.messages.value)
        assertEquals(setOf("b"), reloaded.allowed.value)
        assertEquals("result", reloaded.messages.value.single().reply)
    }

    @Test fun `claim is idempotent and cancelled work cannot be claimed`() {
        val box = SessionMailbox(null) {}
        val mail = box.enqueue("a", "b", "hello", false)
        assertNotNull(box.claim(mail.id))
        assertNull(box.claim(mail.id))
        assertThrows(IllegalArgumentException::class.java) { box.remove(mail.id, "a") }
        val queued = box.enqueue("a", "c", "hi", false)
        box.remove(queued.id, "a")
        assertNull(box.claim(queued.id))
    }

    @Test fun `recovery marks running interrupted without replaying and preserves queued`() {
        val box = SessionMailbox(null) {}
        val mail = box.enqueue("a", "b", "hello", false)
        box.claim(mail.id)
        box.enqueue("a", "c", "later", false)
        box.recover()
        assertEquals(listOf("interrupted", "queued"), box.messages.value.map { it.status })
        assertNull(box.claim(mail.id))
    }

    @Test fun `reject invalid routing oversized text and nonparticipant deletion`() {
        val box = SessionMailbox(null) {}
        assertThrows(IllegalArgumentException::class.java) { box.enqueue("a", "a", "hello", false) }
        assertThrows(IllegalArgumentException::class.java) { box.enqueue("a", "b", " ", false) }
        assertThrows(IllegalArgumentException::class.java) { box.enqueue("a", "b", "x".repeat(16_001), false) }
        val mail = box.enqueue("a", "b", "hello", false)
        assertThrows(IllegalArgumentException::class.java) { box.remove(mail.id, "c") }
        assertEquals(1, box.messages.value.size)
    }

    @Test fun `failed persistence never publishes accepted work`() {
        val box = SessionMailbox(null) { throw IllegalStateException("disk full") }
        assertThrows(IllegalStateException::class.java) { box.enqueue("a", "b", "hello", false) }
        assertTrue(box.messages.value.isEmpty())
        assertThrows(IllegalStateException::class.java) { box.setAllowed("b", true) }
        assertTrue(box.allowed.value.isEmpty())
    }

    @Test fun `queue is bounded and terminal results cannot be overwritten`() {
        val box = SessionMailbox(null) {}
        repeat(SessionMailbox.MAX_PENDING) { box.enqueue("a", "b", "$it", false) }
        assertThrows(IllegalArgumentException::class.java) { box.enqueue("a", "b", "overflow", false) }
        val first = box.messages.value.first()
        box.claim(first.id)
        box.finish(first.id, "completed", "ok")
        box.finish(first.id, "failed", "late failure")
        assertEquals("ok", box.messages.value.first().reply)
        box.enqueue("a", "b", "next", false)
    }
}
