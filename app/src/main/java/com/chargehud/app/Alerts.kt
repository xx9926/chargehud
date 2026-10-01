package com.chargehud.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * 阈值提醒：电池温度过高、插电很久仍然充得慢、充满、进入涓流，各发一条独立渠道的通知。
 * 判定都在充电期间的后台服务里跑（见 [[ChargeRecorderService]]），因此和悬浮窗开没开无关。
 */
object Alerts {

    const val CHANNEL_ID = "charge_alerts"

    private const val TEMP_NOTIFICATION_ID = 7002
    private const val SLOW_NOTIFICATION_ID = 7003
    private const val FULL_NOTIFICATION_ID = 7004
    private const val TRICKLE_NOTIFICATION_ID = 7005

    /** 电量到这一段之后功率掉下来是正常收尾（涓流），不该再报"充不进电"。 */
    private const val TRICKLE_MIN_LEVEL = 80

    /** 插电后先给 5 分钟缓冲：快充握手、屏幕亮着的时候功率本来就上不去。 */
    private const val SLOW_GRACE_MS = 5 * 60_000L
    private const val SLOW_WINDOW_MS = 60_000L
    private const val SLOW_MIN_SAMPLES = 3

    /** 温度回落到阈值以下这么多度才允许再次提醒，避免在阈值线上来回刷屏。 */
    private const val TEMP_RESET_HYSTERESIS = 2.0

    private var pluggedAt = 0L
    private var tempAlerted = false
    private var slowAlerted = false
    private var fullAlerted = false
    private var trickleAlerted = false
    private val recentWatts = ArrayDeque<LongArray>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.alert_channel_description) }
        manager.createNotificationChannel(channel)
    }

    fun evaluate(context: Context, config: HudConfig, reading: HudReading) {
        if (!reading.charging) {
            reset()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (pluggedAt == 0L) pluggedAt = now
        if (reading.watts.isNaN()) recentWatts.clear() else pushWatt(now, reading.watts)

        checkTemperature(context, config, reading)
        checkSlowCharge(context, config, reading, now)
        checkFull(context, config, reading)
        checkTrickle(context, config, reading)
    }

    /** 阈值改动或关掉提醒时把状态清掉，免得带着上一次的判定直接弹通知。 */
    fun reset() {
        pluggedAt = 0L
        tempAlerted = false
        slowAlerted = false
        fullAlerted = false
        trickleAlerted = false
        recentWatts.clear()
    }

    private fun pushWatt(now: Long, watts: Double) {
        recentWatts.addLast(longArrayOf(now, (watts * 100).toLong()))
        while (recentWatts.isNotEmpty() && now - recentWatts.first()[0] > SLOW_WINDOW_MS) {
            recentWatts.removeFirst()
        }
    }

    private fun checkTemperature(context: Context, config: HudConfig, reading: HudReading) {
        val threshold = config.alertTempCelsius
        if (threshold <= 0 || reading.tempCelsius.isNaN()) return
        val temp = reading.tempCelsius
        if (tempAlerted && temp <= threshold - TEMP_RESET_HYSTERESIS) tempAlerted = false
        if (temp < threshold || tempAlerted) return
        tempAlerted = true
        notify(
            context,
            TEMP_NOTIFICATION_ID,
            context.getString(R.string.alert_temp_title, String.format(Locale.CHINA, "%.1f", temp)),
            context.getString(R.string.alert_temp_body, threshold)
        )
    }

    private fun checkSlowCharge(context: Context, config: HudConfig, reading: HudReading, now: Long) {
        val threshold = config.alertSlowWatts
        if (threshold <= 0) return
        // 80% 以后功率本来就要降，那是收尾不是充不进电，这种情况交给涓流提醒说话。
        if (reading.levelPercent >= TRICKLE_MIN_LEVEL) return
        if (now - pluggedAt < SLOW_GRACE_MS || recentWatts.size < SLOW_MIN_SAMPLES) return
        val mean = recentWatts.wattValues().average()
        if (slowAlerted && mean > threshold * 1.5) slowAlerted = false
        if (mean >= threshold || slowAlerted) return
        slowAlerted = true
        notify(
            context,
            SLOW_NOTIFICATION_ID,
            context.getString(R.string.alert_slow_title),
            context.getString(
                R.string.alert_slow_body,
                String.format(Locale.CHINA, "%.1f", mean),
                threshold,
                (SLOW_GRACE_MS / 60_000L).toInt()
            )
        )
    }

    /** 框架报 FULL 是最准的信号；个别 ROM 不报 FULL，就退回电量 100%。 */
    private fun checkFull(context: Context, config: HudConfig, reading: HudReading) {
        if (!config.alertFullEnabled || fullAlerted) return
        val full = reading.status == BatteryManager.BATTERY_STATUS_FULL || reading.levelPercent >= 100
        if (!full) return
        fullAlerted = true
        notify(
            context,
            FULL_NOTIFICATION_ID,
            context.getString(R.string.alert_full_title),
            context.getString(R.string.alert_full_body, reading.levelPercent)
        )
    }

    /** 涓流：电量已经到高位、功率又掉到阈值以下，说明快充结束进入收尾。 */
    private fun checkTrickle(context: Context, config: HudConfig, reading: HudReading) {
        val threshold = config.alertTrickleWatts
        if (threshold <= 0 || trickleAlerted) return
        if (reading.levelPercent < TRICKLE_MIN_LEVEL) return
        if (recentWatts.size < SLOW_MIN_SAMPLES) return
        val mean = recentWatts.wattValues().average()
        if (mean >= threshold) return
        trickleAlerted = true
        notify(
            context,
            TRICKLE_NOTIFICATION_ID,
            context.getString(R.string.alert_trickle_title),
            context.getString(
                R.string.alert_trickle_body,
                reading.levelPercent,
                String.format(Locale.CHINA, "%.1f", mean)
            )
        )
    }

    private fun ArrayDeque<LongArray>.wattValues(): List<Double> = map { it[1] / 100.0 }

    private fun notify(context: Context, id: Int, title: String, body: String) {
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            RecentsEntry.settingsIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hud)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(id, notification)
    }
}
