package com.v2ray.ang.lite

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.os.health.HealthStats
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlin.math.max

data class LiteEnergyPoint(
    val timestamp: Long,
    val estimatedMah: Double,
)

data class LiteEnergySummary(
    val estimatedMah: Double = 0.0,
    val trafficBytes: Long = 0L,
    val points: List<LiteEnergyPoint> = emptyList(),
    val hasData: Boolean = false,
    val lastSampleAt: Long = 0L,
)

private data class RawEnergySnapshot(
    val timestamp: Long = 0L,
    val elapsedRealtimeMillis: Long = 0L,
    val wifiPowerMams: Long = -1L,
    val mobilePowerMams: Long = -1L,
    val userCpuMillis: Long = -1L,
    val systemCpuMillis: Long = -1L,
    val rxBytes: Long = -1L,
    val txBytes: Long = -1L,
)

private data class StoredEnergyInterval(
    val startTimestamp: Long = 0L,
    val timestamp: Long = 0L,
    val estimatedMah: Double = 0.0,
    val trafficBytes: Long = 0L,
)

internal object LiteEnergyWindow {
    fun overlapFraction(
        startTimestamp: Long,
        endTimestamp: Long,
        now: Long,
        windowMillis: Long,
    ): Double {
        val end = endTimestamp.coerceAtMost(now)
        val start = startTimestamp.takeIf { it in 1 until end } ?: (end - 1L)
        val overlap = (end - max(start, now - windowMillis)).coerceAtLeast(0L)
        val duration = (end - start).coerceAtLeast(1L)
        return if (overlap <= 0L) 0.0 else overlap.toDouble() / duration.toDouble()
    }
}

object LiteEnergyRepository {
    private const val UNSUPPORTED = -1L
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val RETENTION_MS = 8L * 24L * HOUR_MS
    private const val CPU_ESTIMATED_CURRENT_MA = 180.0
    private const val MILLIS_PER_HOUR = 3_600_000.0
    private val gson = Gson()

    @Synchronized
    fun captureSample(context: Context, force: Boolean = false): LiteEnergySummary {
        val now = System.currentTimeMillis()
        val previous = readBaseline()
        if (!force && previous != null && now - previous.timestamp < 50L * 60L * 1000L) {
            return summary(now)
        }

        val current = takeSnapshot(context, now) ?: return summary(now)
        val intervals = readIntervals().toMutableList()
        val sameBoot = previous != null &&
            previous.elapsedRealtimeMillis > 0L &&
            current.elapsedRealtimeMillis > previous.elapsedRealtimeMillis
        if (previous != null && sameBoot && current.timestamp > previous.timestamp) {
            val networkPowerMams = positiveDelta(current.wifiPowerMams, previous.wifiPowerMams) +
                positiveDelta(current.mobilePowerMams, previous.mobilePowerMams)
            val cpuMillis = positiveDelta(current.userCpuMillis, previous.userCpuMillis) +
                positiveDelta(current.systemCpuMillis, previous.systemCpuMillis)
            val measuredNetworkMah = networkPowerMams / MILLIS_PER_HOUR
            val estimatedCpuMah = cpuMillis * CPU_ESTIMATED_CURRENT_MA / MILLIS_PER_HOUR
            val trafficBytes = positiveDelta(current.rxBytes, previous.rxBytes) +
                positiveDelta(current.txBytes, previous.txBytes)
            val estimatedMah = (measuredNetworkMah + estimatedCpuMah).coerceIn(0.0, 1_000.0)

            intervals += StoredEnergyInterval(
                startTimestamp = previous.timestamp,
                timestamp = current.timestamp,
                estimatedMah = estimatedMah,
                trafficBytes = trafficBytes,
            )
        }

        val retained = intervals.filter { it.timestamp >= now - RETENTION_MS }
        MmkvManager.encodeSettings(AppConfig.PREF_LITE_ENERGY_SAMPLES, gson.toJson(retained))
        MmkvManager.encodeSettings(AppConfig.PREF_LITE_ENERGY_BASELINE, gson.toJson(current))
        return buildSummary(retained, now)
    }

    @Synchronized
    fun summary(now: Long = System.currentTimeMillis()): LiteEnergySummary =
        buildSummary(readIntervals(), now)

    private fun takeSnapshot(context: Context, timestamp: Long): RawEnergySnapshot? {
        val manager = context.getSystemService(Context.SYSTEM_HEALTH_SERVICE) as? SystemHealthManager
            ?: return null
        val health = runCatching { manager.takeMyUidSnapshot() }.getOrNull() ?: return null
        val uid = Process.myUid()
        return RawEnergySnapshot(
            timestamp = timestamp,
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            wifiPowerMams = health.measurementOrUnsupported(UidHealthStats.MEASUREMENT_WIFI_POWER_MAMS),
            mobilePowerMams = health.measurementOrUnsupported(UidHealthStats.MEASUREMENT_MOBILE_POWER_MAMS),
            userCpuMillis = health.measurementOrUnsupported(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS),
            systemCpuMillis = health.measurementOrUnsupported(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS),
            rxBytes = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0L } ?: UNSUPPORTED,
            txBytes = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0L } ?: UNSUPPORTED,
        )
    }

    private fun HealthStats.measurementOrUnsupported(key: Int): Long =
        if (hasMeasurement(key)) getMeasurement(key) else UNSUPPORTED

    private fun positiveDelta(current: Long, previous: Long): Long =
        if (current >= 0L && previous >= 0L && current >= previous) current - previous else 0L

    private fun readBaseline(): RawEnergySnapshot? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_LITE_ENERGY_BASELINE)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { gson.fromJson(it, RawEnergySnapshot::class.java) }.getOrNull() }

    private fun readIntervals(): List<StoredEnergyInterval> =
        MmkvManager.decodeSettingsString(AppConfig.PREF_LITE_ENERGY_SAMPLES)
            ?.takeIf { it.isNotBlank() }
            ?.let { json ->
                runCatching {
                    gson.fromJson(json, Array<StoredEnergyInterval>::class.java).toList()
                }.getOrNull()
            }
            .orEmpty()

    private fun buildSummary(
        intervals: List<StoredEnergyInterval>,
        now: Long,
    ): LiteEnergySummary {
        val windowStart = now - 24L * HOUR_MS
        val weighted = intervals.mapNotNull { interval ->
            // Old records did not store a start timestamp. Treat them as point samples rather
            // than charging their full unknown interval to whichever window contains the end.
            val fraction = LiteEnergyWindow.overlapFraction(
                startTimestamp = interval.startTimestamp,
                endTimestamp = interval.timestamp,
                now = now,
                windowMillis = now - windowStart,
            )
            if (fraction <= 0.0) null else interval to fraction
        }
        return LiteEnergySummary(
            estimatedMah = weighted.sumOf { (interval, fraction) ->
                interval.estimatedMah * fraction
            },
            trafficBytes = weighted.sumOf { (interval, fraction) ->
                (interval.trafficBytes.toDouble() * fraction).toLong()
            },
            points = weighted.map { (interval, fraction) ->
                LiteEnergyPoint(interval.timestamp, interval.estimatedMah * fraction)
            },
            hasData = weighted.isNotEmpty(),
            lastSampleAt = intervals.maxOfOrNull { it.timestamp } ?: 0L,
        )
    }
}
