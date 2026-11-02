package com.openminis.app.ui.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [T-md-preview-links] Links inside a markdown file preview: relative paths
 * resolve against the document's folder; anything with a scheme or a guest
 * absolute path goes to the chat link resolver.
 */
class MarkdownPreviewLinksTest {
    private val doc = File("/data/s/workspace/proj/README.md").absoluteFile

    private fun relative(path: String) = MarkdownPreviewLinkTarget.Relative(File(path).absoluteFile.normalize())

    @Test
    fun relativePathsResolveAgainstTheDocumentFolder() {
        assertEquals(relative("/data/s/workspace/proj/docs/guide.md"), MarkdownPreviewLinks.target("docs/guide.md", doc))
        assertEquals(relative("/data/s/workspace/proj/docs/guide.md"), MarkdownPreviewLinks.target("./docs/guide.md", doc))
        assertEquals(relative("/data/s/workspace/top.md"), MarkdownPreviewLinks.target("../top.md", doc))
    }

    @Test
    fun encodedNamesFragmentsAndQueriesAreHandled() {
        assertEquals(
            relative("/data/s/workspace/proj/docs/中文 指南.md"),
            MarkdownPreviewLinks.target("docs/%E4%B8%AD%E6%96%87%20%E6%8C%87%E5%8D%97.md", doc),
        )
        assertEquals(relative("/data/s/workspace/proj/docs/guide.md"), MarkdownPreviewLinks.target("docs/guide.md#install", doc))
        assertEquals(relative("/data/s/workspace/proj/a+b.md"), MarkdownPreviewLinks.target("a+b.md?x=1", doc))
    }

    @Test
    fun anchorsAndEmptyLinksAreNoOps() {
        assertEquals(MarkdownPreviewLinkTarget.Ignore, MarkdownPreviewLinks.target("#install", doc))
        assertEquals(MarkdownPreviewLinkTarget.Ignore, MarkdownPreviewLinks.target("  ", doc))
    }

    @Test
    fun schemesAndGuestAbsolutePathsGoToTheChatResolver() {
        for (url in listOf(
            "minis://workspace/proj/docs/guide.md",
            "/var/minis/workspace/proj/notes.txt",
            "/etc/motd",
            "file:///data/x.md",
            "https://example.com/a.md",
            "mailto:a@example.com",
        )) {
            assertEquals(url, MarkdownPreviewLinkTarget.Chat(url), MarkdownPreviewLinks.target(url, doc))
        }
    }
    @Test
    fun sessionMountIdentityComesFromThePreviewedFile() {
        val files = File(System.getProperty("java.io.tmpdir"), "openminis-preview-owner").canonicalFile
        assertEquals("a", MarkdownPreviewLinks.sessionIdForFile(File(files, "minis-sessions/a/workspace/README.md"), files))
        org.junit.Assert.assertNull(MarkdownPreviewLinks.sessionIdForFile(File(files, "minis-global/skills/README.md"), files))
        org.junit.Assert.assertNull(MarkdownPreviewLinks.sessionIdForFile(File(files, "minis-sessions/../outside.md"), files))
        org.junit.Assert.assertNull(MarkdownPreviewLinks.sessionIdForFile(File(files, "minis-sessions-else/a/README.md"), files))
    }

    @Test
    fun previewLinksUseTheDocumentSessionAndTheExistingMarkdownHandler() {
        val source = com.openminis.app.ProductionSources.read("ui/sandbox/FilePreviewScreen.kt")
        org.junit.Assert.assertTrue(source.contains("CompositionLocalProvider(LocalMarkdownUrlClickHandler provides onLink)"))
        org.junit.Assert.assertTrue(source.contains("MarkdownPreviewLinks.sessionIdForFile(current.file, context.filesDir)"))
        org.junit.Assert.assertTrue(source.contains("ChatLinkResolver.resolve(previewLink.url, linkSessionId, context)"))
        org.junit.Assert.assertTrue(source.contains("ChatLinkResolver.dispatchDeepLink(context, previewLink.url)"))
    }
}
