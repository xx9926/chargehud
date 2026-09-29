package com.chargehud.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

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

    fun rememberPosition(x: Int, y: Int) {
        sp.edit()
            .putInt(Prefs.KEY_POS_X, x)
            .putInt(Prefs.KEY_POS_Y, y)
            .putBoolean(Prefs.KEY_POS_SAVED, true)
            .apply()
    }
}
