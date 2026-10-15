package com.taksolutions.simsensor.source

import com.taksolutions.simsensor.profile.SensorProfile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Concentration from virtual leaks at a position (docs/DESIGN.md §8.1). A **simplified training
 * model**, not a dispersion model. Pure and shared: the phone uses it for readings, the server
 * for the instructor's preview, the tests for both.
 */
object SourceModel {

    /** Wind used for plumes when neither the plume nor the exercise sets one (from the west). */
    const val DEFAULT_WIND_FROM_DEG = 270.0

    /** Upwind of a plume's origin, concentration spreads only this fraction of its width. */
    private const val UPWIND_FRACTION = 0.25

    /** A plume is never narrower than this fraction of its full width (near the origin). */
    private const val MIN_WIDTH_FRACTION = 0.15

    /**
     * Total concentration per gas at [position] from every source, plus O₂ displacement when
     * [o2Displacement] is on.
     *
     * @param elapsedS exercise time (seconds into the run)
     * @param emitting whether sources emit at all (the exercise is running or paused)
     * @param exerciseWindFromDeg exercise wind, used by plumes without their own
     */
    fun contributions(
        sources: List<GasSource>,
        position: LatLon?,
        elapsedS: Double,
        emitting: Boolean,
        exerciseWindFromDeg: Double? = null,
        o2Displacement: Boolean = false,
        profile: SensorProfile? = null,
    ): Map<String, Double> {
        if (position == null || !emitting) return emptyMap()
        val totals = mutableMapOf<String, Double>()
        for (source in sources) {
            if (!source.enabled) continue
            val strength = strengthAt(source, elapsedS)
            if (strength <= 0.0) continue
            val spatial = spatialFactor(source, position, sinceStart(source, elapsedS) ?: 0.0, exerciseWindFromDeg)
            if (spatial <= 0.0) continue
            for (release in source.gases) {
                totals[release.gas] = (totals[release.gas] ?: 0.0) + release.peak * strength * spatial
            }
        }
        if (o2Displacement && profile != null) applyDisplacement(totals, profile)
        return totals
    }

    /** Seconds since the source started emitting, or null if it hasn't started. */
    fun sinceStart(source: GasSource, elapsedS: Double): Double? {
        val start = when (source.timing.activation) {
            Activation.IMMEDIATE -> 0.0
            Activation.AT_TIME -> source.timing.startAtS
            Activation.MANUAL -> source.activatedAtS ?: return null
        }
        return (elapsedS - start).takeIf { it >= 0.0 }
    }

    /** 0..1: ramp-up after starting, full strength, then decay after the duration ends. */
    fun strengthAt(source: GasSource, elapsedS: Double): Double {
        val t = sinceStart(source, elapsedS) ?: return 0.0
        val timing = source.timing
        val ramp = if (timing.rampUpS > 0) (t / timing.rampUpS).coerceAtMost(1.0) else 1.0
        val duration = timing.durationS ?: return ramp
        if (t < duration) return ramp
        if (timing.decayS <= 0) return 0.0
        val atStop = if (timing.rampUpS > 0) (duration / timing.rampUpS).coerceAtMost(1.0) else 1.0
        return (atStop * (1 - (t - duration) / timing.decayS)).coerceAtLeast(0.0)
    }

    /** 0..1 at [position], with the area grown for [emittingForS] seconds. */
    fun spatialFactor(source: GasSource, position: LatLon, emittingForS: Double, exerciseWindFromDeg: Double?): Double {
        val growthM = growthM(source, emittingForS)
        return when (val shape = source.shape) {
            is PointShape -> {
                val radius = shape.radiusM + growthM
                if (radius <= 0) 0.0 else falloff(source.falloff, Geo.toEnu(shape.center, position).length / radius)
            }
            is PlumeShape -> plume(shape, source.falloff, position, growthM, shape.windFromDeg ?: exerciseWindFromDeg ?: DEFAULT_WIND_FROM_DEG)
            is ZoneShape -> zone(shape, source.falloff, position, growthM)
        }
    }

