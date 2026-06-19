package com.example.cli

import android.app.ActivityManager
import kotlinx.serialization.Serializable
import java.io.File
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
data class MetricsResult(
    val memory: MemoryMetrics,
    val power: PowerMetrics?,
    val cpu: CpuMetrics?
)

@Serializable
data class MemoryMetrics(
    val beforeMB: Long,
    val meanMB: Long,
    val peakMB: Long
)

@Serializable
data class PowerMetrics(
    val currentMa: FloatMetricStats,
    val voltageV: FloatMetricStats,
    val powerMw: FloatMetricStats,
    val temperatureC: FloatMetricStats,
    val isPlugged: Boolean,
    val isCharging: Boolean,
    val chargeCounter: ChargeCounterMetrics?
)

@Serializable
data class FloatMetricStats(
    val mean: Float,
    val peak: Float
)

@Serializable
data class ChargeCounterMetrics(
    val startMicroAh: Int,
    val endMicroAh: Int,
    val consumedMicroAh: Int,
    val estimatedEnergyJ: Float?
)

@Serializable
data class CpuMetrics(
    val processUsagePercent: FloatMetricStats,
    // Peak is not the peak per-core CPU utilization.
    // It is the per-core average from the sample where total process CPU utilization was highest.
    val processUsagePercentPerCore: FloatMetricStats,
    val availableProcessors: Int
)

class FloatStats {
    private var sum = 0.0
    private var count = 0
    var peak = 0f

    fun add(v: Float) {
        sum += v
        count++
        if (v > peak) peak = v
    }

    fun mean(): Float = if (count == 0) 0f else (sum / count).toFloat()

    fun hasSamples(): Boolean = count > 0
}

class LongStats {
    private var sum = 0L
    private var count = 0
    var peak = 0L

    fun add(v: Long) {
        sum += v
        count++
        if (v > peak) peak = v
    }

    fun mean(): Long = if (count == 0) 0L else sum / count
}

object MemorySampler {
    fun sample(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val usedMb = (mi.totalMem - mi.availMem) / (1024 * 1024)

        return usedMb
    }
}

class CpuSampler {
    private var previousCpuTimeMs: Long? = null
    private var previousWallTimeMs: Long? = null

    fun sample(): Float? {
        val currentCpuTimeMs = Process.getElapsedCpuTime()
        val currentWallTimeMs = SystemClock.elapsedRealtime()

        val lastCpuTimeMs = previousCpuTimeMs
        val lastWallTimeMs = previousWallTimeMs

        previousCpuTimeMs = currentCpuTimeMs
        previousWallTimeMs = currentWallTimeMs

        if (lastCpuTimeMs == null || lastWallTimeMs == null) {
            return null
        }

        val cpuDeltaMs = currentCpuTimeMs - lastCpuTimeMs
        val wallDeltaMs = currentWallTimeMs - lastWallTimeMs

        if (wallDeltaMs <= 0L) {
            return null
        }

        return (cpuDeltaMs.toFloat() / wallDeltaMs.toFloat()) * 100f
    }
}

class PowerSampler(private val context: Context) {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val currentStats = FloatStats()
    private val voltageStats = FloatStats()
    private val powerStats = FloatStats()
    private val temperatureStats = FloatStats()

    private var isPlugged = false
    private var isCharging = false
    private var startChargeCounterMicroAh: Int? = null
    private var endChargeCounterMicroAh: Int? = null

