package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageInputPreflightTest {
    private fun rejected(detail: String, images: Int = 1, status: Int? = 400): Boolean =
        ImageInputPreflight.isLikelyImageRejection(LLMError.ProviderError(detail, httpStatus = status), images)

    @Test fun genericProvider400WithImagesGetsTheHint() {
        assertTrue(rejected("bad request"))
        assertTrue(rejected("请求参数或所选模型能力不受支持", images = 3))
    }

    @Test fun noImagesOtherStatusesAndNonProviderErrorsDoNotGetTheHint() {
        for (status in listOf(null, 401, 413, 429, 500)) {
            assertFalse("status=$status", rejected("bad request", status = status))
        }
        assertFalse(rejected("bad request", images = 0))
        assertFalse(rejected("bad request", images = -1))
        assertFalse(ImageInputPreflight.isLikelyImageRejection(IllegalStateException("bad request"), 1))
    }

    @Test fun toolPairingErrorsDoNotBecomeImageErrors() {
        for (detail in listOf(
            "No tool output found for tool call call_01",
            "Invalid tool_call: missing tool_result",
            "tool use needs its result",
            "FUNCTION_CALL requires a function response",
            "工具调用缺少工具结果",
        )) {
            assertFalse(detail, rejected(detail))
        }
    }

    @Test fun contextOverflowErrorsDoNotBecomeImageErrors() {
        for (detail in listOf(
            "Maximum context length exceeded",
            "context_length_exceeded",
            "context window exceeded",
            "context_window_exceeded",
            "Context limit exceeded",
            "Prompt is too long",
            "Input too long",
            "Too many tokens",
            "Token limit exceeded",
            "Input tokens exceed the allowed maximum",
            "请求超出上下文窗口限制",
        )) {
            assertFalse(detail, rejected(detail))
        }
    }
}
