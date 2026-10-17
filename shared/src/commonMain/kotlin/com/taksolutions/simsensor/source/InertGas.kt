package com.taksolutions.simsensor.source

/**
 * An inert gas in a leak (nitrogen, argon, helium…, operator 2026-10-17). No monitor measures it:
 * it only takes the place of air, so O₂ falls in proportion, O₂ = baseline × (1 − share). Its
 * amount is the share of air it replaces, in %vol. It always displaces, whatever the exercise's
 * "other gases lower O₂" setting: that is all an inert gas does.
 */
object InertGas {
    /** The leak gas id; not a sensor channel. */
    const val ID = "INERT"
    const val LABEL = "Inert gas (N₂, Ar, He…)"

    /** It can replace all of the air (O₂ 0). */
    const val MAX_PERCENT = 100.0

    /** O₂ at clean air when no monitor says otherwise. */
    const val AIR_O2 = 20.9

    /** How much O₂ falls (%vol) where inert gas makes up [percent] of the air. */
    fun o2Drop(o2Baseline: Double, percent: Double): Double = o2Baseline * (percent.coerceIn(0.0, MAX_PERCENT) / 100.0)
}
