package com.openminis.app.data

import org.junit.Assert.*
import org.junit.Test

class PayloadSizeAuditTest {
    @Test fun auditsMismatchWithoutChangingTheImageBudget() {
        val image = PayloadSizeAudit.audit(1_030_449, 267, 1_048_576)
        assertTrue(image.suspicious)
        assertTrue(image.dangerous)
        assertEquals(1_030_449, image.bytes)
        assertEquals(267, image.estimatedTokens)
        assertFalse(PayloadSizeAudit.audit(100, 1, 1000).suspicious)
        assertFalse(PayloadSizeAudit.audit(32768, 0, 0).dangerous)
        assertTrue(PayloadSizeAudit.audit(32768, 0, 0).suspicious)
    }

    @Test fun ordinaryTextIsNotSuspiciousButLargePartsRemainVisibleToAudit() {
        assertFalse(PayloadSizeAudit.audit(40000, 12000, 100000).suspicious)
        assertFalse(PayloadSizeAudit.audit(40000, 12000, 100000).dangerous)
        assertTrue(PayloadSizeAudit.audit(50000, 12000, 100000).dangerous)
        assertTrue(PayloadSizeAudit.audit(100 * 1024 * 1024, 4000, 1000000).suspicious)
    }

    @Test fun utf8ByteCountMatchesTheEncoderIncludingNonAsciiAndMalformedSurrogates() {
        for (s in listOf("", "hello", "中文日文", "🚀", "\uD800", "\uDC00", "\uD800a", "éç中文🚀")) {
            assertEquals(s, s.toByteArray(Charsets.UTF_8).size, PayloadSizeAudit.utf8Bytes(s))
        }
    }
}
