package com.chargehud.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var residentNotificationHint: TextView
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
        residentNotificationHint = findViewById(R.id.residentNotificationHint)
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
            if (!updatingUi) {
                config.residentNotification = checked
                syncResidentHint()
            }
        }
        switchLocked.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) config.locked = checked
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
        checkShowPower.isChecked = config.showPower
        checkShowTemp.isChecked = config.showTemp
        checkShowVolt.isChecked = config.showVoltage
        checkShowAmp.isChecked = config.showCurrent
        syncResidentHint()

        seekTextSize.progress = (config.textSizeSp - Prefs.MIN_TEXT_SP).roundToInt()
        textSizeValue.text = "${config.textSizeSp.roundToInt()} sp"
        seekBackgroundAlpha.progress = config.backgroundAlphaPercent
        backgroundAlphaValue.text = "${config.backgroundAlphaPercent}%"
        seekCapacity.progress = (config.capacityMah - 1000) / 100
        syncCapacityLabel()
        seekRefresh.progress = config.refreshMillis / 100 - 3
        refreshValue.text = "${config.refreshMillis / 1000f} 秒"
    }

    /** 只有关掉常驻时才说明代价，平时不占地方。 */
    private fun syncResidentHint() {
        residentNotificationHint.visibility =
            if (config.residentNotification) View.GONE else View.VISIBLE
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
    }
}
