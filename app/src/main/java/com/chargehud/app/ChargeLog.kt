package com.chargehud.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class ChargeSample(
    val offsetSec: Int,
    val watts: Double,
    val amps: Double,
    val levelPercent: Int,
    val tempCelsius: Double,
)

data class ChargeSession(
    val startWallMillis: Long,
    val durationMillis: Long,
    val startLevel: Int,
    val endLevel: Int,
    val peakWatts: Double,
    val averageWatts: Double,
    val peakTempCelsius: Double,
    val gainedMah: Int,
    val capacityMah: Int,
    val samples: List<ChargeSample>,
    val live: Boolean = false,
) {
    val gainedPercent: Int get() = max(0, endLevel - startLevel)

    fun startTimeLabel(): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(startWallMillis))

    fun durationLabel(): String {
        val totalSeconds = durationMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours} 时 ${minutes} 分"
            minutes > 0 -> "${minutes} 分 ${seconds} 秒"
            else -> "${seconds} 秒"
        }
    }

    fun summary(): String {
        val parts = mutableListOf(
            if (live) "进行中" else startTimeLabel(),
            durationLabel(),
            "+$gainedPercent%",
            "峰值 ${watts(peakWatts)} W",
            "均 ${watts(averageWatts)} W",
        )
        if (gainedMah > 0) parts.add("$gainedMah mAh")
        if (!peakTempCelsius.isNaN()) parts.add("峰 ${String.format(Locale.CHINA, "%.1f", peakTempCelsius)} ℃")
        return parts.joinToString(" · ")
    }

    private fun watts(value: Double): String =
        if (value >= 100) String.format(Locale.CHINA, "%.0f", value)
        else String.format(Locale.CHINA, "%.1f", value)
}

/**
 * 充电档案：把每次插电到拔电的过程记成一条会话，追加在应用私有目录的 JSON Lines 文件里。
 * 采样跟随悬浮窗刷新，5 秒一条，点太多就整体抽稀，几十分钟的充电也只有几百个点。
 */
object ChargeLog {

    private const val TAG = "ChargeHud"
    private const val FILE_NAME = "charge_sessions.jsonl"
    private const val MAX_SESSIONS = 60
    private const val MIN_SESSION_MS = 30_000L
    private const val BASE_INTERVAL_MS = 5_000L
    private const val MAX_SAMPLES = 500

    private var appContext: Context? = null
    private var sessionActive = false
    private val samples = ArrayList<ChargeSample>()
    private var startElapsed = 0L
    private var startWall = 0L
    private var startLevel = 0
    private var capacityMah = 0
    private var lastSampleAt = 0L
    private var sampleIntervalMs = BASE_INTERVAL_MS
    private var peakWatts = 0.0
    private var peakTemp = Double.NaN
    private var wattSum = 0.0
    private var wattCount = 0
    private var gainedMahAccum = 0.0

    /** 由悬浮窗服务的刷新循环调用；服务没跑的时段自然没有记录。 */
    fun onReading(context: Context, reading: HudReading) {
        appContext = context.applicationContext
        if (!reading.charging) {
            closeIfOpen()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!sessionActive) {
            startSession(reading, now)
            return
        }
        if (now - lastSampleAt < sampleIntervalMs) return
        lastSampleAt = now
        val watts = if (reading.watts.isNaN() || reading.watts < 0) 0.0 else reading.watts
        samples.add(
            ChargeSample(
                offsetSec = ((now - startElapsed) / 1000).toInt(),
                watts = watts,
                amps = reading.amps,
                levelPercent = reading.levelPercent,
                tempCelsius = reading.tempCelsius,
            )
        )
        if (watts > peakWatts) peakWatts = watts
        if (!reading.tempCelsius.isNaN()) {
            peakTemp = if (peakTemp.isNaN()) reading.tempCelsius else max(peakTemp, reading.tempCelsius)
        }
        if (watts > 0) {
            wattSum += watts
            wattCount++
        }
        if (reading.amps > 0) {
            // 安培对时间积分就是充进去的容量，比按 1% 电量倒推细得多。
            gainedMahAccum += reading.amps * sampleIntervalMs / 3_600_000.0 * 1000.0
        }
        if (samples.size > MAX_SAMPLES) decimate()
    }

    /** 进行中的会话快照，档案页拿来显示"进行中"那一条。 */
    fun live(): ChargeSession? {
        if (!sessionActive || samples.size < 2) return null
        return snapshot().copy(live = true)
    }

