package com.openminis.app.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.openminis.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Legacy alias names are retained so upgrades keep a usable launcher entry. */
object AppIconRepository {
    private const val PREFS = "app_icon_prefs"
    private const val KEY_SELECTED_ID = "selected_icon_id"
    private const val PACKAGE_NAME = "com.openminis.app"

    enum class Variant(val id: String, val aliasClass: String, val iconRes: Int, val notificationRes: Int) {
        Gpt("gpt", "$PACKAGE_NAME.MainActivityIconAuto", R.mipmap.ic_launcher, R.drawable.brand_gpt_notification),
        DeepSeek("deepseek", "$PACKAGE_NAME.MainActivityIconLight", R.mipmap.ic_launcher_classic_light, R.drawable.brand_deepseek_notification),
        Claude("claude", "$PACKAGE_NAME.MainActivityIconDark", R.mipmap.ic_launcher_classic_dark, R.drawable.brand_claude_notification);

        companion object {
            // Previous Auto/Light/Dark choices were themes, not AI identities.
            fun fromId(id: String?): Variant = entries.firstOrNull { it.id == id } ?: Gpt
        }
    }

    private val selected = MutableStateFlow(Variant.Gpt)
    val selection = selected.asStateFlow()

    fun current(context: Context): Variant = Variant.fromId(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED_ID, null),
    )

    fun initialize(context: Context) {
        selected.value = current(context)
        apply(context, selected.value)
    }

    /** Enable the target first so a pre-33 launcher never sees zero enabled entries. */
    internal fun switchAliases(target: Variant, setEnabled: (Variant, Boolean) -> Unit) {
        setEnabled(target, true)
        Variant.entries.filter { it != target }.forEach { setEnabled(it, false) }
    }

    @Synchronized
    fun apply(context: Context, target: Variant): Boolean {
        val ctx = context.applicationContext
        val pm = ctx.packageManager
        val previous = try {
            Variant.entries.associateWith { pm.getComponentEnabledSetting(ComponentName(ctx, it.aliasClass)) }
        } catch (e: Exception) {
            Log.w("AppIconRepository", "Unable to read launcher icon state", e)
            return false
        }
        try {
            switchAliases(target) { variant, enabled ->
                val desired = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                val old = previous.getValue(variant)
                val wasEnabled = old == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (old == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && variant == Variant.Gpt)
                if (wasEnabled != enabled) pm.setComponentEnabledSetting(
                    ComponentName(ctx, variant.aliasClass), desired, PackageManager.DONT_KILL_APP,
                )
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED_ID, target.id).apply()
            selected.value = target
            return true
        } catch (e: Exception) {
            previous.entries.sortedBy { (_, state) -> state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
                .forEach { (variant, state) ->
                    runCatching { pm.setComponentEnabledSetting(ComponentName(ctx, variant.aliasClass), state, PackageManager.DONT_KILL_APP) }
                }
            Log.w("AppIconRepository", "Unable to change launcher icon", e)
            return false
        }
    }
}
