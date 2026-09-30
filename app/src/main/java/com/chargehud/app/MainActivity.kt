package com.chargehud.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var config: HudConfig

    private lateinit var statusText: TextView
    private lateinit var switchEnabled: SwitchCompat
    private lateinit var switchSysFs: SwitchCompat
    private lateinit var switchAutoCapacity: SwitchCompat
    private lateinit var switchDualCell: SwitchCompat
    private lateinit var switchHalfVoltage: SwitchCompat
    private lateinit var switchResidentNotification: SwitchCompat
    private lateinit var switchLocked: SwitchCompat
    private lateinit var checkShowPower: CheckBox
    private lateinit var checkShowTemp: CheckBox
    private lateinit var checkShowVolt: CheckBox
    private lateinit var checkShowAmp: CheckBox
    private lateinit var archiveHeader: TextView
    private lateinit var alertTempValue: TextView
    private lateinit var alertSlowValue: TextView
    private lateinit var alertTrickleValue: TextView
    private lateinit var switchAlertFull: SwitchCompat
    private lateinit var btnPalette: Button
    private lateinit var appearanceHeader: TextView
    private lateinit var appearanceSection: LinearLayout
    private lateinit var dataHeader: TextView
    private lateinit var dataSection: LinearLayout
    private lateinit var posHeader: TextView
    private lateinit var posSection: LinearLayout
    private lateinit var seekTextSize: SeekBar
    private lateinit var textSizeValue: TextView
    private lateinit var seekBackgroundAlpha: SeekBar
    private lateinit var backgroundAlphaValue: TextView
    private lateinit var seekCapacity: SeekBar
    private lateinit var capacityValue: TextView
    private lateinit var seekRefresh: SeekBar
    private lateinit var refreshValue: TextView
    private lateinit var liveReading: TextView

    private var updatingUi = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = HudConfig(this)
        bindViews()
        configureSeekBarRanges()
        wireControls()
    }

    override fun onResume() {
        super.onResume()
        updatingUi = true
        syncFromConfig()
        updatingUi = false
        refreshStatus()
        handler.post(livePoller)
    }

    override fun onPause() {
        stopMoveRepeat()
        handler.removeCallbacks(livePoller)
        super.onPause()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        switchEnabled = findViewById(R.id.switchEnabled)
        switchSysFs = findViewById(R.id.switchSysFs)
        switchAutoCapacity = findViewById(R.id.switchAutoCapacity)
        switchDualCell = findViewById(R.id.switchDualCell)
        switchHalfVoltage = findViewById(R.id.switchHalfVoltage)
        switchResidentNotification = findViewById(R.id.switchResidentNotification)
        switchLocked = findViewById(R.id.switchLocked)
        checkShowPower = findViewById(R.id.checkShowPower)
        checkShowTemp = findViewById(R.id.checkShowTemp)
        checkShowVolt = findViewById(R.id.checkShowVolt)
        checkShowAmp = findViewById(R.id.checkShowAmp)
        archiveHeader = findViewById(R.id.archiveHeader)
        alertTempValue = findViewById(R.id.alertTempValue)
        alertSlowValue = findViewById(R.id.alertSlowValue)
        alertTrickleValue = findViewById(R.id.alertTrickleValue)
        switchAlertFull = findViewById(R.id.switchAlertFull)
        btnPalette = findViewById(R.id.btnPalette)
        appearanceHeader = findViewById(R.id.appearanceHeader)
        appearanceSection = findViewById(R.id.appearanceSection)
        dataHeader = findViewById(R.id.dataHeader)
        dataSection = findViewById(R.id.dataSection)
        posHeader = findViewById(R.id.posHeader)
        posSection = findViewById(R.id.posSection)
        seekTextSize = findViewById(R.id.seekTextSize)
        textSizeValue = findViewById(R.id.textSizeValue)
        seekBackgroundAlpha = findViewById(R.id.seekBackgroundAlpha)
        backgroundAlphaValue = findViewById(R.id.backgroundAlphaValue)
        seekCapacity = findViewById(R.id.seekCapacity)
        capacityValue = findViewById(R.id.capacityValue)
        seekRefresh = findViewById(R.id.seekRefresh)
        refreshValue = findViewById(R.id.refreshValue)
        liveReading = findViewById(R.id.liveReading)
    }

    private fun configureSeekBarRanges() {
        seekTextSize.max = (Prefs.MAX_TEXT_SP - Prefs.MIN_TEXT_SP).roundToInt()
        seekBackgroundAlpha.max = 100
        seekCapacity.max = 90
        seekRefresh.max = 47
    }

    private fun wireControls() {
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) turnOn() else HudService.shutdown(this)
            // 服务启动是异步的，这里不能立刻用 isAlive() 回写开关，否则刚拨上的开关会被弹回去。
            handler.post { refreshStatus() }
        }
        switchSysFs.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                config.useSysFs = checked
                BatteryReader.reset()
            }
        }
        switchAutoCapacity.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                config.autoCapacity = checked
                BatteryReader.reset()
                syncCapacityLabel()
            }
        }
        switchDualCell.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                config.dualCell = checked
                BatteryReader.reset()
            }
        }
        switchHalfVoltage.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                config.halfVoltage = checked
                BatteryReader.reset()
            }
        }
        switchResidentNotification.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) config.residentNotification = checked
        }
        switchLocked.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) config.locked = checked
        }
        switchAlertFull.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) config.alertFullEnabled = checked
        }
        // 勾选的先后顺序就是悬浮窗里各行的排列顺序
        checkShowPower.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) config.setFieldShown(HudConfig.FIELD_POWER, checked)
        }
        checkShowTemp.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) config.setFieldShown(HudConfig.FIELD_TEMP, checked)
        }
        checkShowVolt.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) config.setFieldShown(HudConfig.FIELD_VOLT, checked)
        }
        checkShowAmp.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) config.setFieldShown(HudConfig.FIELD_AMP, checked)
        }

        seekTextSize.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onValueChanged(bar: SeekBar, progress: Int) {
                config.textSizeSp = Prefs.MIN_TEXT_SP + progress.toFloat()
                textSizeValue.text = "${config.textSizeSp.roundToInt()} sp"
            }
        })
        seekBackgroundAlpha.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onValueChanged(bar: SeekBar, progress: Int) {
                config.backgroundAlphaPercent = progress
                backgroundAlphaValue.text = "${config.backgroundAlphaPercent}%"
            }
        })
        seekCapacity.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onValueChanged(bar: SeekBar, progress: Int) {
                config.capacityMah = 1000 + progress * 100
                syncCapacityLabel()
            }
        })
        seekRefresh.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onValueChanged(bar: SeekBar, progress: Int) {
                config.refreshMillis = 300 + progress * 100
                refreshValue.text = "${config.refreshMillis / 1000f} 秒"
            }
        })

        btnPalette.setOnClickListener { ColorPickerDialog.show(this) }

        archiveHeader.text = getString(R.string.section_archive) + "　▸"
        archiveHeader.setOnClickListener { openArchive() }
        alertTempValue.setOnClickListener { pickTempThreshold() }
        alertSlowValue.setOnClickListener { pickSlowThreshold() }
        alertTrickleValue.setOnClickListener { pickTrickleThreshold() }

        appearanceHeader.setOnClickListener {
            val expanded = appearanceSection.visibility != View.VISIBLE
            appearanceSection.visibility = if (expanded) View.VISIBLE else View.GONE
            syncAppearanceHeader()
        }
        syncAppearanceHeader()

        dataHeader.setOnClickListener {
            val expanded = dataSection.visibility != View.VISIBLE
            dataSection.visibility = if (expanded) View.VISIBLE else View.GONE
            syncDataHeader()
        }
        syncDataHeader()

        posHeader.setOnClickListener {
            val expanded = posSection.visibility != View.VISIBLE
            posSection.visibility = if (expanded) View.VISIBLE else View.GONE
            syncPosHeader()
        }
        syncPosHeader()
        wirePositionButtons()
    }

    private fun syncAppearanceHeader() {
        val expanded = appearanceSection.visibility == View.VISIBLE
        appearanceHeader.text =
            getString(R.string.section_appearance) + if (expanded) "　▾" else "　▸"
    }

    private fun syncDataHeader() {
        val expanded = dataSection.visibility == View.VISIBLE
        dataHeader.text = getString(R.string.section_data) + if (expanded) "　▾" else "　▸"
    }

    private fun syncPosHeader() {
        val expanded = posSection.visibility == View.VISIBLE
        posHeader.text = getString(R.string.section_position) + if (expanded) "　▾" else "　▸"
    }

    /** 进场用横向推移（新页从右边推进来、本页被带着向左退一点），和档案页的侧滑返回配成一套。 */
    @Suppress("DEPRECATION")
    private fun openArchive() {
        startActivity(Intent(this, ArchiveActivity::class.java))
        overridePendingTransition(R.anim.slide_in_from_right, R.anim.slide_out_to_left)
    }

    private fun syncAlertLabels() {
        alertTempValue.text = tempLabel(config.alertTempCelsius)
        alertSlowValue.text = slowLabel(config.alertSlowWatts)
        alertTrickleValue.text = slowLabel(config.alertTrickleWatts)
    }

    private fun pickTempThreshold() {
        showOptionPicker(TEMP_OPTIONS, ::tempLabel) {
            config.alertTempCelsius = it
            syncAlertLabels()
        }
    }

    /** 慢充的判定窗口固定为插电 5 分钟后、近一分钟均值，只调阈值。 */
    private fun pickSlowThreshold() {
        showOptionPicker(SLOW_OPTIONS, ::slowLabel) {
            config.alertSlowWatts = it
            syncAlertLabels()
        }
    }

    /** 阈值选择弹窗：只留一列居中的选项，宽度给屏幕一半，不再带标题（行名已经说明是哪个提醒）。 */
    /** 涓流提醒的电量门槛固定在 80%，只调功率阈值。 */
    private fun pickTrickleThreshold() {
        showOptionPicker(TRICKLE_OPTIONS, ::slowLabel) {
            config.alertTrickleWatts = it
            syncAlertLabels()
        }
    }

    private fun showOptionPicker(options: List<Int>, label: (Int) -> String, onPick: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        val verticalPadding = (14 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, verticalPadding, 0, verticalPadding)
        }
        val dialog = AlertDialog.Builder(this).setView(container).create()
        val rowBackground = selectableItemBackground()
        options.forEach { value ->
            container.addView(
                TextView(this).apply {
                    text = label(value)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    minHeight = (44 * density).toInt()
                    setPadding(0, verticalPadding, 0, verticalPadding)
                    setBackgroundResource(rowBackground)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        onPick(value)
                        dialog.dismiss()
                    }
                }
            )
        }
        dialog.show()
        // 底色要跟主界面一致，只能在 Activity 主题上解析这个属性：Dialog 主题会把它解析成纯白。
        val surface = GradientDrawable().apply {
            cornerRadius = 28f * resources.displayMetrics.density
            setColor(pageSurfaceColor())
        }
        dialog.window?.setBackgroundDrawable(surface)
        // 默认的半透明遮罩会把后面的主界面压暗，同色的弹窗就会显得"发白"，这里直接去掉遮罩。
        dialog.window?.setDimAmount(0f)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels / 2f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun pageSurfaceColor(): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(android.R.attr.colorBackground, value, true)) return Color.WHITE
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun tempLabel(value: Int): String = if (value <= 0) "关闭" else "$value ℃"

    private fun slowLabel(value: Int): String = if (value <= 0) "关闭" else "< $value W"

    /** 方向按钮只写偏好里的目标位置，悬浮窗服务监听偏好后自行移动和夹边；按住按钮连续移动。 */
    private fun wirePositionButtons() {
        val directions = mapOf(
            R.id.btnUp to Pair(0, -1),
            R.id.btnDown to Pair(0, 1),
            R.id.btnLeft to Pair(-1, 0),
            R.id.btnRight to Pair(1, 0)
        )
        directions.forEach { (id, dir) ->
            findViewById<View>(id).setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        moveStep(dir.first, dir.second)
                        startMoveRepeat(dir.first, dir.second)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopMoveRepeat()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun moveStep(dx: Int, dy: Int) {
        config.rememberPosition(config.posX + dx * STEP_PX, config.posY + dy * STEP_PX)
    }

    private var moveRepeat: Runnable? = null

    private fun startMoveRepeat(dx: Int, dy: Int) {
        stopMoveRepeat()
        moveRepeat = object : Runnable {
            override fun run() {
                moveStep(dx, dy)
                handler.postDelayed(this, 30)
            }
        }.also { handler.postDelayed(it, 250) }
    }

    private fun stopMoveRepeat() {
        moveRepeat?.let { handler.removeCallbacks(it) }
        moveRepeat = null
    }

    private fun syncFromConfig() {
        switchEnabled.isChecked = config.enabled && HudService.isAlive()
        switchSysFs.isChecked = config.useSysFs
        switchAutoCapacity.isChecked = config.autoCapacity
        switchDualCell.isChecked = config.dualCell
        switchHalfVoltage.isChecked = config.halfVoltage
        switchResidentNotification.isChecked = config.residentNotification
        switchLocked.isChecked = config.locked
        switchAlertFull.isChecked = config.alertFullEnabled
        checkShowPower.isChecked = config.showPower
        checkShowTemp.isChecked = config.showTemp
        checkShowVolt.isChecked = config.showVoltage
        checkShowAmp.isChecked = config.showCurrent
        syncAlertLabels()

        seekTextSize.progress = (config.textSizeSp - Prefs.MIN_TEXT_SP).roundToInt()
        textSizeValue.text = "${config.textSizeSp.roundToInt()} sp"
        seekBackgroundAlpha.progress = config.backgroundAlphaPercent
        backgroundAlphaValue.text = "${config.backgroundAlphaPercent}%"
        seekCapacity.progress = (config.capacityMah - 1000) / 100
        syncCapacityLabel()
        seekRefresh.progress = config.refreshMillis / 100 - 3
        refreshValue.text = "${config.refreshMillis / 1000f} 秒"
    }

    /** 显示估算真正会用到的容量：自动读到就用设计容量。 */
    private fun syncCapacityLabel() {
        val design = if (config.autoCapacity) BatteryReader.designCapacity(this) else -1
        capacityValue.text = if (design > 0) "$design mAh 自动" else "${config.capacityMah} mAh 手填"
    }

    private fun refreshStatus() {
        statusText.text = buildString {
            append("服务状态：")
            append(if (HudService.isAlive()) getString(R.string.service_running) else getString(R.string.service_stopped))
        }
    }

    private fun turnOn() {
        config.enabled = true
        HudService.start(this)
    }

    private val livePoller = object : Runnable {
        override fun run() {
            val reading = BatteryReader.read(this@MainActivity, config)
            liveReading.text = if (reading.source == PowerSource.UNKNOWN && reading.watts.isNaN()) {
                getString(R.string.power_source_unknown)
            } else {
                val volts = if (reading.volts.isNaN()) "--" else String.format("%.2f", reading.volts)
                val amps = String.format("%.2f", reading.amps)
                getString(
                    R.string.live_reading_format,
                    reading.formatPower(),
                    reading.formatTemp(),
                    volts,
                    amps
                )
            }
            handler.postDelayed(this, 1_000)
            refreshStatus()
        }
    }

    private abstract inner class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        abstract fun onValueChanged(bar: SeekBar, progress: Int)

        final override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!updatingUi) onValueChanged(bar, progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    companion object {
        private const val STEP_PX = 5

        /** 0 表示关闭提醒；42 ℃ 是本机大功率快充时的正常上沿，默认停在这一档。 */
        private val TEMP_OPTIONS = listOf(0, 36, 38, 40, 42, 46, 48, 50)
        private val SLOW_OPTIONS = listOf(0, 2, 4, 6, 8, 10)
        private val TRICKLE_OPTIONS = listOf(0, 2, 3, 5)
    }
}
