package com.chargehud.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.TypedValue

object Prefs {
    const val FILE = "charge_hud"

    const val KEY_ENABLED = "enabled"
    const val KEY_LOCKED = "locked"
    const val KEY_TEXT_COLOR = "text_color"
    const val KEY_TEXT_SIZE_SP = "text_size_sp"
    const val KEY_BG_ALPHA_PCT = "bg_alpha_pct"
    const val KEY_POS_X = "pos_x"
    const val KEY_POS_Y = "pos_y"
    const val KEY_POS_SAVED = "pos_saved"
    const val KEY_REFRESH_MS = "refresh_ms"
    const val KEY_SYSFS = "sysfs"
    const val KEY_CAPACITY = "capacity_mah"
    const val KEY_AUTO_CAPACITY = "auto_capacity"
    const val KEY_DUAL_CELL = "dual_cell"
    const val KEY_HALF_VOLTAGE = "half_voltage"
    const val KEY_RESIDENT_NOTIFICATION = "resident_notification"
    const val KEY_SHOW_POWER = "show_power"
    const val KEY_SHOW_TEMP = "show_temp"
    const val KEY_SHOW_VOLT = "show_volt"
    const val KEY_SHOW_AMP = "show_amp"
    const val KEY_FIELD_ORDER = "field_order"
    const val KEY_ALERT_TEMP_C = "alert_temp_c"
    const val KEY_ALERT_SLOW_W = "alert_slow_w"
    const val KEY_ALERT_FULL = "alert_full"
    const val KEY_ALERT_TRICKLE_W = "alert_trickle_w"

    const val MIN_TEXT_SP = 10f
    const val MAX_TEXT_SP = 40f

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/** Typed view over the shared preferences that both the settings UI and the overlay read. */
class HudConfig(context: Context) {

    private val sp = Prefs.of(context)

