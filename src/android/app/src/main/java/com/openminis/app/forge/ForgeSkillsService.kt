package com.openminis.app.forge

import android.content.Context
import com.openminis.app.data.repository.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ForgeSkillsService(context: Context, private val skillRepository: SkillRepository) {
    private val appContext = context.applicationContext
    private fun enabled() = appContext.getSharedPreferences("forge_plugins", Context.MODE_PRIVATE).getBoolean("forge.skills", true)
    private val store by lazy {
        ForgeSkillsStore(File(appContext.filesDir, "forge")) { path ->
            appContext.assets.open("forge/bundled-skills/$path").use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "Skill asset limit exceeded" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    // Installing this delegate only assigns references. Asset verification and copies are lazy.
    private val provider = object : ForgeSkillsProvider {
        override fun promptFragment(sessionId: String): String? = if (enabled()) store.promptFragment(sessionId) else null
        override fun renameSessionOverrides(fromDraft: String, toReal: String) = store.renameSessionOverrides(fromDraft, toReal)
        override fun clearSessionOverrides(sessionId: String) = store.clearSessionOverrides(sessionId)
    }

    fun install(): ForgeSkillsProvider = provider.also { skillRepository.setForgeSkillsProvider(it) }

    /** Call once during host initialization, before sending the first agent message. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        install()
        if (enabled()) store // Await the same synchronized lazy initialization used by the delegate.
        Unit
    }

    suspend fun request(method: String, args: JSONObject, sessionId: String?): Any? = withContext(Dispatchers.IO) {
        check(enabled()) { "此 Forge 插件已停用" }
        install()
        store.request(method, args, sessionId)
    }
}
