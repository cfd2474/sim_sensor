package com.taksolutions.simsensor.source

import kotlinx.serialization.Serializable
import kotlin.math.log10
import kotlin.math.sqrt

/** Signal strength measured with a phone held [distanceM] metres from the beacon. */
@Serializable
data class CalibrationPoint(val distanceM: Double, val rssi: Double)

/** Calibration for [BeaconRanging]: power at 1 m, path-loss exponent, and how well it fits. */
data class CalibrationFit(val txPowerAt1m: Double, val pathLossN: Double, val rmsErrorDb: Double)

/**
 * Fits the log-distance model `rssi = P − 10·n·log10(d)` to measured points (DESIGN §8.2):
 * least squares when the points cover two or more distances, otherwise only P with the current
 * [fallbackN]. Results are clamped to physically sensible ranges, so one bad reading can't
 * produce a calibration that makes every phone read zero.
 */
object BeaconCalibration {
    const val MIN_N = 1.5
    const val MAX_N = 4.5 // the beacon editor accepts 1.5–4.5; a fit must stay editable
    const val MIN_POWER = -100.0
    const val MAX_POWER = -20.0

    fun fit(points: List<CalibrationPoint>, fallbackN: Double): CalibrationFit {
        require(points.isNotEmpty()) { "At least one point" }
        require(points.all { it.distanceM > 0 }) { "Distances must be positive" }
        // rssi = P + n·x with x = −10·log10(d)
        val xs = points.map { -10.0 * log10(it.distanceM) }
        val ys = points.map { it.rssi }
        val meanX = xs.average()
        val meanY = ys.average()
        val sxx = xs.sumOf { (it - meanX) * (it - meanX) }
        val n = if (sxx < 1e-6) {
            fallbackN // one distance only: keep the slope, fit the level
        } else {
            (xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) } / sxx).coerceIn(MIN_N, MAX_N)
        }
        val power = (meanY - n * meanX).coerceIn(MIN_POWER, MAX_POWER)
        val rms = sqrt(xs.indices.sumOf { val e = ys[it] - (power + n * xs[it]); e * e } / xs.size)
        return CalibrationFit(power, n, rms)
    }
}