    /** Growth stops when emission stops. */
    private fun growthM(source: GasSource, emittingForS: Double): Double {
        val timing = source.timing
        if (timing.growthMPerMin <= 0) return 0.0
        val growingFor = timing.durationS?.let { minOf(emittingForS, it) } ?: emittingForS
        return timing.growthMPerMin * growingFor / 60.0
    }

    /**
     * Downwind teardrop. Along the plume axis the reading falls from the origin to zero at the
     * (grown) length. Across, it falls to zero at a half-width that widens downwind like the
     * square root of the distance. A little spreads upwind of the origin.
     */
    private fun plume(shape: PlumeShape, falloff: Falloff, position: LatLon, growthM: Double, windFromDeg: Double): Double {
        val scale = if (shape.lengthM > 0) (shape.lengthM + growthM) / shape.lengthM else 1.0
        val length = shape.lengthM * scale
        val width = shape.widthM * scale
        if (length <= 0 || width <= 0) return 0.0

        val p = Geo.toEnu(shape.origin, position)
        val toward = (windFromDeg + 180.0) * PI / 180.0 // the plume drifts downwind
        val axisE = sin(toward)
        val axisN = cos(toward)
        val along = p.east * axisE + p.north * axisN
        val across = kotlin.math.abs(p.east * axisN - p.north * axisE)

        if (along < 0) {
            // Upwind: a small round spread around the origin.
            return falloff(falloff, p.length / (width * UPWIND_FRACTION))
        }
        val x = along / length
        if (x >= 1) return 0.0
        val halfWidth = width / 2 * max(MIN_WIDTH_FRACTION, sqrt(x))
        return falloff(falloff, x) * falloff(Falloff.SMOOTH, across / halfWidth)
    }

    private fun zone(shape: ZoneShape, falloff: Falloff, position: LatLon, growthM: Double): Double {
        if (shape.polygon.size < 3) return 0.0
        val origin = shape.polygon.first()
        val p = Geo.toEnu(origin, position)
        val polygon = shape.polygon.map { Geo.toEnu(origin, it) }
        if (Geo.inside(p, polygon)) return 1.0
        val edge = shape.edgeM + growthM
        if (edge <= 0) return 0.0
        return falloff(falloff, Geo.distanceToEdge(p, polygon) / edge)
    }

    /** Fraction of the peak at [x] = distance / reach (0 at the source, 1 at the edge). */
    fun falloff(kind: Falloff, x: Double): Double {
        if (x >= 1.0) return 0.0
        val d = x.coerceAtLeast(0.0)
        return when (kind) {
            Falloff.LINEAR -> 1 - d
            Falloff.SMOOTH -> (1 - d * d).pow(2)
            Falloff.STEEP -> (1 - d).pow(3)
        }
    }

    /**
     * O₂ displacement: other gases push out air, so O₂ drops by 20.9 % of their volume fraction
     * (10,000 ppm ≈ 1 %vol lowers O₂ by about 0.21 %vol). ppm channels count directly; %LEL is
     * converted as methane (100 %LEL = 5 %vol = 50,000 ppm).
     */
    private fun applyDisplacement(totals: MutableMap<String, Double>, profile: SensorProfile) {
        val o2 = profile.channels.firstOrNull { it.lowAlarmFalling } ?: return
        var ppm = 0.0
        for (channel in profile.channels) {
            if (channel.id == o2.id) continue
            val value = totals[channel.id] ?: continue
            ppm += when (channel.unit.uppercase()) {
                "PPM" -> value
                "%LEL" -> value * METHANE_PPM_PER_PERCENT_LEL
                "%VOL" -> value * 10_000.0
                else -> 0.0
            }
        }
        if (ppm <= 0) return
        totals[o2.id] = (totals[o2.id] ?: 0.0) - o2.baseline * (ppm / 1_000_000.0)
    }

    private const val METHANE_PPM_PER_PERCENT_LEL = 500.0
}
