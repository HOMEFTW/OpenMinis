package com.openminis.app.ui.components

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.app.ActivityOptionsCompat
import com.openminis.app.R
import com.openminis.app.util.launchSafely
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Real picker contracts with no resolving activity, adapted to the fork's launcher. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
class SafePickerLaunchRobolectricTest {
    private fun activity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().also {
            shadowOf(it.application).checkActivities(true)
        }

    // These tests intentionally have no app resources; supply only the toast label.
    private fun pickerContext(base: Context): Context = object : ContextWrapper(base) {
        private val pickerResources = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence =
                if (id == R.string.build34_picker_unavailable) "Picker unavailable" else super.getText(id)
        }
        override fun getResources(): Resources = pickerResources
    }

    private fun <I> missingPicker(contract: ActivityResultContract<I, *>, input: I) {
        val activity = activity()
        var results = 0
        val launcher = activity.activityResultRegistry.register("missing", contract) { results++ }
        assertFalse(launcher.launchSafely(pickerContext(activity), input))
        assertEquals("Picker unavailable", ShadowToast.getTextOfLatestToast())
        assertEquals(0, results)
    }

    @Test fun `missing document and directory pickers visibly notify`() {
        missingPicker(ActivityResultContracts.OpenMultipleDocuments(), arrayOf("*/*"))
        missingPicker(ActivityResultContracts.OpenDocumentTree(), null)
        missingPicker(ActivityResultContracts.CreateDocument("text/plain"), "notes.txt")
    }

    @Test fun `missing image picker visibly notifies`() {
        missingPicker(ActivityResultContracts.PickVisualMedia(), PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build())
    }

    @Test fun `available picker still launches without a toast`() {
        val activity = activity()
        shadowOf(activity.application).checkActivities(false)
        val launcher = activity.activityResultRegistry.register("available", ActivityResultContracts.OpenMultipleDocuments()) {}
        assertTrue(launcher.launchSafely(pickerContext(activity), arrayOf("*/*")))
        assertEquals("android.intent.action.OPEN_DOCUMENT", shadowOf(activity).nextStartedActivityForResult.intent.action)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test fun `fork security and unregistered launcher failures also visibly notify`() {
        val context = pickerContext(activity())
        for (failure in listOf(SecurityException("blocked"), IllegalStateException("not registered"))) {
            val launcher = object : ActivityResultLauncher<String>() {
                override fun launch(input: String, options: ActivityOptionsCompat?) { throw failure }
                override fun unregister() = Unit
                override val contract: ActivityResultContract<String, *> = ActivityResultContracts.GetContent()
            }
            assertFalse(launcher.launchSafely(context, "image/*"))
            assertEquals("Picker unavailable", ShadowToast.getTextOfLatestToast())
        }
    }
}
