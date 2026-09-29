package com.chargehud.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** 下拉状态栏磁贴：点一下开/关悬浮窗（即需求里的“磁铁”快捷开关）。 */
class HudTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        val config = HudConfig(this)
        if (config.enabled && HudService.isAlive()) {
            HudService.shutdown(this)
            push(active = false)
        } else if (!Settings.canDrawOverlays(this)) {
            openOverlayPermissionPage()
        } else {
            config.enabled = true
            HudService.start(this)
            push(active = true)
        }
    }

    private fun render() = render(HudConfig(this).enabled && HudService.isAlive())

    /**
     * startForegroundService 是异步的，而 onClick 就跑在主线程上，服务要等本次回调返回后才建好，
     * 此刻 isAlive() 仍是 false，所以按期望态画。HyperOS 在点击回调期间的 updateTile 会被吞掉，
     * 再往消息队列里排两次补推。
     */
    private fun push(active: Boolean) {
        render(active)
        handler.postDelayed({ render(active) }, 80L)
        handler.postDelayed({ render(active) }, 320L)
    }

    private fun render(active: Boolean) {
        val tile = qsTile ?: return
        Log.i(TAG, "磁贴渲染：${if (active) "开" else "关"}")
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }

    private companion object {
        const val TAG = "ChargeHudTile"
    }

    private fun openOverlayPermissionPage() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { startActivityAndCollapse(pending) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { startActivityAndCollapse(intent) }
        }
    }
}
