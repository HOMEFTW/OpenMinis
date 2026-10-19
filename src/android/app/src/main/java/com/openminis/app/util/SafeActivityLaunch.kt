package com.openminis.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.openminis.app.R

/** System document/photo pickers are optional on some Android devices. */
fun <I> ActivityResultLauncher<I>.launchSafely(context: Context, input: I): Boolean = try {
    launch(input)
    true
} catch (_: ActivityNotFoundException) {
    showPickerUnavailable(context)
} catch (_: SecurityException) {
    showPickerUnavailable(context)
} catch (_: IllegalStateException) {
    showPickerUnavailable(context)
}

private fun showPickerUnavailable(context: Context): Boolean {
    Toast.makeText(context, R.string.build34_picker_unavailable, Toast.LENGTH_LONG).show()
    return false
}
