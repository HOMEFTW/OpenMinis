package com.openminis.app.forge

import android.app.Application
import com.openminis.app.data.repository.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The parent Android build runs this test; standalone JVM checks cover the storage separately. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ForgeSkillsRepositoryTest {
    private lateinit var context: Application

    @Before fun mountRealBundledAssets() {
        context = RuntimeEnvironment.getApplication()
        // This module has no AGP unit-test resource APK. Robolectric 4.17's
        // Asset.createFromFile is unsupported; its ZIP-backed loader is supported.
        // Package the actual bundle bytes without changing all existing test configurations.
        val main = listOf("src/main", "app/src/main", "src/android/app/src/main")
            .map(::File).firstOrNull { File(it, "assets/forge/bundled-skills/catalog.json").isFile }
            ?: error("Cannot locate the Android Forge source assets")
        val archive = File(context.cacheDir, "forge-test-assets.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            File(main, "assets/forge/bundled-skills").walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(main).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val cookie = ReflectionHelpers.callInstanceMethod<Int>(context.assets, "addAssetPath",
            ClassParameter.from(String::class.java, archive.canonicalPath))
        assertTrue("Real Forge assets must be mounted", cookie != 0)
        context.assets.open("forge/bundled-skills/catalog.json").bufferedReader().use {
            assertEquals(37, JSONObject(it.readText()).getJSONObject("inventory").getInt("skills"))
        }
    }

    @Test fun forgeEnablementReachesExistingPromptAndDraftLifecycleWithoutReplacingUserSkills() = runBlocking {
        val repository = SkillRepository(context)
        val existing = repository.skills.value
        val service = repository.forgeSkillsService
        assertSame(service, repository.forgeSkillsService)
        service.initialize()
        val before = repository.skillPromptFragment("real")
        assertFalse(before.orEmpty().contains("<forge_codeg_skills>"))
        service.request("skills_set", JSONObject().put("id", "brainstorming").put("enabled", true).put("scope", "global"), "real")
        assertTrue(repository.skillPromptFragment("real")!!.contains("brainstorming"))
        service.request("skills_set", JSONObject().put("id", "brainstorming").put("enabled", false).put("scope", "session"), "__new__draft")
        repository.renameSessionOverrides("__new__draft", "real")
        assertFalse(repository.skillPromptFragment("real").orEmpty().contains("<forge_codeg_skills>"))
        repository.clearSessionOverrides("real")
        assertTrue(repository.skillPromptFragment("real")!!.contains("<forge_codeg_skills>"))
        assertEquals(existing, repository.skills.value)

        // A fresh repository must see persisted enablement without opening a Forge panel.
        val reopened = SkillRepository(context)
        reopened.prepareForgeSkills()
        assertTrue(reopened.skillPromptFragment("real")!!.contains("brainstorming"))
        context.getSharedPreferences("forge_plugins", 0).edit().putBoolean("forge.skills", false).commit()
        assertFalse(reopened.skillPromptFragment("real").orEmpty().contains("<forge_codeg_skills>"))
        try {
            service.request("skill_read", JSONObject().put("id", "brainstorming").put("requireEnabled", false), "real")
            fail("Disabled plugin must reject reads")
        } catch (_: IllegalStateException) { }
        context.getSharedPreferences("forge_plugins", 0).edit().putBoolean("forge.skills", true).commit()
        reopened.prepareForgeSkills()
        assertTrue(reopened.skillPromptFragment("real")!!.contains("brainstorming"))
    }

    @Test fun corruptForgeStateDoesNotBlockPreparationOrOrdinarySkillLifecycle() = runBlocking {
        val state = File(context.filesDir, "forge/skills/enabled.json")
        state.parentFile!!.mkdirs()
        val corrupt = "{broken Forge state"
        state.writeText(corrupt)
        val repository = SkillRepository(context)
        val ordinary = requireNotNull(repository.add("ordinary-skill", "ordinary description", "ordinary body"))

        // Cold initialization fails on the real on-disk state; ordinary chat still prepares.
        repository.prepareForgeSkills()
        var prompt = repository.skillPromptFragment("real").orEmpty()
        assertTrue(prompt.contains("/var/minis/skills/${ordinary.id}/SKILL.md"))
        assertFalse(prompt.contains("<forge_codeg_skills>"))

        repository.setSessionOverride("__new__draft", ordinary.id, false)
        repository.renameSessionOverrides("__new__draft", "real")
        assertFalse(repository.isEnabledForSession(ordinary.id, "real"))
        repository.clearSessionOverrides("real")
        assertTrue(repository.isEnabledForSession(ordinary.id, "real"))
        prompt = repository.skillPromptFragment("real").orEmpty()
        assertTrue(prompt.contains("/var/minis/skills/${ordinary.id}/SKILL.md"))
        assertEquals("Isolation must not replace or reset the corrupt user's state", corrupt, state.readText())

        // Direct Forge operations retain their error; isolation never bypasses integrity checks.
        assertTrue(runCatching {
            repository.forgeSkillsService.request("skill_list", JSONObject(), "real")
        }.isFailure)
    }

    @Test fun corruptionAfterPrewarmStillPreservesTheOrdinaryPrompt() = runBlocking {
        val repository = SkillRepository(context)
        repository.forgeSkillsService.initialize()
        val ordinary = requireNotNull(repository.add("warm-ordinary", "warm description", "body"))
        File(context.filesDir, "forge/skills/enabled.json").writeText("{bad state after initialization")
        repository.prepareForgeSkills()
        val prompt = repository.skillPromptFragment("real").orEmpty()
        assertTrue(prompt.contains("/var/minis/skills/${ordinary.id}/SKILL.md"))
        assertFalse(prompt.contains("<forge_codeg_skills>"))
    }

    @Test fun forgeCancellationIsNeverConvertedToSuccess() = runBlocking {
        val repository = SkillRepository(context)
        repository.prepareForgeSkills()
        val cancellation = CancellationException("cancel Forge access")
        repository.setForgeSkillsProvider(object : ForgeSkillsProvider {
            override fun promptFragment(sessionId: String): String? = throw cancellation
            override fun renameSessionOverrides(fromDraft: String, toReal: String) = throw cancellation
            override fun clearSessionOverrides(sessionId: String) = throw cancellation
        })
        assertSame(cancellation, runCatching { repository.skillPromptFragment("real") }.exceptionOrNull())
        assertSame(cancellation, runCatching { repository.renameSessionOverrides("draft", "real") }.exceptionOrNull())
        assertSame(cancellation, runCatching { repository.clearSessionOverrides("real") }.exceptionOrNull())

        var returnedNormally = false
        val preparing = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            repository.prepareForgeSkills()
            returnedNormally = true
        }
        preparing.join()
        assertFalse("Cancelled chat preparation must not return normally", returnedNormally)
    }
}
