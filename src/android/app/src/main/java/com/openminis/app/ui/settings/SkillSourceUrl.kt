package com.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.openminis.app.R
import com.openminis.app.data.repository.SkillRepository

/** Only URL imports have an installation source that can be copied. */
internal object SkillSourceUrl {
    fun copyable(skill: SkillRepository.Skill): String? {
        if (skill.importSource != SkillRepository.ImportSource.URL) return null
        return skill.sourceURL?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun copy(context: Context, url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(context, R.string.skill_source_url_copied, Toast.LENGTH_SHORT).show()
    }
}
