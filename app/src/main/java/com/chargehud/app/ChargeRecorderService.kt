package com.chargehud.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 充电期间的后台服务：只在插电时存在，负责喂 ChargeLog 和判定四条提醒。
 * 两件事都跟悬浮窗无关 —— 窗口关着照样记档案、照样发提醒（取数器是共享状态，只能一处喂）。
 */
class ChargeRecorderService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "ChargeHud"
        const val CHANNEL_ID = "charge_record"
        const val NOTIFICATION_ID = 7006
        private const val SAMPLE_MS = 5_000L

        /** 拔线后再等一分钟收尾，确保那次会话能正常归档。 */
        private const val UNPLUGGED_GRACE_MS = 60_000L

        @Volatile
        private var instance: ChargeRecorderService? = null

        fun isAlive(): Boolean = instance != null

        fun start(context: Context) {
            val intent = Intent(context, ChargeRecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChargeRecorderService::class.java))
        }

        /**
         * 兜底拉起：插电广播在部分 ROM 上收不到（比如模拟状态变化），所以悬浮窗服务和设置页
         * 在确认正在充电时各看一眼，后台服务没跑就补一个。
         */
        fun ensure(context: Context) {
            if (!HudConfig(context).wantsChargeWatcher || isAlive()) return
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            if (plugged != 0) start(context)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var unpluggedAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        Alerts.ensureChannel(this)
        // 前台服务必须在时限内进前台，否则系统直接抛异常。
        startForeground(NOTIFICATION_ID, buildNotification())
        Prefs.of(this).registerOnSharedPreferenceChangeListener(this)
        Log.i(TAG, "充电后台服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!HudConfig(this).wantsChargeWatcher) {
            stopSelf()
            return START_NOT_STICKY
        }
        refreshNotification()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacks(ticker)
        instance = null
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == Prefs.KEY_RECORD_CHARGING) refreshNotification()
    }

    /**
     * 这条通知不能撤：实测把它 stopForeground 掉之后通知确实消失了，但息屏一分多钟进程就被冻结、
     * 5 秒采样循环停摆，提醒再也不触发。所以记录关掉、只剩提醒时把文案换成「充电提醒监控中」，
     * 说清楚这一栏是谁占着。
     */
    private fun refreshNotification() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sample() {
        val config = HudConfig(this)
        val reading = BatteryReader.read(this, config)
        if (config.recordWhileCharging) ChargeLog.onReading(this, reading)
        Alerts.evaluate(this, config, reading)
        if (reading.charging) {
            unpluggedAt = 0L
            return
        }
        if (unpluggedAt == 0L) {
            unpluggedAt = SystemClock.elapsedRealtime()
            return
        }
        if (SystemClock.elapsedRealtime() - unpluggedAt >= UNPLUGGED_GRACE_MS) stopSelf()
    }

    private fun buildNotification(): Notification {
        val recording = HudConfig(this).recordWhileCharging
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            RecentsEntry.settingsIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hud)
            .setContentTitle(
                getString(
                    if (recording) R.string.record_notification_title
                    else R.string.watch_notification_title
                )
            )
            .setContentText(
                getString(
                    if (recording) R.string.record_notification_body
                    else R.string.watch_notification_body
                )
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.record_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.record_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