    fun sessions(context: Context): List<ChargeSession> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .mapNotNull { line -> runCatching { decode(JSONObject(line)) }.getOrNull() }
                .reversed()
        }.onFailure { Log.w(TAG, "充电档案读取失败", it) }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        // 进行中的会话也要丢掉，否则拔线时又把它写回文件。
        resetSession()
        runCatching { file(context).delete() }.onFailure { Log.w(TAG, "档案清空失败", it) }
    }

    private fun startSession(reading: HudReading, now: Long) {
        sessionActive = true
        startElapsed = now
        startWall = System.currentTimeMillis()
        startLevel = reading.levelPercent
        capacityMah = reading.capacityMah
        samples.clear()
        lastSampleAt = now
        sampleIntervalMs = BASE_INTERVAL_MS
        peakWatts = 0.0
        peakTemp = if (reading.tempCelsius.isNaN()) Double.NaN else reading.tempCelsius
        wattSum = 0.0
        wattCount = 0
        gainedMahAccum = 0.0
    }

    private fun closeIfOpen() {
        if (!sessionActive) return
        val duration = SystemClock.elapsedRealtime() - startElapsed
        val endLevel = samples.lastOrNull()?.levelPercent ?: startLevel
        // 服务被杀掉的情况也算"结束"，只是这条太短或没有采样点就没有记录价值。
        val worthKeeping = duration >= MIN_SESSION_MS && samples.size >= 3
        val session = snapshot().copy(live = false, endLevel = endLevel)
        resetSession()
        if (worthKeeping) persist(session)
    }

    private fun resetSession() {
        sessionActive = false
        samples.clear()
        peakWatts = 0.0
        peakTemp = Double.NaN
        wattSum = 0.0
        wattCount = 0
        gainedMahAccum = 0.0
    }

    private fun snapshot(): ChargeSession = ChargeSession(
        startWallMillis = startWall,
        durationMillis = SystemClock.elapsedRealtime() - startElapsed,
        startLevel = startLevel,
        endLevel = samples.lastOrNull()?.levelPercent ?: startLevel,
        peakWatts = peakWatts,
        averageWatts = if (wattCount > 0) wattSum / wattCount else 0.0,
        peakTempCelsius = peakTemp,
        gainedMah = gainedMahAccum.toInt(),
        capacityMah = capacityMah,
        samples = samples.toList(),
    )

    /** 抽稀：丢掉一半采样点并把间隔翻倍，长时间充电也不会撑爆文件。 */
    private fun decimate() {
        val kept = ArrayList<ChargeSample>(samples.size / 2 + 1)
        samples.forEachIndexed { index, sample ->
            if (index % 2 == 0 || index == samples.lastIndex) kept.add(sample)
        }
        samples.clear()
        samples.addAll(kept)
        sampleIntervalMs *= 2
    }

    private fun persist(session: ChargeSession) {
        val context = appContext ?: return
        val line = encode(session)
        Thread {
            runCatching {
                file(context).appendText(line + "\n")
                trimToMax(context)
                Log.i(TAG, "充电档案已记录：${session.summary()}")
            }.onFailure { Log.w(TAG, "充电档案写入失败", it) }
        }.start()
    }

    private fun trimToMax(context: Context) {
        val target = file(context)
        if (!target.exists()) return
        val lines = target.readLines()
        if (lines.size <= MAX_SESSIONS) return
        target.writeText(lines.takeLast(MAX_SESSIONS).joinToString("\n") + "\n")
    }

    private fun encode(session: ChargeSession): String {
        val points = JSONArray()
        session.samples.forEach { sample ->
            points.put(
                JSONArray()
                    .put(sample.offsetSec)
                    .put(round1(sample.watts))
                    .put(round2(sample.amps))
                    .put(sample.levelPercent)
                    .put(if (sample.tempCelsius.isNaN()) -1.0 else round1(sample.tempCelsius))
            )
        }
        return JSONObject()
            .put("start", session.startWallMillis)
            .put("duration", session.durationMillis)
            .put("startLevel", session.startLevel)
            .put("endLevel", session.endLevel)
            .put("peak", round1(session.peakWatts))
            .put("avg", round1(session.averageWatts))
            .put("peakTemp", if (session.peakTempCelsius.isNaN()) -1.0 else round1(session.peakTempCelsius))
            .put("mah", session.gainedMah)
            .put("capacity", session.capacityMah)
            .put("samples", points)
            .toString()
    }

    private fun decode(json: JSONObject): ChargeSession {
        val points = json.optJSONArray("samples") ?: JSONArray()
        val list = ArrayList<ChargeSample>(points.length())
        for (index in 0 until points.length()) {
            val row = points.optJSONArray(index) ?: continue
            if (row.length() < 5) continue
            val temp = row.getDouble(4)
            list.add(
                ChargeSample(
                    offsetSec = row.getInt(0),
                    watts = row.getDouble(1),
                    amps = row.getDouble(2),
                    levelPercent = row.getInt(3),
                    tempCelsius = if (temp < 0) Double.NaN else temp,
                )
            )
        }
        val peakTemp = json.optDouble("peakTemp", -1.0)
        return ChargeSession(
            startWallMillis = json.optLong("start"),
            durationMillis = json.optLong("duration"),
            startLevel = json.optInt("startLevel"),
            endLevel = json.optInt("endLevel"),
            peakWatts = json.optDouble("peak", 0.0),
            averageWatts = json.optDouble("avg", 0.0),
            peakTempCelsius = if (peakTemp < 0) Double.NaN else peakTemp,
            gainedMah = json.optInt("mah"),
            capacityMah = json.optInt("capacity"),
            samples = list,
        )
    }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)
}
