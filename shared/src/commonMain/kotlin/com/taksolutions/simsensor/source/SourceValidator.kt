package com.taksolutions.simsensor.source

import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.profile.ValidationError

/**
 * Checks a leak before it is saved. Errors use the same shape as profile validation
 * (`channelId` carries the gas for gas-specific problems).
 */
object SourceValidator {
    /** Larger than any training ground; catches unit mistakes (feet typed as km…). */
    const val MAX_SIZE_M = 20_000.0
    const val MAX_ZONE_POINTS = 200

    /** Below this, consumer GPS (3–10 m) makes a leak feel random; the console warns. */
    const val GPS_FRIENDLY_SIZE_M = 25.0

    /**
     * The most a leak (and all leaks together, [SourceModel]) may raise O₂ above its baseline, in
     * %vol (operator, 2026-10-17). Lowering it is limited only by the baseline (a reading of 0).
     */
    const val MAX_O2_ENRICHMENT = 5.0

    fun validate(source: GasSource, profile: SensorProfile): List<ValidationError> = buildList {
        fun error(field: String, message: String, gas: String? = null) = add(ValidationError(gas, field, message))

        if (source.name.isBlank()) error("name", "Name is required")
        if (source.name.length > 80) error("name", "Name must be at most 80 characters")

        if (source.gases.isEmpty()) error("gases", "Add at least one gas")
        source.gases.groupBy { it.gas }.filterValues { it.size > 1 }.keys.forEach { error("gases", "Gas listed twice", it) }
        source.gases.forEach { release ->
            val channel = profile.channel(release.gas)
            if (release.gas == InertGas.ID) {
                // Not a sensor: the share of air it replaces.
                if (!release.peak.isFinite() || release.peak <= 0 || release.peak > InertGas.MAX_PERCENT) {
                    error("peak", "Inert gas must be more than 0 and at most ${InertGas.MAX_PERCENT.toInt()} %vol of the air", release.gas)
                }
            } else if (channel == null) {
                error("gases", "The exercise's meter has no '${release.gas}' channel", release.gas)
            } else if (channel.lowAlarmFalling) {
                // O₂: signed, below the baseline (depletion) or above it (enrichment).
                when {
                    !release.peak.isFinite() || release.peak == 0.0 -> error("peak", "Enter how far below or above the baseline", release.gas)
                    release.peak < -channel.baseline -> error("peak", "${channel.label} can't drop more than ${channel.baseline} (to 0)", release.gas)
                    release.peak > MAX_O2_ENRICHMENT -> error("peak", "${channel.label} can rise at most $MAX_O2_ENRICHMENT above the baseline", release.gas)
                }
            } else if (!release.peak.isFinite() || release.peak <= 0) {
                error("peak", "Peak must be greater than 0", release.gas)
            } else if (release.peak > channel.rangeMax * 100) {
                error("peak", "Peak is far above the ${channel.label} range (${channel.rangeMax} ${channel.unit})", release.gas)
            }
        }

        when (val shape = source.shape) {
            is PointShape -> {
                checkPosition(shape.center, "center")
                checkSize(shape.radiusM, "radiusM", "Radius")
            }
            is PlumeShape -> {
                checkPosition(shape.origin, "origin")
                checkSize(shape.lengthM, "lengthM", "Length")
                checkSize(shape.widthM, "widthM", "Width")
                shape.windFromDeg?.let { if (!it.isFinite() || it < 0 || it >= 360) error("windFromDeg", "Wind direction must be 0–359°") }
            }
            is BeaconShape -> {
                if (shape.beaconId.isBlank()) error("beaconId", "Choose a beacon")
                checkSize(shape.radiusM, "radiusM", "Reach")
                shape.anchor?.let { checkPosition(it, "anchor") }
            }
            is ZoneShape -> {
                if (shape.polygon.size < 3) error("polygon", "A zone needs at least 3 corners")
                if (shape.polygon.size > MAX_ZONE_POINTS) error("polygon", "A zone can have at most $MAX_ZONE_POINTS corners")
                shape.polygon.forEach { checkPosition(it, "polygon") }
                if (!shape.edgeM.isFinite() || shape.edgeM < 0 || shape.edgeM > MAX_SIZE_M) error("edgeM", "Edge must be 0–${MAX_SIZE_M.toInt()} m")
            }
        }

        val t = source.timing
        fun nonNegative(value: Double?, field: String, label: String) {
            if (value != null && (!value.isFinite() || value < 0)) error(field, "$label can't be negative")
        }
        nonNegative(t.startAtS, "startAtS", "Start time")
        nonNegative(t.rampUpS, "rampUpS", "Ramp-up")
        nonNegative(t.growthMPerMin, "growthMPerMin", "Growth")
        nonNegative(t.durationS, "durationS", "Duration")
        nonNegative(t.decayS, "decayS", "Decay")
        if (t.durationS == 0.0) error("durationS", "Duration must be greater than 0, or empty for no end")
    }

    private fun MutableList<ValidationError>.checkPosition(p: LatLon, field: String) {
        if (!p.lat.isFinite() || p.lat !in -90.0..90.0 || !p.lon.isFinite() || p.lon !in -180.0..180.0) {
            add(ValidationError(null, field, "Not a valid position"))
        }
    }

    private fun MutableList<ValidationError>.checkSize(value: Double, field: String, label: String) {
        if (!value.isFinite() || value <= 0 || value > MAX_SIZE_M) add(ValidationError(null, field, "$label must be 0–${MAX_SIZE_M.toInt()} m"))
    }
}
