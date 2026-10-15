package com.taksolutions.simsensor.ui

import com.taksolutions.simsensor.protocol.Units

/** A distance in the admin's display units (stored and sent in metres). */
fun formatDistance(meters: Float, units: Units): String =
    if (units == Units.IMPERIAL) "${(meters * 3.28084f).toInt()} ft" else "${meters.toInt()} m"

/** A reading as the meter shows it: as many decimals as the channel resolution has. */
fun formatReading(value: Double?, resolution: Double): String {
    if (value == null) return "--"
    val decimals = when {
        resolution >= 1.0 -> 0
        resolution >= 0.1 -> 1
        else -> 2
    }
    return "%.${decimals}f".format(value)
}
