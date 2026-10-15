package com.taksolutions.simsensor.location

import com.taksolutions.simsensor.source.LatLon

/** How trustworthy the position used for leak readings is. */
enum class GpsStatus {
    /** Recent fix within the accuracy limit. */
    GOOD,

    /** Fixes arrive but are too inaccurate; the last good position is used. */
    WEAK,

    /** No fix recently; the last good position is used. */
    STALE,

    /** No good fix yet: leaks can't be evaluated. */
    NONE,
}

/**
 * Picks the position for leak readings (docs/DESIGN.md §8.1). Poor or old fixes don't move
 * it, so readings don't jump when GPS degrades (e.g. walking indoors): the last good position
 * is held and the status says so. Pure, so it can be unit tested.
 */
class GpsQuality(
    private val maxAccuracyM: Float = 30f,
    private val staleAfterMillis: Long = 15_000,
) {
    private var lastGood: GeoFix? = null

    data class Result(val position: LatLon?, val status: GpsStatus)

    /** Feeds the latest fix (may be the same one again) and returns what to use at [nowMillis]. */
    fun update(fix: GeoFix?, nowMillis: Long): Result {
        if (fix != null && (fix.accuracyM ?: Float.MAX_VALUE) <= maxAccuracyM) {
            if (lastGood == null || fix.timeMillis >= lastGood!!.timeMillis) lastGood = fix
        }
        val good = lastGood ?: return Result(null, GpsStatus.NONE)
        val position = LatLon(good.lat, good.lon)
        val status = when {
            nowMillis - (fix?.timeMillis ?: good.timeMillis) > staleAfterMillis -> GpsStatus.STALE
            fix != null && fix !== good && fix.timeMillis > good.timeMillis -> GpsStatus.WEAK
            nowMillis - good.timeMillis > staleAfterMillis -> GpsStatus.WEAK
            else -> GpsStatus.GOOD
        }
        return Result(position, status)
    }
}
