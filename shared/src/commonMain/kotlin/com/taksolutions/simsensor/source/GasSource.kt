package com.taksolutions.simsensor.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A WGS-84 position. */
@Serializable
data class LatLon(val lat: Double, val lon: Double)

/** One gas a source gives off, with its concentration at the source (channel units, e.g. ppm). */
@Serializable
data class GasRelease(val gas: String, val peak: Double)

/** How concentration falls from the source to the edge of its area (docs/DESIGN.md §8.1). */
@Serializable
enum class Falloff {
    /** Straight line from peak to zero. */
    LINEAR,

    /** Gentle near the source, faster towards the edge. */
    SMOOTH,

    /** Concentrated near the source, low most of the way out. */
    STEEP,
}

/** Where and how far a source reaches. All sizes in metres. */
@Serializable
sealed interface SourceShape

/** Reading falls from the centre to zero at [radiusM]. */
@Serializable
@SerialName("point")
data class PointShape(val center: LatLon, val radiusM: Double) : SourceShape

/**
 * A teardrop stretched downwind: [lengthM] downwind, [widthM] across at the far end.
 * [windFromDeg] (direction the wind comes from, 0 = north) overrides the exercise wind.
 */
@Serializable
@SerialName("plume")
data class PlumeShape(
    val origin: LatLon,
    val lengthM: Double,
    val widthM: Double,
    val windFromDeg: Double? = null,
) : SourceShape

/** Uniform inside the polygon, falling to zero over [edgeM] outside it. */
@Serializable
@SerialName("zone")
data class ZoneShape(val polygon: List<LatLon>, val edgeM: Double) : SourceShape

@Serializable
enum class Activation {
    /** Emits from the start of the exercise run. */
    IMMEDIATE,

    /** Emits from [SourceTiming.startAtS] into the run. */
    AT_TIME,

    /** Emits once an instructor activates it ([GasSource.activatedAtS]). */
    MANUAL,
}

/** When a source emits. Times are exercise time in seconds (paused time doesn't count). */
@Serializable
data class SourceTiming(
    val activation: Activation = Activation.IMMEDIATE,
    val startAtS: Double = 0.0,
    /** Time to reach full strength after starting. */
    val rampUpS: Double = 0.0,
    /** The area grows by this many metres per minute while emitting (a spreading leak). */
    val growthMPerMin: Double = 0.0,
    /** Stops emitting this long after starting; null = until the run ends. */
    val durationS: Double? = null,
    /** Time to fade to zero after stopping. */
    val decayS: Double = 0.0,
)

/** A virtual leak placed by an instructor (docs/DESIGN.md §8.1). */
@Serializable
data class GasSource(
    val id: String,
    val name: String,
    val shape: SourceShape,
    val gases: List<GasRelease>,
    val falloff: Falloff = Falloff.SMOOTH,
    val timing: SourceTiming = SourceTiming(),
    val enabled: Boolean = true,
    /** Exercise time when an instructor activated a MANUAL source; null = not yet. */
    val activatedAtS: Double? = null,
)

/**
 * Exercise time as the server last knew it. Phones advance it with their (server-corrected)
 * clock while [running]; while paused it stands still.
 */
@Serializable
data class ExerciseClock(
    val elapsedS: Double,
    val atServerMillis: Long,
    val running: Boolean,
) {
    fun elapsedAt(serverNowMillis: Long): Double =
        if (running) elapsedS + (serverNowMillis - atServerMillis).coerceAtLeast(0) / 1000.0 else elapsedS
}
