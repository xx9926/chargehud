package com.chargehud.app

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

/** 充电档案页：上面是选中会话的功率/电量曲线，下面是历史列表。 */
class ArchiveActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var chart: ChargeChartView
    private lateinit var listContainer: LinearLayout
    private lateinit var clearButton: TextView

    private var sessions: List<ChargeSession> = emptyList()
    private var selectedIndex = 0

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragStartedAt = 0L
    private var closingByDrag = false
    // Activity 构造时还没有 Context，这几个尺寸只能在 onCreate 里取。
    private var flickDistance = 0f
    private var touchSlop = 0

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            // 只有正在进行的那条需要动，历史部分不会因为等待而改变。
            if (sessions.firstOrNull()?.live == true) reload()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive)
        root = findViewById(R.id.root)
        flickDistance = 80f * resources.displayMetrics.density
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        statusText = findViewById(R.id.archiveStatus)
        summaryText = findViewById(R.id.sessionSummary)
        chart = findViewById(R.id.chargeChart)
        listContainer = findViewById(R.id.sessionList)
        clearButton = findViewById(R.id.btnClearArchive)
        clearButton.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        reload()
        handler.postDelayed(refresher, REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    /** 页面上任意位置起手往右拖都算"返回上一页"，整页跟手右移，露出下面的设置页。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!dragging) {
                val dx = event.x - downX
                if (dx > touchSlop * 2 && dx > abs(event.y - downY) * 1.6f) {
                    dragging = true
                    dragStartedAt = SystemClock.uptimeMillis()
                    cancelChildTouch(event)
                }
            }
        }
        if (!dragging) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> root.translationX = (event.x - downX).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseDrag(event)
        }
        return true
    }

    /** 判定交给系统之前先给子 View 补一个 CANCEL，否则列表会以为这次触摸被它拿走而保持按下态。 */
    private fun cancelChildTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun releaseDrag(event: MotionEvent) {
        dragging = false
        val moved = event.x - downX
        val flicked = SystemClock.uptimeMillis() - dragStartedAt < 220 && moved > flickDistance
        val pageWidth = root.width.toFloat()
        val close = moved > pageWidth * 0.35f || flicked
        if (!close) {
            root.animate().translationX(0f).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()
            return
        }
        closingByDrag = true
        root.animate().translationX(pageWidth).setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { finish() }
            .start()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        // 拖到底再松手时页面已经移出去了，这里再叠一层滑出动画会重一次，所以只给非拖拽路径加动画。
        if (closingByDrag) {
            overridePendingTransition(0, 0)
        } else {
            overridePendingTransition(R.anim.slide_in_from_left, R.anim.slide_out_to_right)
        }
    }

    private fun reload() {
        // 进行中的那条由充电记录服务持有，没有会话时 live() 自己返回 null。
        sessions = listOfNotNull(ChargeLog.live()) + ChargeLog.sessions(this)
        statusText.text = when {
            sessions.isEmpty() -> "还没有记录：插上充电器就会开始记，不用开悬浮窗（可在「更多 → 充电时后台记录」关掉）。"
            else -> "共 ${sessions.size} 条，只保存在本机，卸载即清空。"
        }
        selectedIndex = selectedIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0))
        rebuildList()
        showSelected()
    }

    private fun rebuildList() {
        listContainer.removeAllViews()
        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics
        ).toInt()
        val rowBackground = selectableItemBackground()
        sessions.forEachIndexed { index, session ->
            val row = TextView(this).apply {
                text = session.summary()
                textSize = 15f
                setPadding(0, padding, 0, padding)
                setBackgroundResource(rowBackground)
                isClickable = true
                isFocusable = true
                if (index == selectedIndex) typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    selectedIndex = index
                    rebuildList()
                    showSelected()
                }
            }
            listContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun showSelected() {
        val session = sessions.getOrNull(selectedIndex)
        chart.setSession(session)
        summaryText.text = session?.let {
            val interval = if (it.samples.size >= 2) {
                it.samples[1].offsetSec - it.samples[0].offsetSec
            } else {
                0
            }
            buildString {
                append("电量 ${it.startLevel}% → ${it.endLevel}%")
                append(" · 标称 ${it.capacityMah} mAh")
                if (interval > 0) {
                    append(" · ")
                    append(it.samples.size)
                    append(" 个采样点，每 ")
                    append(interval)
                    append(" 秒")
                }
            }
        } ?: ""
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空档案")
            .setMessage("删除全部充电记录，正在进行的这次也会丢掉。")
            .setPositiveButton("清空") { _, _ ->
                ChargeLog.clear(this)
                selectedIndex = 0
                reload()
            }
            .setNegativeButton("留着", null)
            .show()
    }

    companion object {
        private const val REFRESH_MS = 10_000L
    }
}
