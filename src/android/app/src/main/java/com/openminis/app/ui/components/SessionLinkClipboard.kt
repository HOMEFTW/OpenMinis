package com.openminis.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.deeplink.SessionLink
import kotlinx.coroutines.CancellationException

internal suspend fun copySessionLinkToClipboard(
    context: Context,
    chatRepository: ChatRepository,
    sessionId: String,
) {
    val copied = try {
        if (chatRepository.getSession(sessionId) == null) {
            false
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.session_link_copy), SessionLink.create(sessionId)),
            )
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
    Toast.makeText(
        context,
        context.getString(if (copied) R.string.build34_copied else R.string.session_link_copy_failed),
        Toast.LENGTH_SHORT,
    ).show()
}