    var enabled: Boolean
        get() = sp.getBoolean(Prefs.KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(Prefs.KEY_ENABLED, value).apply()

    var locked: Boolean
        get() = sp.getBoolean(Prefs.KEY_LOCKED, false)
        set(value) = sp.edit().putBoolean(Prefs.KEY_LOCKED, value).apply()

    var textColor: Int
        get() = sp.getInt(Prefs.KEY_TEXT_COLOR, Color.WHITE)
        set(value) = sp.edit().putInt(Prefs.KEY_TEXT_COLOR, value).apply()

    var textSizeSp: Float
        get() = sp.getFloat(Prefs.KEY_TEXT_SIZE_SP, 16f)
        set(value) = sp.edit().putFloat(Prefs.KEY_TEXT_SIZE_SP, value).apply()

    var backgroundAlphaPercent: Int
        get() = sp.getInt(Prefs.KEY_BG_ALPHA_PCT, 45)
        set(value) = sp.edit().putInt(Prefs.KEY_BG_ALPHA_PCT, value.coerceIn(0, 100)).apply()

    var posX: Int
        get() = sp.getInt(Prefs.KEY_POS_X, -1)
        set(value) = sp.edit().putInt(Prefs.KEY_POS_X, value).apply()

    var posY: Int
        get() = sp.getInt(Prefs.KEY_POS_Y, -1)
        set(value) = sp.edit().putInt(Prefs.KEY_POS_Y, value).apply()

    var positionSaved: Boolean
        get() = sp.getBoolean(Prefs.KEY_POS_SAVED, false)
        set(value) = sp.edit().putBoolean(Prefs.KEY_POS_SAVED, value).apply()

    var refreshMillis: Int
        get() = sp.getInt(Prefs.KEY_REFRESH_MS, 1000)
        set(value) = sp.edit().putInt(Prefs.KEY_REFRESH_MS, value.coerceIn(300, 5000)).apply()

    var useSysFs: Boolean
        get() = sp.getBoolean(Prefs.KEY_SYSFS, true)
        set(value) = sp.edit().putBoolean(Prefs.KEY_SYSFS, value).apply()

    var capacityMah: Int
        get() = sp.getInt(Prefs.KEY_CAPACITY, 5000)
        set(value) = sp.edit().putInt(Prefs.KEY_CAPACITY, value.coerceIn(1000, 10000)).apply()

    /** 关掉后用 capacityMah 手填值，反射读设计容量失败的机型会自动落回手填值。 */
    var autoCapacity: Boolean
        get() = sp.getBoolean(Prefs.KEY_AUTO_CAPACITY, true)
        set(value) = sp.edit().putBoolean(Prefs.KEY_AUTO_CAPACITY, value).apply()

    /** 双电芯并充的机型：框架只报单颗电芯电流，总功率要 x2。 */
    var dualCell: Boolean
        get() = sp.getBoolean(Prefs.KEY_DUAL_CELL, false)
        set(value) = sp.edit().putBoolean(Prefs.KEY_DUAL_CELL, value).apply()

    /** 串联电池组报的是两颗电芯之和的电压，需要 /2 才是单颗电芯电压。 */
    var halfVoltage: Boolean
        get() = sp.getBoolean(Prefs.KEY_HALF_VOLTAGE, false)
        set(value) = sp.edit().putBoolean(Prefs.KEY_HALF_VOLTAGE, value).apply()

    /** 关掉后服务退出前台态、通知撤销：通知栏不再常驻，代价是后台更容易被系统回收。 */
    var residentNotification: Boolean
        get() = sp.getBoolean(Prefs.KEY_RESIDENT_NOTIFICATION, true)
        set(value) = sp.edit().putBoolean(Prefs.KEY_RESIDENT_NOTIFICATION, value).apply()

    /** 悬浮窗上显示哪几行。四个全关掉时窗口收成不可见，不再留一个空底板。 */
    var showPower: Boolean
        get() = sp.getBoolean(Prefs.KEY_SHOW_POWER, true)
        set(value) = sp.edit().putBoolean(Prefs.KEY_SHOW_POWER, value).apply()

    var showTemp: Boolean
        get() = sp.getBoolean(Prefs.KEY_SHOW_TEMP, true)
        set(value) = sp.edit().putBoolean(Prefs.KEY_SHOW_TEMP, value).apply()

    var showVoltage: Boolean
        get() = sp.getBoolean(Prefs.KEY_SHOW_VOLT, false)
        set(value) = sp.edit().putBoolean(Prefs.KEY_SHOW_VOLT, value).apply()

    var showCurrent: Boolean
        get() = sp.getBoolean(Prefs.KEY_SHOW_AMP, false)
        set(value) = sp.edit().putBoolean(Prefs.KEY_SHOW_AMP, value).apply()

    /**
     * 悬浮窗的行顺序 = 用户勾选这些字段的先后顺序。老数据里没有这个键，
     * 或勾选项没被记进顺序表时，按默认顺序（温度、功率、电压、电流）补到末尾。
     */    var fieldOrder: List<String>
        get() {
            val stored = sp.getString(Prefs.KEY_FIELD_ORDER, "")
                ?.split(SEPARATOR)
                ?.filter { it in FIELD_IDS && fieldShown(it) }
                ?: emptyList()
            val untracked = FIELD_IDS.filter { fieldShown(it) && it !in stored }
            return stored + untracked
        }
        set(value) = sp.edit().putString(Prefs.KEY_FIELD_ORDER, value.joinToString(SEPARATOR)).apply()

    /** 电池温度提醒阈值（℃），0 表示不提醒。 */
    var alertTempCelsius: Int
        get() = sp.getInt(Prefs.KEY_ALERT_TEMP_C, DEFAULT_ALERT_TEMP_C)
        set(value) = sp.edit().putInt(Prefs.KEY_ALERT_TEMP_C, value.coerceIn(0, 60)).apply()

    /** 慢充提醒：插电满 5 分钟后近一分钟均功率低于这个瓦特数就提醒，0 表示不提醒。 */
    var alertSlowWatts: Int
        get() = sp.getInt(Prefs.KEY_ALERT_SLOW_W, 0)
        set(value) = sp.edit().putInt(Prefs.KEY_ALERT_SLOW_W, value.coerceIn(0, 20)).apply()

    /** 充满提醒：框架报 FULL 或电量到 100% 时提醒一次，每次插电重新计。 */
    var alertFullEnabled: Boolean
        get() = sp.getBoolean(Prefs.KEY_ALERT_FULL, true)
        set(value) = sp.edit().putBoolean(Prefs.KEY_ALERT_FULL, value).apply()

    /** 涓流提醒：电量到 80% 以上、近一分钟均功率低于这个瓦特数时提醒一次，0 表示不提醒。 */
    var alertTrickleWatts: Int
        get() = sp.getInt(Prefs.KEY_ALERT_TRICKLE_W, DEFAULT_ALERT_TRICKLE_W)
        set(value) = sp.edit().putInt(Prefs.KEY_ALERT_TRICKLE_W, value.coerceIn(0, 20)).apply()

    fun fieldShown(id: String): Boolean = when (id) {
        FIELD_POWER -> showPower
        FIELD_TEMP -> showTemp
        FIELD_VOLT -> showVoltage
        FIELD_AMP -> showCurrent
        else -> false
    }

    /** 勾上排到最后、取消勾选移出顺序表，取消后再勾回来就重新排队。 */
    fun setFieldShown(id: String, shown: Boolean) {
        when (id) {
            FIELD_POWER -> showPower = shown
            FIELD_TEMP -> showTemp = shown
            FIELD_VOLT -> showVoltage = shown
            FIELD_AMP -> showCurrent = shown
        }
        fieldOrder = fieldOrder.toMutableList().apply {
            remove(id)
            if (shown) add(id)
        }
    }

    fun rememberPosition(x: Int, y: Int) {
        sp.edit()
            .putInt(Prefs.KEY_POS_X, x)
            .putInt(Prefs.KEY_POS_Y, y)
            .putBoolean(Prefs.KEY_POS_SAVED, true)
            .apply()
    }

    companion object {
        const val FIELD_POWER = "power"
        const val FIELD_TEMP = "temp"
        const val FIELD_VOLT = "volt"
        const val FIELD_AMP = "amp"

        /** 没勾选过任何顺序时的排列：温度在上、功率在下。 */
        private val FIELD_IDS = listOf(FIELD_TEMP, FIELD_POWER, FIELD_VOLT, FIELD_AMP)
        private const val SEPARATOR = ","

        /** 天玑平台实测：大功率快充时机身 39~41 ℃ 属正常，42 ℃ 以上才值得提醒。 */
        const val DEFAULT_ALERT_TEMP_C = 42

        /** 本机满充段实测功率只有 7~8 W，涓流一般在 3 W 以下。 */
        const val DEFAULT_ALERT_TRICKLE_W = 3
    }
}

/** selectableItemBackground 是主题属性，要先解析出它指向的 drawable id 才能 setBackgroundResource。 */
fun Context.selectableItemBackground(): Int {
    val value = TypedValue()
    return if (theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
        value.resourceId
    } else {
        0
    }
}
