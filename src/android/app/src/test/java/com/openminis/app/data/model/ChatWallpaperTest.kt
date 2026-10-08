package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWallpaperTest {
    @Test
    fun `unconfigured sessions are enabled and inherit 75 percent transparency`() {
        val state = ChatWallpaperState()
        val session = state.forSession("new")

        assertTrue(session.enabled)
        assertNull(session.transparencyOverride)
        assertEquals(75, state.defaultTransparency)
        assertEquals(75, session.effectiveTransparency(state.defaultTransparency))
        assertTrue(state.sessions.isEmpty())
    }

    @Test
    fun `session lookup preserves overrides without affecting other sessions`() {
        val configured = ChatWallpaperSession(enabled = false, transparencyOverride = 20)
        val state = ChatWallpaperState(defaultTransparency = 60, sessions = mapOf("one" to configured))

        assertEquals(configured, state.forSession("one"))
        assertFalse(state.forSession("one").enabled)
        assertEquals(20, state.forSession("one").effectiveTransparency(60))
        assertEquals(60, state.forSession("two").effectiveTransparency(60))
    }

    @Test
    fun `effective transparency retains endpoints and clamps both sources`() {
        for ((input, expected) in listOf(-1 to 0, 0 to 0, 75 to 75, 100 to 100, 101 to 100,
            Int.MIN_VALUE to 0, Int.MAX_VALUE to 100)) {
            assertEquals(expected, ChatWallpaperSession().effectiveTransparency(input))
            assertEquals(expected, ChatWallpaperSession(transparencyOverride = input).effectiveTransparency(75))
        }
        assertEquals(0, ChatWallpaperSession(transparencyOverride = 0).effectiveTransparency(100))
        assertEquals(100, ChatWallpaperSession(transparencyOverride = 100).effectiveTransparency(0))
    }

    @Test
    fun `deepseek IDs include gateway namespaces and version paths`() {
        for (id in listOf("deepseek-chat", "deepseek-reasoner", "deepseek-flash", "deepseek-v4.1",
            "deepseek-v4-pro", "deepseek/deepseek-r1", "deepseek-flash/v4.1",
            "deepseek/deepseek-flash/v4.1", "  DEEPSEEK-FLASH  ")) {
            assertEquals(id, ChatWallpaper.DEEPSEEK, resolveChatWallpaper(id))
        }
    }

    @Test
    fun `gpt IDs include compact names o series chatgpt and codex`() {
        for (id in listOf("gpt-4o", "gpt-4.1-mini", "gpt-3.5-turbo", "gpt-6-sol", "gpt-6-luna",
            "gpt6-sol", "gpt6-luna", "openai/gpt-6.1-sol", "openai/gpt-6.1-sol-2026-09-30",
            "gateway/openai/gpt6-sol", "o1", "o1-preview", "o3", "o3-pro", "openai/o4-mini",
            "chatgpt", "chatgpt-4o-latest", "codex", "codex-mini-latest", "gpt-5.3-codex",
            "  OPENAI/GPT-6.1-SOL  ")) {
            assertEquals(id, ChatWallpaper.GPT, resolveChatWallpaper(id))
        }
    }

    @Test
    fun `claude IDs include anthropic namespaces and Bedrock aliases`() {
        for (id in listOf("claude", "claude-opus-4-8", "claude-sonnet-4-6", "anthropic/claude",
            "anthropic/claude-3.5-sonnet", "anthropic.claude-3-5-sonnet-20241022-v2:0",
            "us.anthropic.claude-sonnet-4-20250514-v1:0", "eu.anthropic.claude-3-7-sonnet-20250219-v1:0",
            "apac.anthropic.claude-sonnet-4-20250514-v1:0", "global.anthropic.claude-sonnet-4-20250514-v1:0",
            "bedrock/anthropic.claude-3-haiku-20240307-v1:0", " ANTHROPIC/CLAUDE ")) {
            assertEquals(id, ChatWallpaper.CLAUDE, resolveChatWallpaper(id))
        }
    }

    @Test
    fun `unknown models and embedded family names do not select wallpaper`() {
        for (id in listOf(null, "", "  ", "unknown", "gemini-3-pro", "google/gemini-3-flash",
            "qwen3", "qwen/qwen3-coder", "openai/gemini-3-pro", "openai/qwen3", "openai/unknown",
            "some-gpt", "fake-gpt6-sol", "qwen-gpt-6.1-sol", "custom-chatgpt", "custom-codex",
            "gpt6fake", "gpt-fake", "o3fake", "chatgptfake", "codexfake", "deepseekfake",
            "fake-deepseek-flash", "claudefake", "fake-claude", "notanthropic.claude-sonnet-4",
            "gemini-3-pro-gpt", "gpt-6.1-sol/unknown", "openai/", "v4.1")) {
            assertNull(id, resolveChatWallpaper(id))
        }
    }
}
