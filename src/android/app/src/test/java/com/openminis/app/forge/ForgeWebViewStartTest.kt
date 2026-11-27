package com.openminis.app.forge

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ForgeWebViewStartTest {
    @Test fun waitsForAttachedNonzeroViewportThenLoadsOnce() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val container = FrameLayout(activity)
            activity.setContentView(container)
            // Robolectric's WebView provider stubs setFrame; exercise the real View
            // attach/layout callbacks used by the gate, without a mock renderer.
            val web = View(activity)
            var starts = 0
            val starter = ForgeWebViewStart(web) { starts++ }
            assertEquals(0, starts)
            container.addView(web, FrameLayout.LayoutParams(0, 0))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(web.isAttachedToWindow)
            web.layout(0, 0, 0, 0)
            assertEquals(0, starts)
            web.layout(0, 0, 360, 640)
            assertEquals("Laid-out WebView width", 360, web.width)
            assertEquals("Laid-out WebView height", 640, web.height)
            assertEquals("Attached layout must trigger startup", 1, starts)
            web.layout(0, 0, 360, 400)
            assertEquals(1, starts)
            starter.dispose()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun disposingBeforeLayoutPreventsLateNavigation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val web = View(activity)
            var starts = 0
            val starter = ForgeWebViewStart(web) { starts++ }
            starter.dispose()
            activity.setContentView(web)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(web.isAttachedToWindow)
            web.layout(0, 0, 360, 640)
            assertEquals(0, starts)
        } finally { controller.pause().stop().destroy() }
    }
}
