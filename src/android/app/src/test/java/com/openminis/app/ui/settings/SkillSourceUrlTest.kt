package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.data.repository.SkillRepository.ImportSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-skill-copy-source-url] Copy actions on the Skills screens copy
 * the URL a skill was installed from - and is offered only when there is one.
 */
class SkillSourceUrlTest {

    private fun skill(source: ImportSource, url: String?) =
        SkillRepository.Skill(name = "s", importSource = source, sourceURL = url)

    @Test
    fun `a skill installed from a URL offers that URL`() {
        val url = "https://github.com/OpenMinis/skills/blob/main/pdf/SKILL.md"
        assertEquals(url, SkillSourceUrl.copyable(skill(ImportSource.URL, url)))
        assertEquals("surrounding whitespace is not copied", url, SkillSourceUrl.copyable(skill(ImportSource.URL, "  $url\n")))
    }

    @Test
    fun `a URL import whose URL was never persisted offers nothing`() {
        assertNull(SkillSourceUrl.copyable(skill(ImportSource.URL, null)))
        assertNull(SkillSourceUrl.copyable(skill(ImportSource.URL, "")))
        assertNull(SkillSourceUrl.copyable(skill(ImportSource.URL, "   ")))
    }

    @Test
    fun `file, bundled and session skills offer nothing, even with a leftover URL`() {
        // importFromContent keeps the old source_url when a URL skill is
        // re-imported from a file; that skill no longer comes from the URL.
        for (source in listOf(ImportSource.FILE, ImportSource.BUNDLED, ImportSource.SESSION)) {
            assertNull("$source", SkillSourceUrl.copyable(skill(source, null)))
            assertNull("$source", SkillSourceUrl.copyable(skill(source, "https://example.com/SKILL.md")))
        }
    }

    @Test
    fun `both settings surfaces expose the conditional copy action`() {
        val screen = ProductionSources.read("ui/settings/SkillsManagementScreen.kt")
        assertEquals(2, Regex("""val sourceUrl = SkillSourceUrl\.copyable\(skill\)""").findAll(screen).count())
        assertEquals(2, Regex("""if \(sourceUrl != null\)""").findAll(screen).count())
        assertEquals(2, Regex("""SkillSourceUrl\.copy\(context, sourceUrl\)""").findAll(screen).count())
        assertEquals(2, Regex("""contentDescription = stringResource\(R.string.skill_copy_source_url\)""").findAll(screen).count())
    }

    @Test
    fun `copy labels are localized in every shipped locale`() {
        val res = java.io.File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res")
        val locales = res.listFiles()!!.filter {
            it.name.startsWith("values") && (java.io.File(it, "strings.xml").isFile || java.io.File(it, "build34_translations.xml").isFile)
        }
        assertTrue(locales.size >= 23)
        for (locale in locales) {
            val xml = java.io.File(locale, "stable115_ui_links.xml").readText()
            for (key in listOf("skill_copy_source_url", "skill_source_url_copied")) {
                assertTrue("$locale/$key", Regex("""<string name="$key">.+?</string>""").containsMatchIn(xml))
            }
        }
    }
}