    /**
     * Sample current power consumption and update internal statistics.
     * @return Current power consumption in milliwatts (mW), or null if measurement failed
     */
    fun sample(): Float? {
        try {
            // Get battery current in microamperes (μA). Some devices return 0 or Int.MIN_VALUE
            // when a current reading is unavailable, so prefer CURRENT_NOW and fall back to CURRENT_AVERAGE.
            val chargeCounterMicroAh = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (chargeCounterMicroAh != Int.MIN_VALUE && chargeCounterMicroAh > 0) {
                if (startChargeCounterMicroAh == null) {
                    startChargeCounterMicroAh = chargeCounterMicroAh
                }
                endChargeCounterMicroAh = chargeCounterMicroAh
            }

            val currentNowMicroAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentAverageMicroAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            val currentMicroAmps = when {
                currentNowMicroAmps != 0 && currentNowMicroAmps != Int.MIN_VALUE -> currentNowMicroAmps
                currentAverageMicroAmps != 0 && currentAverageMicroAmps != Int.MIN_VALUE -> currentAverageMicroAmps
                else -> return null
            }

            // Some devices return negative values when discharging. Use magnitude for battery-side power estimate.
            val currentMa = abs(currentMicroAmps) / 1000f

            // Get voltage and temperature from battery intent
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)

            val pluggedStatus = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val batteryState = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            isPlugged = isPlugged || pluggedStatus != 0
            isCharging = isCharging ||
                batteryState == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryState == BatteryManager.BATTERY_STATUS_FULL

            // Get voltage in millivolts (mV)
            val voltageMilliVolts = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            if (voltageMilliVolts !in 3000..5000) {
                return null
            }
            val voltageV = voltageMilliVolts / 1000f

            // Get temperature in deci-degrees Celsius (e.g., 251 = 25.1°C)
            val temperatureDeciCelsius = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val temperatureC = temperatureDeciCelsius / 10f

            // Calculate power: P = I × V (in milliwatts)
            val powerMw = currentMa * voltageV

            // Update statistics
            currentStats.add(currentMa)
            voltageStats.add(voltageV)
            powerStats.add(powerMw)
            temperatureStats.add(temperatureC)

            return powerMw

        } catch (e: Exception) {
            // Return null on error (device may not support battery stats)
            return null
        }
    }

    /**
     * Get summary statistics for all metrics
     */
    fun getStats(): PowerSamplerStats {
        return PowerSamplerStats(
            currentMa = FloatMetricStats(currentStats.mean(), currentStats.peak),
            voltageV = FloatMetricStats(voltageStats.mean(), voltageStats.peak),
            powerMw = FloatMetricStats(powerStats.mean(), powerStats.peak),
            temperatureC = FloatMetricStats(temperatureStats.mean(), temperatureStats.peak),
            isPlugged = isPlugged,
            isCharging = isCharging,
            chargeCounter = buildChargeCounterMetrics()
        )
    }

    private fun buildChargeCounterMetrics(): ChargeCounterMetrics? {
        val start = startChargeCounterMicroAh ?: return null
        val end = endChargeCounterMicroAh ?: return null
        val consumed = start - end
        if (consumed <= 0) return null

        val voltageMean = voltageStats.mean()
        val estimatedEnergyJ =
            if (voltageMean in 3.0f..5.0f)
                (consumed / 1_000_000f) * 3600f * voltageMean
            else
                null
        return ChargeCounterMetrics(
            startMicroAh = start,
            endMicroAh = end,
            consumedMicroAh = consumed,
            estimatedEnergyJ = estimatedEnergyJ
        )
    }

    data class PowerSamplerStats(
        val currentMa: FloatMetricStats,
        val voltageV: FloatMetricStats,
        val powerMw: FloatMetricStats,
        val temperatureC: FloatMetricStats,
        val isPlugged: Boolean,
        val isCharging: Boolean,
        val chargeCounter: ChargeCounterMetrics?
    )
}

class HardwareMetricsEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 100L
) {
    private val memoryStats = LongStats()
    private val powerStats = FloatStats()
    private val cpuStats = FloatStats()

    val powerSampler = PowerSampler(context)
    private val cpuSampler = CpuSampler()

    private var job: Job? = null
    private var memoryBefore: Long = 0


    init {
        // Measure memory before
        memoryBefore = MemorySampler.sample(context)
    }

    fun start() {
        cpuSampler.sample()

        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    // Memory
                    memoryStats.add(MemorySampler.sample(context))

                    // CPU
                    cpuSampler.sample()?.let { cpuStats.add(it) }

                    // Power
                    powerSampler.sample()?.let { powerStats.add(it) }

                } catch (_: Throwable) {
                    // swallow errors to avoid affecting benchmark
                }

                delay(intervalMs)
            }
        }
    }

    fun stop(): MetricsResult {
        // Only cancel the sampling job. The provided scope is owned by the benchmark caller.
        job?.cancel()

        memoryStats.add(MemorySampler.sample(context))
        cpuSampler.sample()?.let { cpuStats.add(it) }
        powerSampler.sample()?.let { powerStats.add(it) }

        val samplerStats = powerSampler.getStats()

        val chargeCounterMetrics = samplerStats.chargeCounter

        val powerMetrics =
            if (powerStats.mean() > 0f || chargeCounterMetrics != null)
                PowerMetrics(
                    currentMa = FloatMetricStats(samplerStats.currentMa.mean, samplerStats.currentMa.peak),
                    voltageV = FloatMetricStats(samplerStats.voltageV.mean, samplerStats.voltageV.peak),
                    powerMw = FloatMetricStats(samplerStats.powerMw.mean, samplerStats.powerMw.peak),
                    temperatureC = FloatMetricStats(samplerStats.temperatureC.mean, samplerStats.temperatureC.peak),
                    isPlugged = samplerStats.isPlugged,
                    isCharging = samplerStats.isCharging,
                    chargeCounter = chargeCounterMetrics
                )
            else null

        val availableProcessors = Runtime.getRuntime().availableProcessors()
        val cpuMetrics =
            if (cpuStats.hasSamples())
                CpuMetrics(
                    processUsagePercent = FloatMetricStats(cpuStats.mean(), cpuStats.peak),
                    processUsagePercentPerCore = FloatMetricStats(
                        cpuStats.mean() / availableProcessors,
                        cpuStats.peak / availableProcessors
                    ),
                    availableProcessors = availableProcessors
                )
            else null

        return MetricsResult(
            memory = MemoryMetrics(
                beforeMB = memoryBefore,
                meanMB = memoryStats.mean(),
                peakMB = memoryStats.peak
            ),
            power = powerMetrics,
            cpu = cpuMetrics
        )
    }

    fun writeMetricsJson(metrics: MetricsResult, file: File) {
        val json = Json { prettyPrint = true }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(MetricsResult.serializer(), metrics))
    }
}