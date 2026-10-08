package com.openminis.app.data.repository

import com.openminis.app.data.model.ChatWallpaperSession
import com.openminis.app.data.model.ChatWallpaperState
import com.openminis.app.util.MemorySharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatWallpaperRepositoryTest {
    private val prefs = MemorySharedPreferences()
    private val repo = ChatWallpaperRepository(prefs)

    private fun restarted(): ChatWallpaperRepository =
        ChatWallpaperRepository(MemorySharedPreferences(prefs.all))

    @Test
    fun `unpromoted drafts stay in memory and promotion persists their preferences`() {
        val draftId = "__new__wallpaper-test"
        repo.setSessionEnabled(draftId, false)
        repo.setSessionTransparency(draftId, 35)
        assertEquals(ChatWallpaperSession(false, 35), repo.state.value.forSession(draftId))
        assertTrue(prefs.all.isEmpty())
        assertEquals(ChatWallpaperSession(), restarted().state.value.forSession(draftId))

        repo.moveSession(draftId, "saved")
        assertFalse(repo.state.value.sessions.containsKey(draftId))
        assertEquals(ChatWallpaperSession(false, 35), restarted().state.value.forSession("saved"))
    }

    @Test
    fun `reading new sessions returns defaults without creating persisted records`() {
        assertEquals(ChatWallpaperState(), repo.state.value)
        assertEquals(ChatWallpaperSession(), repo.state.value.forSession("draft"))
        assertEquals(ChatWallpaperSession(), repo.state.value.forSession("saved"))
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `global transparency is clamped persisted and inherited`() {
        for ((input, expected) in listOf(Int.MIN_VALUE to 0, 0 to 0, 42 to 42, 100 to 100,
            Int.MAX_VALUE to 100)) {
            repo.setDefaultTransparency(input)

            assertEquals(expected, repo.state.value.defaultTransparency)
            assertEquals(expected, repo.state.value.forSession("new").effectiveTransparency(
                repo.state.value.defaultTransparency))
            assertEquals(expected, restarted().state.value.defaultTransparency)
        }
    }

    @Test
    fun `session toggles and transparency are isolated and survive restart`() {
        repo.setSessionEnabled("one", false)
        repo.setSessionTransparency("one", 20)
        repo.setSessionTransparency("two", 100)

        assertEquals(ChatWallpaperSession(false, 20), repo.state.value.forSession("one"))
        assertEquals(ChatWallpaperSession(true, 100), repo.state.value.forSession("two"))
        assertEquals(ChatWallpaperSession(), repo.state.value.forSession("three"))
        assertEquals(repo.state.value, restarted().state.value)
    }

    @Test
    fun `session transparency clamps and preserves full visibility endpoints`() {
        for ((input, expected) in listOf(-100 to 0, 0 to 0, 55 to 55, 100 to 100, 500 to 100)) {
            repo.setSessionTransparency("one", input)

            assertEquals(expected, repo.state.value.forSession("one").transparencyOverride)
            assertEquals(expected, repo.state.value.forSession("one").effectiveTransparency(75))
            assertEquals(repo.state.value, restarted().state.value)
        }
    }

    @Test
    fun `restoring global transparency retains the disabled switch and tracks later defaults`() {
        repo.setSessionEnabled("one", false)
        repo.setSessionTransparency("one", 0)
        repo.setDefaultTransparency(60)
        assertEquals(0, repo.state.value.forSession("one").effectiveTransparency(60))

        repo.setSessionTransparency("one", null)
        repo.setDefaultTransparency(35)

        val session = repo.state.value.forSession("one")
        assertFalse(session.enabled)
        assertNull(session.transparencyOverride)
        assertEquals(35, session.effectiveTransparency(repo.state.value.defaultTransparency))
        assertEquals(repo.state.value, restarted().state.value)
    }

    @Test
    fun `enabling a session retains its transparency and clearing all overrides restores defaults`() {
        repo.setSessionTransparency("one", 25)
        repo.setSessionEnabled("one", false)
        repo.setSessionEnabled("one", true)
        assertEquals(ChatWallpaperSession(true, 25), repo.state.value.forSession("one"))

        repo.setSessionTransparency("one", null)

        assertEquals(ChatWallpaperState(), repo.state.value)
        assertEquals(ChatWallpaperState(), restarted().state.value)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `changes publish new snapshots without mutating previous snapshots`() {
        val initial = repo.state.value
        repo.setSessionTransparency("draft", 30)
        val draft = repo.state.value
        repo.setSessionEnabled("draft", false)
        repo.moveSession("draft", "saved")
        repo.removeSession("saved")

        assertEquals(ChatWallpaperState(), initial)
        assertEquals(mapOf("draft" to ChatWallpaperSession(true, 30)), draft.sessions)
        assertEquals(ChatWallpaperState(), repo.state.value)
    }

    @Test
    fun `state flow emits global session restore move and removal changes`() = runTest {
        val snapshots = mutableListOf<ChatWallpaperState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.state.toList(snapshots) }

        repo.setDefaultTransparency(60)
        repo.setSessionEnabled("draft", false)
        repo.setSessionTransparency("draft", 20)
        repo.setSessionTransparency("draft", null)
        repo.moveSession("draft", "saved")
        repo.removeSession("saved")

        assertEquals(listOf(
            ChatWallpaperState(),
            ChatWallpaperState(60),
            ChatWallpaperState(60, mapOf("draft" to ChatWallpaperSession(false))),
            ChatWallpaperState(60, mapOf("draft" to ChatWallpaperSession(false, 20))),
            ChatWallpaperState(60, mapOf("draft" to ChatWallpaperSession(false))),
            ChatWallpaperState(60, mapOf("saved" to ChatWallpaperSession(false))),
            ChatWallpaperState(60),
        ), snapshots)
    }

    @Test
    fun `moving draft preferences replaces destination and survives restart`() {
        repo.setDefaultTransparency(65)
        repo.setSessionEnabled("draft", false)
        repo.setSessionTransparency("draft", 0)
        repo.setSessionTransparency("saved", 100)
        repo.setSessionTransparency("other", 40)

        repo.moveSession("draft", "saved")

        assertFalse(repo.state.value.sessions.containsKey("draft"))
        assertEquals(ChatWallpaperSession(), repo.state.value.forSession("draft"))
        assertEquals(ChatWallpaperSession(false, 0), repo.state.value.forSession("saved"))
        assertEquals(ChatWallpaperSession(true, 40), repo.state.value.forSession("other"))
        assertEquals(65, repo.state.value.defaultTransparency)
        assertEquals(repo.state.value, restarted().state.value)
    }

    @Test
    fun `moving a session with inherited transparency removes the old destination override`() {
        repo.setSessionEnabled("draft", false)
        repo.setSessionTransparency("saved", 100)

        repo.moveSession("draft", "saved")

        assertEquals(ChatWallpaperSession(false), repo.state.value.forSession("saved"))
        assertEquals(75, repo.state.value.forSession("saved").effectiveTransparency(75))
        assertEquals(repo.state.value, restarted().state.value)
    }

    @Test
    fun `moving a transparency only session clears the old destination disabled flag`() {
        repo.setSessionTransparency("draft", 25)
        repo.setSessionEnabled("saved", false)

        repo.moveSession("draft", "saved")

        assertEquals(ChatWallpaperSession(true, 25), repo.state.value.forSession("saved"))
        assertEquals(repo.state.value, restarted().state.value)
    }

    @Test
    fun `moving an absent session or to the same ID is a no-op`() {
        repo.setSessionEnabled("saved", false)
        repo.setSessionTransparency("saved", 10)
        val before = repo.state.value
        val stored = prefs.all

        repo.moveSession("missing", "saved")
        repo.moveSession("saved", "saved")

        assertEquals(before, repo.state.value)
        assertEquals(stored, prefs.all)
        assertEquals(before, restarted().state.value)
    }

    @Test
    fun `removing session preferences restores global defaults without affecting others`() {
        repo.setDefaultTransparency(45)
        repo.setSessionEnabled("one", false)
        repo.setSessionTransparency("one", 20)
        repo.setSessionTransparency("two", 30)

        repo.removeSession("one")
        repo.removeSession("missing")

        assertEquals(ChatWallpaperSession(), repo.state.value.forSession("one"))
        assertEquals(45, repo.state.value.forSession("one").effectiveTransparency(45))
        assertEquals(mapOf("two" to ChatWallpaperSession(true, 30)), repo.state.value.sessions)
        assertEquals(repo.state.value, restarted().state.value)
    }

    @Test
    fun `session IDs with storage delimiters remain distinct`() {
        val ids = listOf("", "one", "one:enabled", "one/transparency", "会话:临时/1")
        for ((index, id) in ids.withIndex()) repo.setSessionTransparency(id, index * 10)

        repo.moveSession("one:enabled", "saved:enabled/transparency")
        repo.removeSession("one/transparency")

        assertEquals(0, repo.state.value.forSession("").transparencyOverride)
        assertEquals(10, repo.state.value.forSession("one").transparencyOverride)
        assertEquals(20, repo.state.value.forSession("saved:enabled/transparency").transparencyOverride)
        assertEquals(40, repo.state.value.forSession("会话:临时/1").transparencyOverride)
        assertEquals(ChatWallpaperSession(), repo.state.value.forSession("one:enabled"))
        assertEquals(ChatWallpaperSession(), repo.state.value.forSession("one/transparency"))
        assertEquals(repo.state.value, restarted().state.value)
    }

    @Test
    fun `out of range persisted values are clamped on reload`() {
        repo.setDefaultTransparency(50)
        repo.setSessionTransparency("one", 20)
        val changedValues = prefs.all.mapValues { (_, value) -> if (value is Int) -50 else value }
        val reloaded = ChatWallpaperRepository(MemorySharedPreferences(changedValues))
        val highValues = prefs.all.mapValues { (_, value) -> if (value is Int) 150 else value }
        val highReloaded = ChatWallpaperRepository(MemorySharedPreferences(highValues))

        assertEquals(0, reloaded.state.value.defaultTransparency)
        assertEquals(0, reloaded.state.value.forSession("one").transparencyOverride)
        assertEquals(100, highReloaded.state.value.defaultTransparency)
        assertEquals(100, highReloaded.state.value.forSession("one").transparencyOverride)
    }

    @Test
    fun `wallpaper operations preserve unrelated preferences`() {
        prefs.edit().putString("theme", "dark").putInt("unrelated", 123).apply()
        repo.setSessionEnabled("draft", false)
        repo.setSessionTransparency("draft", 20)
        repo.setDefaultTransparency(90)
        repo.moveSession("draft", "saved")
        repo.removeSession("saved")

        assertEquals("dark", prefs.getString("theme", null))
        assertEquals(123, prefs.getInt("unrelated", 0))
        assertEquals(ChatWallpaperState(90), restarted().state.value)
    }
}
