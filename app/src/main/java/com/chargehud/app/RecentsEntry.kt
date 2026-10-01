package com.chargehud.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 「隐藏后台」的入口切换。任务进不进最近任务只能在创建那一刻决定，manifest 属性又是静态的，
 * 所以桌面入口做成两个互斥的 Activity：谁被启用，图标和所有跳转就走谁。
 */
object RecentsEntry {

    private const val SHOWN = "com.chargehud.app.SettingsShown"
    private const val HIDDEN = "com.chargehud.app.SettingsHidden"

    fun component(context: Context): String =
        if (HudConfig(context).hideFromRecents) HIDDEN else SHOWN

    /** 打开设置页必须走别名；直接 Intent(context, MainActivity) 会绕过别名，标记就丢了。 */
    fun settingsIntent(context: Context): Intent =
        Intent().setClassName(context.packageName, component(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun applyEntry(context: Context, hidden: Boolean) {
        val manager = context.packageManager
        val enable = if (hidden) HIDDEN else SHOWN
        val disable = if (hidden) SHOWN else HIDDEN
        manager.setComponentEnabledSetting(
            ComponentName(context.packageName, enable),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        manager.setComponentEnabledSetting(
            ComponentName(context.packageName, disable),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
