package com.openminis.app.ui.webview

import android.view.ViewGroup
import android.webkit.WebView
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import com.openminis.app.logging.AppLogger
import java.util.Collections
import java.util.WeakHashMap

// Main-thread-only, like WebView itself. Weak keys never retain closed pages.
private val disposedViews = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())

/** Report renderer death without bypassing each owner's teardown routine. */
object WebViewRenderProcess {
    fun handle(owner: String, detail: RenderProcessGoneDetail?): Boolean {
        val didCrash = detail?.didCrash()
        val priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            detail?.rendererPriorityAtExit()
        } else null
        AppLogger.warning(
            "WebViewRenderProcess",
            "renderer gone owner=$owner didCrash=$didCrash priorityAtExit=$priority — handled, host process kept alive",
        )
        return true
    }
}

fun WebView.onRenderProcessGoneHandled(owner: String, detail: RenderProcessGoneDetail?): Boolean =
    WebViewRenderProcess.handle(owner, detail)

fun WebView.isDisposed(): Boolean = disposedViews.contains(this)

/** Do not stop/reload a WebView after renderer death. Owners must drop it too. */
fun WebView.disposeSafely() {
    if (!disposedViews.add(this)) return
    try { (parent as? ViewGroup)?.removeView(this) }
    catch (e: Exception) { android.util.Log.w("WebView", "Detach failed", e) }
    try { destroy() }
    catch (e: Exception) { android.util.Log.w("WebView", "Destroy failed", e) }
}

fun WebView.rendererGoneNotice() {
    android.widget.Toast.makeText(context, com.openminis.app.R.string.pr_webview_failed, android.widget.Toast.LENGTH_LONG).show()
}
