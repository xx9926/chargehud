package com.chargehud.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 插电广播：把充电期间的后台服务拉起来，这样不显示悬浮窗也能记档案、也能发阈值提醒。
 * 拔线不处理 —— 服务会自己等一分钟收尾再退出，保证这条会话能正常归档。
 */
class ChargingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        if (!HudConfig(context).wantsChargeWatcher) return
        runCatching { ChargeRecorderService.start(context) }
    }
}
