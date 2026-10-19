package com.openminis.app.ui.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewLinkResolverTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun document() = File(temporary.root, "docs/guide.md")

    @Test fun `relative paths resolve against the current document directory`() {
        val target = resolvePreviewLink(document(), "../notes/readme.md#details") as PreviewLinkTarget.LocalFile
        assertEquals(File(temporary.root, "notes/readme.md").canonicalFile, target.file)
    }

    @Test fun `encoded names preserve plus and decode spaces hash and Unicode`() {
        val target = resolvePreviewLink(document(), "子目录/a+b%20c%23d.md?download=1#heading") as PreviewLinkTarget.LocalFile
        assertEquals(File(temporary.root, "docs/子目录/a+b c#d.md").canonicalFile, target.file)
    }

    @Test fun `web URLs use the in app target and keep query and fragment`() {
        assertEquals(PreviewLinkTarget.Web("https://example.com/a?q=1#part"), resolvePreviewLink(document(), "https://example.com/a?q=1#part"))
        assertEquals(PreviewLinkTarget.Web("https://example.com/a"), resolvePreviewLink(document(), "//example.com/a"))
    }

    @Test fun `local file URLs and same document anchors do not become external intents`() {
        val file = File(temporary.root, "docs/a+b c.md").canonicalFile
        assertEquals(PreviewLinkTarget.LocalFile(file), resolvePreviewLink(document(), file.toURI().toString()))
        assertEquals(PreviewLinkTarget.Anchor("part"), resolvePreviewLink(document(), "#part"))
    }

    @Test fun `external user schemes survive and executable schemes are rejected`() {
        assertTrue(resolvePreviewLink(document(), "mailto:test@example.com") is PreviewLinkTarget.External)
        for (link in listOf("javascript:alert(1)", "data:text/html,hi", "content://other/file", "https:///broken", "%broken")) {
            assertEquals(link, PreviewLinkTarget.Unsupported, resolvePreviewLink(document(), link))
        }
    }
}
