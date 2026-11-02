package com.openminis.app.forge

import org.json.JSONArray
import org.json.JSONObject

/** Forge API 2 checks capability declarations AND permissions for every request. */
object ForgeApiAccess {
    val methods = mapOf(
        "storage.get" to "storage", "storage.set" to "storage", "storage.delete" to "storage",
        "settings.get" to "settings", "events.emit" to "events",
        "composer.read" to "composer", "composer.insert" to "composer", "composer.set" to "composer",
        "conversation.title" to "conversation", "output.append" to "output", "output.clear" to "output", "output.open" to "output",
        "browser.open" to "browser", "panels.open" to "panels", "panels.close" to "panels",
        "ui.register" to "ui", "ui.unregister" to "ui",
    )
    val available = methods.values.toSet().associateWith { 1 }
    fun denial(manifest: JSONObject, method: String, surface: String? = null): String? {
        if (method in setOf("context.get", "metadata.get", "capabilities.get")) return null
        val capability = methods[method] ?: return "Android 宿主不支持 $method"
        val permissions = strings(manifest.optJSONArray("permissions"))
        val declared = manifest.optJSONArray("capabilities")
        if (declared != null) {
            val value = (0 until declared.length()).map { declared.getJSONObject(it) }.find { it.optString("name") == capability }
            if (value == null || value.optInt("version", 1) != 1) return "插件未声明能力 $capability v1"
        }
        if (capability == "ui" && surface == null) {
            return if (permissions.any { it in setOf("sidebar", "toolbar", "composer") }) null else "插件缺少界面位置权限"
        }
        if (capability == "ui" && surface !in setOf("sidebar", "toolbar", "composer")) return "不支持的界面位置"
        val permission = when (capability) { "conversation" -> "conversation.title"; "ui" -> requireNotNull(surface); else -> capability }
        if (permission !in permissions) return "插件缺少权限 $permission"
        if (capability in setOf("composer", "conversation", "output", "browser") && "conversation.read" !in permissions) return "插件缺少权限 conversation.read"
        return null
    }
    fun strings(array: JSONArray?): Set<String> = if (array == null) emptySet() else (0 until array.length()).map { array.getString(it) }.toSet()
}
