package com.taksolutions.simsensor.ui

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
