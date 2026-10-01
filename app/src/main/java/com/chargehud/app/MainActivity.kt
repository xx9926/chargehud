package com.chargehud.app

import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.roundToInt

open class MainActivity : AppCompatActivity() {

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
    private lateinit var switchHideRecents: SwitchCompat
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
    private lateinit var capacityAutoLabel: TextView
    private lateinit var capacityManualRow: View
    private lateinit var capacityInput: EditText
    private lateinit var switchRecordCharging: SwitchCompat
    private lateinit var seekRefresh: SeekBar
    private lateinit var refreshValue: TextView
    private lateinit var liveReading: TextView
    private lateinit var repoLink: TextView

    private var updatingUi = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = HudConfig(this)
        // 重装后组件启用状态会退回 manifest 默认值，这里跟偏好对齐一次。
        RecentsEntry.applyEntry(this, config.hideFromRecents)
        ChargeRecorderService.ensure(this)
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
        // 点页面里的文字不会让输入框失焦，返回键也只收起键盘；离页时补一次落盘。
        // 只在手动那一栏真的显示着时补，否则切回自动后输入框里的旧文本会被当成新值写回去。
        if (!config.autoCapacity && capacityManualRow.visibility == View.VISIBLE) commitCapacityInput()
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
        switchHideRecents = findViewById(R.id.switchHideRecents)
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
        capacityAutoLabel = findViewById(R.id.capacityAutoLabel)
        capacityManualRow = findViewById(R.id.capacityManualRow)
        capacityInput = findViewById(R.id.capacityInput)
        switchRecordCharging = findViewById(R.id.switchRecordCharging)
        seekRefresh = findViewById(R.id.seekRefresh)
        refreshValue = findViewById(R.id.refreshValue)
        liveReading = findViewById(R.id.liveReading)
        repoLink = findViewById(R.id.repoLink)
    }

    private fun configureSeekBarRanges() {
        seekTextSize.max = (Prefs.MAX_TEXT_SP - Prefs.MIN_TEXT_SP).roundToInt()
        seekBackgroundAlpha.max = 100
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
                syncCapacityUi()
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
            if (!updatingUi) {
                config.alertFullEnabled = checked
                onAlertConfigChanged()
            }
        }
        // 用 click 而不是 checkedChange：Activity 重建时系统会恢复开关状态，
        // 那一下会走成监听器，把用户刚打开的隐藏状态又写回 false。
        switchHideRecents.setOnClickListener {
            val hidden = switchHideRecents.isChecked
            config.hideFromRecents = hidden
            // 当前任务是用旧入口建的，标记改不了；换入口从下次进设置页开始生效。
            RecentsEntry.applyEntry(this, hidden)
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
        // 手填容量只在失焦或按完成时落盘：边打字边写会把中间值当成容量，也会把输入框里的数字改掉。
        capacityInput.setOnFocusChangeListener { _, focused ->
            if (!focused) commitCapacityInput()
        }
        capacityInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
                commitCapacityInput()
                true
            } else {
                false
            }
        }
        switchRecordCharging.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            config.recordWhileCharging = checked
            // 记录关掉时提醒可能还要用这个服务，所以按"还需不需要后台服务"来决定去留。
            if (config.wantsChargeWatcher) {
                ChargeRecorderService.ensure(this)
            } else {
                ChargeRecorderService.stop(this)
            }
        }
        seekRefresh.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onValueChanged(bar: SeekBar, progress: Int) {
                config.refreshMillis = 300 + progress * 100
                refreshValue.text = "${config.refreshMillis / 1000f} 秒"
            }
        })

        btnPalette.setOnClickListener { ColorPickerDialog.show(this) }

        val repoUrl = getString(R.string.repo_url)
        repoLink.text = repoUrl
        repoLink.setOnClickListener { copyRepoUrl(repoUrl) }

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

    private fun copyRepoUrl(url: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
        Toast.makeText(this, R.string.repo_copied, Toast.LENGTH_SHORT).show()
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
            onAlertConfigChanged()
        }
    }

    /** 慢充的判定窗口固定为插电 5 分钟后、近一分钟均值，只调阈值。 */
    private fun pickSlowThreshold() {
        showOptionPicker(SLOW_OPTIONS, ::slowLabel) {
            config.alertSlowWatts = it
            syncAlertLabels()
            onAlertConfigChanged()
        }
    }

    /** 阈值选择弹窗：只留一列居中的选项，宽度给屏幕一半，不再带标题（行名已经说明是哪个提醒）。 */
    /** 涓流提醒的电量门槛固定在 80%，只调功率阈值。 */
    private fun pickTrickleThreshold() {
        showOptionPicker(TRICKLE_OPTIONS, ::slowLabel) {
            config.alertTrickleWatts = it
            syncAlertLabels()
            onAlertConfigChanged()
        }
    }

    /**
     * 提醒的阈值/开关一改动：判定状态作废重算，并且顺手把充电期间的后台服务拉起来 ——
     * 提醒由那个服务判定，跟悬浮窗开没开无关。
     */
    private fun onAlertConfigChanged() {
        Alerts.reset()
        if (config.wantsChargeWatcher) {
            ChargeRecorderService.ensure(this)
        } else if (!config.recordWhileCharging) {
            ChargeRecorderService.stop(this)
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
        switchHideRecents.isChecked = config.hideFromRecents
        switchRecordCharging.isChecked = config.recordWhileCharging
        checkShowPower.isChecked = config.showPower
        checkShowTemp.isChecked = config.showTemp
        checkShowVolt.isChecked = config.showVoltage
        checkShowAmp.isChecked = config.showCurrent
        syncAlertLabels()

        seekTextSize.progress = (config.textSizeSp - Prefs.MIN_TEXT_SP).roundToInt()
        textSizeValue.text = "${config.textSizeSp.roundToInt()} sp"
        seekBackgroundAlpha.progress = config.backgroundAlphaPercent
        backgroundAlphaValue.text = "${config.backgroundAlphaPercent}%"
        syncCapacityUi()
        seekRefresh.progress = config.refreshMillis / 100 - 3
        refreshValue.text = "${config.refreshMillis / 1000f} 秒"
    }

    /**
     * 容量这一栏跟着「自动读取电池设计容量」切换：
     * 自动只显示识别结果，手动才给输入框。
     */
    private fun syncCapacityUi() {
        if (config.autoCapacity) {
            val design = BatteryReader.designCapacity(this)
            capacityAutoLabel.text = if (design > 0) {
                "自动识别：$design mAh（只在电流全部读不到时参与估算）"
            } else {
                "自动识别失败，暂用 ${config.capacityMah} mAh"
            }
            capacityAutoLabel.visibility = View.VISIBLE
            capacityManualRow.visibility = View.GONE
        } else {
            capacityAutoLabel.visibility = View.GONE
            capacityManualRow.visibility = View.VISIBLE
            if (capacityInput.text.toString() != config.capacityMah.toString()) {
                capacityInput.setText(config.capacityMah.toString())
            }
        }
    }

    private fun commitCapacityInput() {
        val typed = capacityInput.text.toString().toIntOrNull()
        if (typed != null) config.capacityMah = typed
        capacityInput.setText(config.capacityMah.toString())
        BatteryReader.reset()
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

/**
 * 桌面入口的两个互斥壳子，逻辑全在 MainActivity 里；分开写是因为只有 Activity 自己的
 * excludeFromRecents 会被 AMS 认，alias 上的会被忽略。
 */
class SettingsShown : MainActivity()

class SettingsHidden : MainActivity()
