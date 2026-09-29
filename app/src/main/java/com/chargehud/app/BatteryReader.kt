package com.chargehud.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PowerSource {
    /** BatteryManager.getIntProperty 暴露的框架电流，第三方应用可读。 */
    FRAMEWORK,

    /** 从 /sys/class/power_supply 读到真实瞬时电流。 */
    SYSTEM_FILE,

    /** 用 BATTERY_PROPERTY_CHARGE_COUNTER（库仑计）差分得到的电流。 */
    CHARGE_COUNTER,

    /** 用电池容量 x 电量变化速率推算。 */
    ESTIMATED,

    UNKNOWN
}

class HudReading(
    val watts: Double,
    val source: PowerSource,
    val volts: Double,
    val amps: Double,
    val tempCelsius: Double,
    val tempFromSystemApi: Boolean,
    val levelPercent: Int,
    val status: Int,
    val plugged: Int,
    val capacityMah: Int = 0,
) {
    val charging: Boolean get() = plugged != 0
    val hasPower: Boolean get() = source != PowerSource.UNKNOWN && !watts.isNaN()

    /** 只有直接测到瞬时电流的来源不加约等号。符号保留：负值=已插电但电池在净放电。 */
    fun formatPower(): String =
        if (!hasPower) {
            "--"
        } else {
            val measured = source == PowerSource.FRAMEWORK || source == PowerSource.SYSTEM_FILE
            val value = if (abs(watts) >= 100) String.format("%.0f", watts) else String.format("%.1f", watts)
            (if (measured) "" else "≈") + value + " W"
        }

    fun formatTemp(): String =
        if (tempCelsius.isNaN()) "--" else String.format("%.1f ℃", tempCelsius)
}

/**
 * Android 没有对第三方开放充电瓦特数，这里按 框架瞬时电流 -> sysfs 实测 -> 库仑计差分 -> 电量变化率估算
 * 的优先级取数，功率 = 电压 x 电流，再按机型情况施加双电芯/串联电压修正。
 */
object BatteryReader {

    private const val LEVEL_WINDOW_MS = 60_000L
    private const val COUNTER_WINDOW_MS = 20_000L
    private const val FRAMEWORK_WINDOW_MS = 6_000L
    private const val UNSUPPORTED = Int.MIN_VALUE
    private const val MIN_AMPS = 0.0005
    private const val MAX_AMPS = 8.0
    private const val TAG = "ChargeHud"

    private var lastLoggedSource: PowerSource? = null
    private var lastWatts = Double.NaN

    private val CURRENT_PATHS = listOf(
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/battery/current_avg",
        "/sys/class/power_supply/battery/batt_current_ua",
        "/sys/class/power_supply/battery/batt_current_now",
        "/sys/class/power_supply/battery/batt_current",
        "/sys/class/power_supply/battery/ibatt",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/main/current_now",
        "/sys/class/power_supply/batt_current",
        "/sys/class/power_supply/cw221x-bat/current_now",
        "/sys/class/power_supply/bq27546-0/current_now",
        "/sys/class/power_supply/Battery/current_now",
        "/sys/class/power_supply/usb/current_now",
    )

