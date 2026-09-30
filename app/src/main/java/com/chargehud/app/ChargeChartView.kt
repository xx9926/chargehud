package com.chargehud.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/**
 * 充电曲线：X 轴是插电后的分钟数，左轴功率 W、右轴电量 %，两条折线共用一张图。
 * 控件只画数据，本身不读电池。
 */
class ChargeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 128, 128, 128)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 128, 128, 128)
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 128, 128, 128)
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7A45")
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
    }
    private val powerLegendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7A45")
        textSize = 12f * resources.displayMetrics.scaledDensity
    }
    private val levelLegendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        textSize = 12f * resources.displayMetrics.scaledDensity
    }
    private val powerPath = Path()
    private val levelPath = Path()

    private var session: ChargeSession? = null

    fun setSession(target: ChargeSession?) {
        session = target
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val leftEdge = 14f * density + labelPaint.measureText("120")
        val rightEdge = width - 14f * density - labelPaint.measureText("100%")
        val topEdge = 26f * density
        val bottomEdge = height - 22f * density
        val plotWidth = rightEdge - leftEdge
        val plotHeight = bottomEdge - topEdge
        if (plotWidth <= 0f || plotHeight <= 0f) return

        val data = session
        if (data == null || data.samples.size < 2) {
            canvas.drawText(
                if (data == null) "还没有充电记录" else "这次充电没有采到足够数据",
                leftEdge,
                topEdge + plotHeight / 2,
                labelPaint
            )
            return
        }

        val minutes = max(1.0, data.durationMillis / 60_000.0)
        val wattsTop = niceTop(max(1.0, data.samples.maxOf { it.watts }))
        val levels = data.samples.map { it.levelPercent.toDouble() }
        val levelBottom = (levels.min() - 1).coerceIn(0.0, 99.0)
        val levelTop = (levels.max() + 1).coerceIn(levelBottom + 1.0, 100.0)

        canvas.drawText("功率 W", leftEdge, topEdge - 10f * density, powerLegendPaint)
        canvas.drawText(
            "电量 %",
            rightEdge - levelLegendPaint.measureText("电量 %"),
            topEdge - 10f * density,
            levelLegendPaint
        )

        val rows = 4
        for (row in 0..rows) {
            val fraction = row.toFloat() / rows
            val y = bottomEdge - fraction * plotHeight
            if (row > 0) canvas.drawLine(leftEdge, y, rightEdge, y, gridPaint)
            val watts = wattsTop * fraction
            val wattsLabel =
                if (watts % 1.0 < 0.05) watts.toInt().toString() else String.format("%.1f", watts)
            canvas.drawText(
                wattsLabel,
                leftEdge - labelPaint.measureText(wattsLabel) - 6f * density,
                y + labelPaint.textSize / 3,
                labelPaint
            )
            val level = levelBottom + (levelTop - levelBottom) * fraction
            val levelLabel = "${level.toInt()}%"
            canvas.drawText(levelLabel, rightEdge + 6f * density, y + labelPaint.textSize / 3, labelPaint)
        }

        val columns = 4
        for (column in 1 until columns) {
            val x = leftEdge + column.toFloat() / columns * plotWidth
            canvas.drawLine(x, topEdge, x, bottomEdge, gridPaint)
        }
        canvas.drawLine(leftEdge, bottomEdge, rightEdge, bottomEdge, axisPaint)

        powerPath.reset()
        levelPath.reset()
        data.samples.forEachIndexed { index, sample ->
            val x = leftEdge + (sample.offsetSec / (minutes * 60.0)).coerceIn(0.0, 1.0).toFloat() * plotWidth
            val powerY = bottomEdge - (sample.watts / wattsTop).toFloat() * plotHeight
            val levelY = bottomEdge -
                ((sample.levelPercent - levelBottom) / (levelTop - levelBottom)).toFloat() * plotHeight
            if (index == 0) {
                powerPath.moveTo(x, powerY)
                levelPath.moveTo(x, levelY)
            } else {
                powerPath.lineTo(x, powerY)
                levelPath.lineTo(x, levelY)
            }
        }
        canvas.drawPath(powerPath, powerPaint)
        canvas.drawPath(levelPath, levelPaint)

        canvas.drawText("0 分", leftEdge, bottomEdge + labelPaint.textSize + 6f * density, labelPaint)
        val endLabel = "${minutes.toInt()} 分"
        canvas.drawText(
            endLabel,
            rightEdge - labelPaint.measureText(endLabel),
            bottomEdge + labelPaint.textSize + 6f * density,
            labelPaint
        )
    }

    /** 纵轴上限取到能被 4 行整除的刻度，否则中间几行会标出 1.3 W、3.8 W 这种没意义的数字。 */
    private fun niceTop(raw: Double): Double {
        val candidates = doubleArrayOf(4.0, 8.0, 12.0, 20.0, 40.0, 60.0, 80.0, 120.0, 160.0, 240.0)
        return candidates.firstOrNull { it >= raw * 1.1 } ?: ceil(raw / 240.0) * 240.0
    }
}
