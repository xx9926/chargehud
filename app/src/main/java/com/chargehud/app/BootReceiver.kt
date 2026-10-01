package com.chargehud.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings

/** 开机后恢复上次开启的悬浮窗（小米需在“自启动”里放行本应用，否则收不到该广播）。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = HudConfig(context)
        if (config.enabled && Settings.canDrawOverlays(context)) {
            runCatching { HudService.start(context) }
        }
        // 开机时就插着电的情况收不到插电广播，这里补一次。
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        if (plugged != 0 && config.wantsChargeWatcher) {
            runCatching { ChargeRecorderService.start(context) }
        }
    }
}
