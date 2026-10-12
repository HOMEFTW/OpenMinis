package com.openminis.app.ui.chat

import java.io.File
import com.openminis.app.data.session.SessionResourceCopier

/** Give copied media references independent files so deleting the source cannot break the branch. */
internal class EditBranchMedia(private val mediaRoot: File, branchId: String) {
    private val copier = SessionResourceCopier(requireNotNull(mediaRoot.parentFile), branchId)
    val directory: File get() = copier.mediaDirectory
    fun rewrite(parts: String): String = copier.rewrite(parts)
    fun copyWorkspace(sourceSessionId: String, checkActive: () -> Unit = {}) = copier.copyWorkspace(sourceSessionId, checkActive)
    fun cleanup() = copier.cleanup()
}
