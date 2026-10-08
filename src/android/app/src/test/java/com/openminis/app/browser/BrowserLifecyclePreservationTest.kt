package com.openminis.app.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLifecyclePreservationTest {
    private fun source(path: String): String =
        File("src/main/java/com/openminis/app/$path").readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    @Test
    fun `serialized and capacity fallback paths preserve tab ownership`() {
        val pool = source("browser/BrowserTabPool.kt")
        assertTrue(pool.contains("executeSerialized(serialTabId, input, owner)"))
        assertTrue(pool.contains("executeSerialized(agentTarget, input, owner)"))
        assertTrue(pool.contains("acquireTabId = tabId, owner = owner"))
        assertTrue(pool.contains("picked ?: currentTabs.firstOrNull { mayUse(it.id, owner) }"))
        assertFalse(pool.contains("picked ?: currentTabs.firstOrNull()"))
    }

    @Test
    fun `popups inherit ownership and go through the shared tab allocator`() {
        val popup = source("browser/BrowserTabPool.kt")
            .substringAfter("private fun handleNewWindow(").substringBefore("private fun handleCloseWindow(")
        assertTrue(popup.contains("val owner = tabOwner[sourceTab.id]"))
        assertTrue(popup.contains("createTab(currentTabs, owner = owner)"))
        assertTrue(popup.contains("tabOwner[tab.id] = owner"))
        assertFalse(popup.contains("WebView(context)"))
    }

    @Test
    fun `renderer death wakes waiters and leaves the tab available for rebuilding`() {
        val callback = source("browser/BrowserUseManager.kt")
            .substringAfter("override fun onRenderProcessGone(").substringBefore("override fun shouldOverrideUrlLoading(")
        assertTrue(callback.contains("rendererFailed = true"))
        assertTrue(callback.contains("completeExceptionally(WebViewWedgedException("))
        assertTrue(callback.contains("view.disposeSafely()"))
        assertFalse(callback.contains("onCloseWindow?.invoke()"))
    }

    @Test
    fun `teardown retains the local safe disposal without driving a dead renderer`() {
        val release = source("browser/BrowserTabPool.kt")
            .substringAfter("private fun releaseTabResources(").substringBefore("private fun forgetTab(")
        assertTrue(release.contains("tab.manager.dispose()"))
        val helper = source("ui/webview/WebViewRenderProcess.kt")
            .substringAfter("fun WebView.disposeSafely()").substringBefore("fun WebView.rendererGoneNotice()")
        assertTrue(helper.contains("if (!disposedViews.add(this)) return"))
        assertFalse(helper.contains("stopLoading()"))
        assertFalse(helper.contains("loadUrl("))
    }
}
