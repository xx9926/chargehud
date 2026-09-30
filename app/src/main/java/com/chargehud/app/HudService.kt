package com.chargehud.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

class HudService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "ChargeHud"
        const val CHANNEL_ID = "charge_hud"
        const val NOTIFICATION_ID = 7001

        const val ACTION_HIDE = "com.chargehud.app.action.HIDE"
        const val ACTION_TOGGLE_LOCK = "com.chargehud.app.action.TOGGLE_LOCK"

        @Volatile
        private var instance: HudService? = null

        fun isAlive(): Boolean = instance != null

        fun start(context: Context) {
            val intent = Intent(context, HudService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 关闭悬浮窗：清掉开关并停止服务，通知栏快捷开关与磁贴状态一致。 */
        fun shutdown(context: Context) {
            HudConfig(context).enabled = false
            context.stopService(Intent(context, HudService::class.java))
        }
    }

    private lateinit var settings: HudConfig
    private lateinit var windowManager: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var container: LinearLayout
    private lateinit var grabView: View
    private lateinit var powerView: TextView
    private lateinit var tempView: TextView
    private lateinit var voltView: TextView
    private lateinit var ampView: TextView

    private var dragging = false
    private var dragLeft = 0
    private var dragTop = 0

    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var lastNotifiedAt = 0L
    private var lastReading = HudReading(
        watts = Double.NaN,
        source = PowerSource.UNKNOWN,
        volts = Double.NaN,
        amps = 0.0,
        tempCelsius = Double.NaN,
        tempFromSystemApi = false,
        levelPercent = 0,
        status = 0,
        plugged = 0,
    )

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, settings.refreshMillis.toLong())
        }
    }

    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        // 小窗跟随面板移动：只改 params.x/y 不缩窗口，
        // 之前"拖动时扩成整屏"会在尺寸切换那一帧把面板画到左上角，看起来就是闪跳。
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.START
        // LAYOUT_IN_SCREEN 让坐标以整屏为基准，悬浮窗才能盖到状态栏区域。
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startLeft = 0
        private var startTop = 0
        private var dragged = false
        private var activePointerId = -1
        var touchFromGrab = false

        override fun onDown(e: MotionEvent): Boolean {
            activePointerId = e.getPointerId(e.actionIndex)
            downRawX = e.getRawX(e.actionIndex)
            downRawY = e.getRawY(e.actionIndex)
            // 从抓手区（面板下方的延伸条）起手时，触点比面板原点低一截，
            // 要把这段偏移并回面板坐标基准，否则拖动量会算多一段。
            if (touchFromGrab) downRawY -= grabView.top
            startLeft = dragLeft
            startTop = dragTop
            dragging = true
            dragged = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (settings.locked) return false
            // 只跟随起手那一根手指：第二个触点出现时 e2.rawX 会取到别的触点（幽灵触点/手掌），
            // 坐标瞬间跳变会让面板被 clamp 到屏幕边缘，看起来就是"从角上左右跳动"。
            val index = pointerIndexOf(e2) ?: return true
            val targetX = (startLeft + e2.getRawX(index) - downRawX).roundToInt()
            val targetY = (startTop + e2.getRawY(index) - downRawY).roundToInt()
            if (targetX != dragLeft || targetY != dragTop) {
                dragged = true
                movePanelTo(targetX, targetY)
            }
            return true
        }

        private fun pointerIndexOf(e: MotionEvent): Int? {
            for (i in 0 until e.pointerCount) {
                if (e.getPointerId(i) == activePointerId) return i
            }
            return null
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // 锁定只管拖动，不管单击：否则锁上之后只能回主界面才能解锁。
            if (!dragged) openSettings()
            return true
        }
    }

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = HudConfig(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        gestureDetector = GestureDetector(this, gestureListener)
        createViews()
        createNotificationChannel()
        Alerts.ensureChannel(this)
        // 服务是 startForegroundService 拉起的，不在时限内进前台系统会直接抛异常，
        // 所以先无条件 startForeground，再由 applyNotificationMode() 决定要不要把通知撤掉。
        startForeground(NOTIFICATION_ID, buildNotification())
        applyNotificationMode()
        BatteryReader.logNodeProbe()
        Log.i(TAG, "服务启动，悬浮窗权限=${canShowOverlay()} 锁定=${settings.locked}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                shutdown(this)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LOCK -> {
                settings.locked = !settings.locked
                Toast.makeText(
                    this,
                    if (settings.locked) R.string.overlay_locked_toast else R.string.overlay_unlocked_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (!canShowOverlay()) {
            Toast.makeText(this, R.string.overlay_permission_missing, Toast.LENGTH_LONG).show()
            shutdown(this)
            return START_NOT_STICKY
        }
        attachIfMissing()
        Prefs.of(this).registerOnSharedPreferenceChangeListener(this)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(this)
        detach()
        BatteryReader.reset()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_TEXT_COLOR, Prefs.KEY_TEXT_SIZE_SP, Prefs.KEY_BG_ALPHA_PCT -> applyTypography()
            Prefs.KEY_SHOW_POWER, Prefs.KEY_SHOW_TEMP, Prefs.KEY_SHOW_VOLT, Prefs.KEY_SHOW_AMP,
            Prefs.KEY_FIELD_ORDER -> applyFields()
            Prefs.KEY_POS_X, Prefs.KEY_POS_Y, Prefs.KEY_POS_SAVED -> applyPosition()
            Prefs.KEY_REFRESH_MS -> {
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, settings.refreshMillis.toLong())
            }
            Prefs.KEY_RESIDENT_NOTIFICATION -> applyNotificationMode()
            Prefs.KEY_ALERT_TEMP_C, Prefs.KEY_ALERT_SLOW_W, Prefs.KEY_ALERT_FULL,
            Prefs.KEY_ALERT_TRICKLE_W -> Alerts.reset()
            Prefs.KEY_ENABLED -> if (!settings.enabled) stopSelf()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTypography()
        applyPosition()
    }

    private fun canShowOverlay(): Boolean = android.provider.Settings.canDrawOverlays(this)

    @SuppressLint("ClickableViewAccessibility")
    private fun createViews() {
        val density = resources.displayMetrics.density
        val horizontalPadding = (10 * density).roundToInt()
        val verticalPadding = (5 * density).roundToInt()

        powerView = TextView(this).apply { includeFontPadding = false }
        tempView = TextView(this).apply { includeFontPadding = false }
        voltView = TextView(this).apply { includeFontPadding = false }
        ampView = TextView(this).apply { includeFontPadding = false }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            setLayerType(LinearLayout.LAYER_TYPE_SOFTWARE, null)
            addView(tempView)
            addView(powerView)
            addView(voltView)
            addView(ampView)
        }
        grabView = View(this)
        root = FrameLayout(this).apply {
            addView(
                container,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                grabView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0)
            )
        }
        // 手势挂在面板和抓手区上；面板贴进状态栏时，第三方窗口收不到那 96px 的触摸，
        // 抓手区补在面板正下方，从它上面起手就能把面板拖出状态栏。
        val touchHandler = View.OnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                gestureListener.touchFromGrab = view === grabView
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                endDrag()
            }
            gestureDetector.onTouchEvent(event)
        }
        container.setOnTouchListener(touchHandler)
        grabView.setOnTouchListener(touchHandler)
        applyTypography()
    }

    private fun attachIfMissing() {
        if (attached) {
            applyTypography()
            applyPosition()
            return
        }
        applyPosition()
        runCatching { windowManager.addView(root, params) }
            .onSuccess {
                attached = true
                root.post { applyPanelOffset() }
                Log.i(TAG, "悬浮窗已添加，位置=($dragLeft, $dragTop)")
            }
            .onFailure { error ->
                Log.e(TAG, "悬浮窗添加失败", error)
                Toast.makeText(this, R.string.overlay_add_failed, Toast.LENGTH_LONG).show()
            }
    }

    private fun detach() {
        if (attached) {
            runCatching { windowManager.removeView(root) }
            attached = false
        }
    }

    private fun applyTypography() {
        val sizeSp = settings.textSizeSp
        val color = settings.textColor
        listOf(powerView, tempView, voltView, ampView).forEach { view ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            view.setTextColor(color)
        }

        val density = resources.displayMetrics.density
        val background = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(withAlpha(Color.BLACK, settings.backgroundAlphaPercent / 100f))
        }
        container.background = background
        applyFields()
    }

    /** 显示哪几行由勾选决定，行的先后就是勾选的先后；一行都不勾时整个窗口收起，只留一块空底板没有意义。 */
    private fun applyFields() {
        val rows = mapOf(
            HudConfig.FIELD_TEMP to tempView,
            HudConfig.FIELD_POWER to powerView,
            HudConfig.FIELD_VOLT to voltView,
            HudConfig.FIELD_AMP to ampView,
        )
        val order = settings.fieldOrder
        container.removeAllViews()
        order.forEach { id ->
            val row = rows[id] ?: return@forEach
            row.visibility = View.VISIBLE
            container.addView(row)
        }
        root.visibility = if (order.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (order.isNotEmpty()) root.post { applyPanelOffset() }
    }

    private fun applyPosition() {
        if (dragging) return
        val screen = screenSize()
        if (!settings.positionSaved) {
            val width = if (container.isLaidOut) container.width else (130 * resources.displayMetrics.density).roundToInt()
            dragLeft = screen.x - width - (12 * resources.displayMetrics.density).roundToInt()
            dragTop = (28 * resources.displayMetrics.density).roundToInt()
            persistPosition()
        } else if (settings.posX != dragLeft || settings.posY != dragTop) {
            clampToScreen(settings.posX, settings.posY)
            persistPosition()
        }
        applyPanelOffset()
    }

    private fun clampToScreen(x: Int, y: Int) {
        dragLeft = clampX(x)
        dragTop = clampY(y)
    }

    private fun clampX(x: Int): Int {
        // 小窗的帧宽就是面板宽：按窗口宽度 clamp，否则窗口会整帧滑出屏幕右缘（面板看不见也点不到）。
        val width = if (root.width > 0) root.width else container.measuredWidth
        return x.coerceIn(0, (screenSize().x - width).coerceAtLeast(0))
    }

    private fun clampY(y: Int): Int {
        val height = if (root.height > 0) root.height else container.measuredHeight
        return y.coerceIn(0, (screenSize().y - height).coerceAtLeast(0))
    }

    private fun endDrag() {
        if (!dragging) return
        dragging = false
        persistPosition()
    }

    private fun movePanelTo(x: Int, y: Int) {
        dragLeft = clampX(x)
        dragTop = clampY(y)
        applyPanelOffset()
    }

    private fun applyPanelOffset() {
        params.x = dragLeft
        params.y = dragTop
        // 第三方悬浮窗整帧都落在状态栏 96px 内时收不到任何触摸（DOWN 被系统先吃掉），
        // 所以面板贴顶时把窗口向下伸出状态栏，抓手区就是这段可触摸的延伸。
        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
            if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).roundToInt()
        }
        val overlap = if (dragTop < statusBar) statusBar + 40 - dragTop else 0
        // 抓手区与面板同宽：MATCH_PARENT 会把 WRAP_CONTENT 的窗口撑到整屏宽，落点就全错了。
        val panelWidth = if (container.width > 0) container.width else container.measuredWidth
        val layout = grabView.layoutParams as FrameLayout.LayoutParams
        if (layout.height != overlap || layout.width != panelWidth) {
            layout.width = panelWidth
            layout.height = overlap
            grabView.layoutParams = layout
        }
        refreshWindowLayout()
    }

    private fun refreshWindowLayout() {
        if (attached) runCatching { windowManager.updateViewLayout(root, params) }
            .onFailure { Log.e(TAG, "窗口更新失败", it) }
    }

    /** 只有真正变化时才写回，避免偏好监听与拖动互相触发形成回环。 */
    private fun persistPosition() {
        if (!settings.positionSaved || settings.posX != dragLeft || settings.posY != dragTop) {
            settings.rememberPosition(dragLeft, dragTop)
        }
    }

    private fun screenSize(): Point {
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val display = windowManager.defaultDisplay
            Point().also { display.getRealSize(it) }
        }
        if (metrics.x <= 0 || metrics.y <= 0) {
            val dm = resources.displayMetrics
            return Point(dm.widthPixels, dm.heightPixels)
        }
        return metrics
    }

    private fun refresh() {
        val reading = BatteryReader.read(this, settings)
        lastReading = reading
        ChargeLog.onReading(this, reading)
        Alerts.evaluate(this, settings, reading)
        powerView.text = reading.formatPower()
        tempView.text = reading.formatTemp()
        voltView.text = if (reading.volts.isNaN()) "--" else String.format("%.2f V", reading.volts)
        ampView.text = String.format("%.2f A", reading.amps)
        // 文字宽度会随读数变化，抓手区要跟上面板同宽。
        if (!dragging) applyPanelOffset()
        val now = SystemClock.elapsedRealtime()
        if (settings.residentNotification && now - lastNotifiedAt >= 2_000) {
            lastNotifiedAt = now
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val reading = lastReading
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hideIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HudService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lockIntent = PendingIntent.getService(
            this, 2,
            Intent(this, HudService::class.java).setAction(ACTION_TOGGLE_LOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val status = when {
            reading.charging -> getString(R.string.status_charging, reading.levelPercent)
            else -> getString(R.string.status_unplugged, reading.levelPercent)
        }
        val electric = if (reading.source == PowerSource.UNKNOWN) {
            getString(R.string.power_source_unknown)
        } else {
            getString(R.string.power_source_detail, reading.formatPower(), sourceLabel(reading.source))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hud)
            .setContentTitle(getString(R.string.notification_title, reading.formatPower(), reading.formatTemp()))
            .setContentText("$status · $electric")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_hide), hideIntent)
            .addAction(0, getString(if (settings.locked) R.string.action_unlock else R.string.action_lock), lockIntent)
            .build()
    }

    private fun sourceLabel(source: PowerSource): String = getString(
        when (source) {
            PowerSource.FRAMEWORK -> R.string.power_source_framework
            PowerSource.SYSTEM_FILE -> R.string.power_source_sysfs
            PowerSource.CHARGE_COUNTER -> R.string.power_source_counter
            PowerSource.ESTIMATED -> R.string.power_source_estimated
            PowerSource.UNKNOWN -> R.string.power_source_none
        }
    )

    /**
     * 常驻开关：开着就保持前台服务并刷新通知，关掉则退出前台态并撤销通知。
     * 退出前台后悬浮窗还在，但系统随时可能回收后台进程，读数会停。
     */
    private fun applyNotificationMode() {
        val manager = getSystemService(NotificationManager::class.java)
        if (settings.residentNotification) {
            startForeground(NOTIFICATION_ID, buildNotification())
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            manager?.cancel(NOTIFICATION_ID)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_description) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
}
