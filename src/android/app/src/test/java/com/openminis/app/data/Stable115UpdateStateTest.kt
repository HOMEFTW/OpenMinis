package com.openminis.app.data

import android.content.Context
import com.openminis.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Stable115UpdateStateTest {
    @Test fun legacyPreferencesMigrateAndSkippedUpdatesStayHiddenWithoutLosingTheCachedResult() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("update_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("update_preferences", Context.MODE_PRIVATE).edit()
            .putString("updates.channel", "beta").putBoolean("updates.autoCheck", false).commit()
        UpdateStateStore.init(context)
        assertEquals("beta", UpdatePreferences.channel(context))
        assertFalse(UpdatePreferences.autoCheckEnabled(context))
        UpdatePreferences.setAutoCheckEnabled(context, true)
        UpdateStateStore.beginAttempt(context, 100L, UpdatePolicy.Channel.BETA, BuildConfig.VERSION_CODE)
        val available = UpdateStateStore.Available("1.99", "1.99", null, "https://example.test/app-debug.apk", 123L, null, "notes", "1.99", "")
        UpdateStateStore.markSuccess(context, 100L, "etag-1", available, false)
        assertEquals("1.99", UpdatePreferences.availableUpdate.value?.tagName)
        assertTrue(UpdateStateStore.hasUpdateBadge.value)
        UpdateStateStore.skip(context, null)
        assertNull(UpdatePreferences.availableUpdate.value)
        assertFalse(UpdateStateStore.hasUpdateBadge.value)
        assertEquals(available, UpdateStateStore.available(context))
        UpdateStateStore.beginAttempt(context, 200L, UpdatePolicy.Channel.BETA, BuildConfig.VERSION_CODE)
        assertEquals(100L, UpdateStateStore.throttleState(context).lastSuccessAt)
        UpdatePreferences.setChannel(context, "stable")
        assertNull(UpdateStateStore.etag(context))
        assertNull(UpdateStateStore.available(context))
        assertNull(UpdateStateStore.throttleState(context).lastAttemptAt)
    }
}
