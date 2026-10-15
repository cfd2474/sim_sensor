package com.taksolutions.simsensor.source

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in metres east and north of a local origin. */
data class Enu(val east: Double, val north: Double) {
    val length: Double get() = hypot(east, north)
}

/**
 * Geometry on the scale of an exercise (a few kilometres). Positions are projected onto a
 * plane tangent at an origin; over 5 km the error is well under a metre.
 */
object Geo {
    /** Mean Earth radius (IUGG), metres. */
    const val EARTH_RADIUS_M = 6_371_008.8
    private const val DEG = PI / 180.0

    /** [point] in metres east and north of [origin]. */
    fun toEnu(origin: LatLon, point: LatLon): Enu {
        var dLon = point.lon - origin.lon
        if (dLon > 180) dLon -= 360
        if (dLon < -180) dLon += 360
        val north = (point.lat - origin.lat) * DEG * EARTH_RADIUS_M
        val east = dLon * DEG * EARTH_RADIUS_M * cos(((origin.lat + point.lat) / 2) * DEG)
        return Enu(east, north)
    }

    /** The position [east] and [north] metres from [origin] (inverse of [toEnu]). */
    fun fromEnu(origin: LatLon, east: Double, north: Double): LatLon {
        val lat = origin.lat + north / EARTH_RADIUS_M / DEG
        val lon = origin.lon + east / (EARTH_RADIUS_M * cos(((origin.lat + lat) / 2) * DEG)) / DEG
        return LatLon(lat, lon)
    }

    /** Great-circle distance (haversine), metres. */
    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = (b.lat - a.lat) * DEG
        val dLon = (b.lon - a.lon) * DEG
        val h = sin(dLat / 2).let { it * it } + cos(a.lat * DEG) * cos(b.lat * DEG) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Ray-casting point-in-polygon on the local plane. */
    fun inside(p: Enu, polygon: List<Enu>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.north > p.north) != (b.north > p.north) &&
                p.east < (b.east - a.east) * (p.north - a.north) / (b.north - a.north) + a.east
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Shortest distance from [p] to the polygon's boundary, metres. */
    fun distanceToEdge(p: Enu, polygon: List<Enu>): Double {
        var best = Double.MAX_VALUE
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            best = minOf(best, distanceToSegment(p, a, b))
        }
        return best
    }

    private fun distanceToSegment(p: Enu, a: Enu, b: Enu): Double {
        val dx = b.east - a.east
        val dy = b.north - a.north
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0.0) return hypot(p.east - a.east, p.north - a.north)
        val t = (((p.east - a.east) * dx + (p.north - a.north) * dy) / lengthSq).coerceIn(0.0, 1.0)
        return hypot(p.east - (a.east + t * dx), p.north - (a.north + t * dy))
    }
}