    private val VOLTAGE_PATHS = listOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/battery/voltage_avg",
        "/sys/class/power_supply/battery/batt_voltage",
        "/sys/class/power_supply/bms/voltage_now",
        "/sys/class/power_supply/batt_voltage",
        "/sys/class/power_supply/cw221x-bat/voltage_now",
        "/sys/class/power_supply/Battery/voltage_now",
    )

    private val TEMP_PATHS = listOf(
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/bms/temp",
        "/sys/class/power_supply/Battery/temp",
    )

    private val levelSamples = ArrayDeque<LongArray>()
    private val counterSamples = ArrayDeque<LongArray>()
    private val ampsSamples = ArrayDeque<LongArray>()
    private var smoothedWatts = Double.NaN
    private var smoothedSource: PowerSource? = null
    private var probedCapacityMah = -1

    fun reset() {
        levelSamples.clear()
        counterSamples.clear()
        ampsSamples.clear()
        smoothedWatts = Double.NaN
        smoothedSource = null
        lastLoggedSource = null
    }

    /** 服务启动时调用一次，把可访问的电流节点写进 logcat，方便判断这台机器能走哪条路。 */
    fun logNodeProbe() {
        val report = CURRENT_PATHS.joinToString("; ") { path ->
            "$path=${if (readSingle(path) != null) "可读" else "不可读"}"
        }
        Log.i(TAG, "电流节点探测：$report")
    }

    private fun fmt(value: Double): String =
        if (value.isNaN()) "--" else String.format("%.2f", value)

    fun read(context: Context, settings: HudConfig = HudConfig(context)): HudReading {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = context.getSystemService(BatteryManager::class.java)

        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        var level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        if (level < 0) {
            level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        }
        val status = sticky?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        var tempCelsius = if (tempTenths in 1..2000) tempTenths / 10.0 else Double.NaN
        val tempFromSystemApi = !tempCelsius.isNaN()
        if (tempCelsius.isNaN() && settings.useSysFs) {
            tempCelsius = readNumber(TEMP_PATHS)?.let { if (it > 100) it / 10.0 else it } ?: Double.NaN
        }

        val sysVoltage = if (settings.useSysFs) readNumber(VOLTAGE_PATHS) else null
        val sysCurrent = if (settings.useSysFs) readNumber(CURRENT_PATHS) else null

        val extraVoltageMilli = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        var volts = if (extraVoltageMilli > 2_000) extraVoltageMilli / 1_000.0 else Double.NaN
        if (volts.isNaN() && sysVoltage != null) {
            volts = if (sysVoltage > 100_000) sysVoltage / 1_000_000.0 else sysVoltage / 1_000.0
        }
        if (settings.halfVoltage && !volts.isNaN()) volts /= 2.0
        val voltageIsMicro = sysVoltage != null && sysVoltage > 100_000

        val currentNow = readProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentAverage = readProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val counter = readProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        var amps = Double.NaN
        var source = PowerSource.UNKNOWN

        val frameworkAmps = toAmps(currentNow) ?: toAmps(currentAverage)
        if (frameworkAmps != null) {
            ampsSamples.addLast(
                longArrayOf(SystemClock.elapsedRealtime(), (frameworkAmps * 1_000_000).toLong())
            )
        }
        val windowStart = SystemClock.elapsedRealtime() - FRAMEWORK_WINDOW_MS
        while (ampsSamples.isNotEmpty() && ampsSamples.first()[0] < windowStart) {
            ampsSamples.removeFirst()
        }
        val frameworkMean = frameworkMean()
        if (frameworkMean != null && !volts.isNaN()) {
            amps = frameworkMean
            source = PowerSource.FRAMEWORK
        }

        if (source == PowerSource.UNKNOWN && sysCurrent != null && !volts.isNaN()) {
            val ampsFromFile = if (voltageIsMicro || abs(sysCurrent) >= 100_000) {
                sysCurrent / 1_000_000.0
            } else {
                sysCurrent / 1_000.0
            }
            if (abs(ampsFromFile) in MIN_AMPS..MAX_AMPS) {
                amps = ampsFromFile
                source = PowerSource.SYSTEM_FILE
            }
        }

        if (source == PowerSource.UNKNOWN && !volts.isNaN() && counter != null) {
            amps = estimateAmpsFromCounter(SystemClock.elapsedRealtime(), counter)
            if (!amps.isNaN()) source = PowerSource.CHARGE_COUNTER
        }

        val capacity = effectiveCapacity(context, settings)
        if (source == PowerSource.UNKNOWN && !volts.isNaN() && level >= 0) {
            amps = estimateAmps(SystemClock.elapsedRealtime(), level, scale, capacity)
            if (!amps.isNaN()) source = PowerSource.ESTIMATED
        }

        val cellFactor = if (settings.dualCell) 2.0 else 1.0
        val rawWatts = if (source == PowerSource.UNKNOWN) Double.NaN else volts * amps * cellFactor
        val watts = if (rawWatts.isNaN()) Double.NaN else smooth(rawWatts, source)

        if (source != lastLoggedSource || (watts.isNaN() != lastWatts.isNaN()) || abs(watts - lastWatts) > 0.3) {
            lastLoggedSource = source
            lastWatts = watts
            Log.i(
                TAG,
                "来源=$source 电压=${fmt(volts)}V 电流=${fmt(amps)}A 功率=${fmt(watts)}W " +
                    "温度=${fmt(tempCelsius)}℃ 电量=$level 容量=$capacity " +
                    "框架瞬时=$currentNow 框架平均=$currentAverage 框架样本=${ampsSamples.size} 库仑计=$counter " +
                    "sysfs电压=$sysVoltage sysfs电流=$sysCurrent 双电芯=${settings.dualCell}"
            )
        }

        return HudReading(
            watts = watts,
            source = source,
            volts = volts,
            amps = if (amps.isNaN()) 0.0 else amps,
            tempCelsius = tempCelsius,
            tempFromSystemApi = tempFromSystemApi,
            levelPercent = if (scale > 0) level * 100 / scale else level,
            status = status,
            plugged = plugged,
            capacityMah = capacity,
        )
    }

    /** 框架属性读不到或不返回有效值时给 null，避免把 0 当成真实读数。 */
    private fun readProperty(manager: BatteryManager?, property: Int): Int? = try {
        (manager?.getIntProperty(property) ?: 0).takeIf { it != 0 && it != UNSUPPORTED }
    } catch (error: Exception) {
        null
    }

    /** 约定单位是 µA，但不少机型给 mA，取落在合理区间里的那种解释。保留符号，均值算完再取绝对值。 */
    private fun toAmps(rawValue: Int?): Double? {
        val value = rawValue?.toDouble() ?: return null
        val magnitude = abs(value)
        if (magnitude < 1.0) return null
        val micro = magnitude / 1_000_000.0
        if (micro in MIN_AMPS..MAX_AMPS) return value / 1_000_000.0
        val milli = magnitude / 1_000.0
        return if (milli in MIN_AMPS..MAX_AMPS) value / 1_000.0 else null
    }

    /** 框架瞬时电流逐秒采样很跳，取最近几秒的带符号均值，方向（充/放）要保留。 */
    private fun frameworkMean(): Double? {
        if (ampsSamples.isEmpty()) return null
        val totalMicroAmps = ampsSamples.sumOf { it[1] }
        val mean = totalMicroAmps.toDouble() / ampsSamples.size / 1_000_000.0
        return mean.takeIf { abs(it) in MIN_AMPS..MAX_AMPS }
    }

    /** PowerProfile 里的设计容量，反射失败返回 -1，此时退回设置里手填的容量。 */
    fun designCapacity(context: Context): Int {
        if (probedCapacityMah >= 0) return probedCapacityMah
        probedCapacityMah = try {
            val profileClass = Class.forName("com.android.internal.os.PowerProfile")
            val profile = profileClass.getConstructor(Context::class.java)
                .newInstance(context.applicationContext)
            val value = profileClass.getMethod("getAveragePower", String::class.java)
                .invoke(profile, "battery.capacity") as? Double ?: 0.0
            if (value in 500.0..20_000.0) value.roundToInt() else -1
        } catch (error: Exception) {
            Log.i(TAG, "PowerProfile 容量不可用：${error.javaClass.simpleName}")
            -1
        }
        Log.i(TAG, "设计容量=${if (probedCapacityMah > 0) "$probedCapacityMah mAh" else "读不到"}")
        return probedCapacityMah
    }

    private fun effectiveCapacity(context: Context, settings: HudConfig): Int {
        if (!settings.autoCapacity) return settings.capacityMah
        return designCapacity(context).takeIf { it > 0 } ?: settings.capacityMah
    }

    /** 库仑计差分：charge counter 单位通常是 µAh，秒级就能看出变化，比按 1% 电量倒推准得多。 */
    private fun estimateAmpsFromCounter(now: Long, counter: Int): Double {
        if (counter <= 0) return Double.NaN
        val previous = counterSamples.lastOrNull()
        if (previous == null || now - previous[0] > 2_000) {
            counterSamples.addLast(longArrayOf(now, counter.toLong()))
        }
        while (counterSamples.size > 1 && now - counterSamples.first()[0] > COUNTER_WINDOW_MS) {
            counterSamples.removeFirst()
        }
        val first = counterSamples.first()
        val last = counterSamples.last()
        val hours = (last[0] - first[0]) / 3_600_000.0
        if (hours < 0.0015 || first[1] == last[1]) return Double.NaN

        val microAmpHours = (last[1] - first[1]).toDouble()
        val amps = microAmpHours / hours / 1_000_000.0
        return if (abs(amps) in MIN_AMPS..MAX_AMPS) amps else Double.NaN
    }

    /** 前面几档都读不到时的兜底：电量变化速率 x 设计容量。需要连续观测 40 秒以上才有意义。 */
    private fun estimateAmps(now: Long, level: Int, scale: Int, capacityMah: Int): Double {
        val previous = levelSamples.lastOrNull()
        if (previous == null || previous[1] != level.toLong() || now - previous[0] > 5_000) {
            levelSamples.addLast(longArrayOf(now, level.toLong()))
        }
        while (levelSamples.size > 1 && now - levelSamples.first()[0] > LEVEL_WINDOW_MS) {
            levelSamples.removeFirst()
        }

        val first = levelSamples.first()
        val last = levelSamples.last()
        val hours = (last[0] - first[0]) / 3_600_000.0
        if (hours < 0.011 || first[1] == last[1]) return Double.NaN

        val milliAmpHours = (last[1] - first[1]).toDouble() * capacityMah / scale
        val amps = milliAmpHours / hours / 1000.0
        return if (abs(amps) in 0.001..MAX_AMPS) amps else Double.NaN
    }

    private fun smooth(value: Double, source: PowerSource): Double {
        if (smoothedWatts.isNaN() || smoothedSource != source) {
            smoothedWatts = value
            smoothedSource = source
            return value
        }
        if (source == PowerSource.FRAMEWORK) return value
        val factor = if (source == PowerSource.SYSTEM_FILE) 0.4 else 0.25
        smoothedWatts += (value - smoothedWatts) * factor
        return smoothedWatts
    }

    /** 返回第一个可读且可解析的路径数值；读不到返回 null。 */
    private fun readNumber(paths: List<String>): Double? {
        for (path in paths) {
            val value = readSingle(path)
            if (value != null) return value
        }
        return null
    }

    private fun readSingle(path: String): Double? = try {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            null
        } else {
            file.inputStream().use { stream ->
                String(stream.readBytes()).trim().toDoubleOrNull()
            }
        }
    } catch (error: Exception) {
        null
    }
}
