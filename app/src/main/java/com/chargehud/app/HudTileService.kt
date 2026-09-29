package com.chargehud.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 下拉状态栏磁贴：点一下开/关悬浮窗（即需求里的“磁铁”快捷开关）。 */
class HudTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        val config = HudConfig(this)
        if (config.enabled && HudService.isAlive()) {
            HudService.shutdown(this)
        } else if (!Settings.canDrawOverlays(this)) {
            openOverlayPermissionPage()
        } else {
            config.enabled = true
            HudService.start(this)
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val active = HudConfig(this).enabled && HudService.isAlive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
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
