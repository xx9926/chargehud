package com.chargehud.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/** 取色弹窗：左边饱和度/明度方块、右边色相竖条，拖动即时改悬浮窗文字颜色，取消则恢复原色。 */
object ColorPickerDialog {

    fun show(activity: AppCompatActivity) {
        val config = HudConfig(activity)
        val original = config.textColor
        val hsv = FloatArray(3)
        Color.colorToHSV(original, hsv)

        val view = activity.layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val svPanel = view.findViewById<SvPanel>(R.id.svPanel)
        val hueBar = view.findViewById<HueBar>(R.id.hueBar)
        val oldSwatch = view.findViewById<View>(R.id.oldSwatch)
        val newSwatch = view.findViewById<View>(R.id.newSwatch)
        val hexInput = view.findViewById<EditText>(R.id.hexInput)
        val presetRow = view.findViewById<LinearLayout>(R.id.presetRow)

        val density = activity.resources.displayMetrics.density
        fun paintSwatch(swatch: View, color: Int) {
            swatch.background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(color)
            }
        }
        paintSwatch(oldSwatch, original)

        var editingHex = false
        fun apply(color: Int, writeHex: Boolean) {
            config.textColor = color
            Color.colorToHSV(color, hsv)
            svPanel.setHsv(hsv[0], hsv[1], hsv[2])
            hueBar.hue = hsv[0]
            paintSwatch(newSwatch, color)
            if (writeHex) {
                editingHex = true
                hexInput.setText(String.format("%06X", color and 0xFFFFFF))
                editingHex = false
            }
        }
        apply(original, true)

        svPanel.onChanged = { h, s, v -> apply(Color.HSVToColor(floatArrayOf(h, s, v)), true) }
        hueBar.onChanged = { h ->
            hsv[0] = h
            svPanel.setHue(h)
            apply(Color.HSVToColor(floatArrayOf(h, hsv[1], hsv[2])), true)
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (editingHex) return
                val text = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (text.length == 6) {
                    text.toLongOrNull(16)?.let { apply((0xFF000000L or it).toInt(), false) }
                }
            }
        })

        listOf(Color.WHITE, Color.BLACK).forEach { color ->
            presetRow.addView(
                View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (34 * density).roundToInt(), (34 * density).roundToInt()
                    ).apply { marginEnd = (12 * density).roundToInt() }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke((1.5f * density).roundToInt(), Color.argb(120, 0, 0, 0))
                    }
                    setOnClickListener { apply(color, true) }
                }
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setOnCancelListener { config.textColor = original }
            .create()
        view.findViewById<View>(R.id.btnPresets).setOnClickListener {
            presetRow.visibility = if (presetRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.btnDone).setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_picker_dialog)
    }
}

/** 饱和度（横）× 明度（纵）取色方块。 */
class SvPanel(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onChanged: ((Float, Float, Float) -> Unit)? = null
    private var hue = 0f
    private var saturation = 0f
    private var value = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * context.resources.displayMetrics.density
        color = Color.WHITE
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setHsv(h: Float, s: Float, v: Float) {
        hue = h
        saturation = s
        value = v
        invalidate()
    }

    fun setHue(h: Float) {
        hue = h
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val corner = 22f * resources.displayMetrics.density
        fill.shader = ComposeShader(
            LinearGradient(0f, 0f, w, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)), Shader.TileMode.CLAMP),
            LinearGradient(0f, 0f, 0f, h, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP),
            PorterDuff.Mode.MULTIPLY
        )
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, fill)
        fill.shader = null
        val radius = 8f * resources.displayMetrics.density
        val cx = saturation * w
        val cy = (1f - value) * h
        dot.color = Color.HSVToColor(floatArrayOf(hue, saturation, value))
        // 选点贴着圆角时会被切掉一块，画在裁剪后的圆角区域内就不会露出方角。
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, corner, corner, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawCircle(cx, cy, radius, dot)
        canvas.drawCircle(cx, cy, radius, ring)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.onTouchEvent(event)
        }
        saturation = (event.x / width).coerceIn(0f, 1f)
        value = 1f - (event.y / height).coerceIn(0f, 1f)
        invalidate()
        onChanged?.invoke(hue, saturation, value)
        return true
    }
}

/** 竖向色相条。 */
class HueBar(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onChanged: ((Float) -> Unit)? = null
    var hue = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * context.resources.displayMetrics.density
        color = Color.argb(200, 60, 60, 60)
    }
    private val clip = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
                0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
            ),
            null,
            Shader.TileMode.CLAMP
        )
        // 色相条窄，圆角直接吃满半个宽度，两端就是胶囊形。
        val corner = (w / 2f).coerceAtMost(15f * resources.displayMetrics.density)
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, fill)
        fill.shader = null
        val y = hue / 360f * h
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, corner, corner, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(0f, y - 5f, w, y + 5f, thumb)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.onTouchEvent(event)
        }
        hue = (event.y / height).coerceIn(0f, 1f) * 360f
        invalidate()
        onChanged?.invoke(hue)
        return true
    }
}
