package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class TitleResponseTest {
    @Test fun `valid JSON and fenced JSON preserve a title and folder`() {
        val json = """{"title":"修复登录问题","category":"code","folder":"工作"}"""
        for (text in listOf(json, "```json\n$json\n```")) {
            assertEquals(TitleResponse("修复登录问题", "code", "工作"), parseGeneratedTitle(text))
        }
    }

    @Test fun `broken JSON explanations and missing or invalid titles are rejected`() {
        for (text in listOf("{", "这里是标题：修复登录", "{}", """{"category":"chat"}""",
            """{"title":null}""", """{"title":42}""", """{"title":""}""",
            """{"title":"{ }"}""", """{"title":"null"}""", """{"title":"New Chat"}""",
            """{"title":"正确标题"} trailing garbage""", """{"title":"正确标题","category":"""")) {
            assertNull(text, parseGeneratedTitle(text))
        }
    }

    @Test fun `truncation never succeeds even with a complete title object`() {
        for (reason in listOf("length", "max_tokens", "incomplete")) {
            assertNull(parseGeneratedTitle("""{"title":"修复登录"}""", reason))
        }
    }

    @Test fun `unknown optional fields are ignored and title whitespace is normalized`() {
        assertEquals(TitleResponse("修复 登录", null, null),
            parseGeneratedTitle("""{"title":" 修复\n登录 ","category":"invalid","folder":null}"""))
    }

    @Test fun `escaped quotes survive parsing`() {
        assertEquals("修复 \"登录\"", parseGeneratedTitle("""{"title":"修复 \"登录\""}""")?.title)
    }
}
