package com.taksolutions.simsensor.source

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.pow

/**
 * A physical iBeacon an exercise's beacon leaks refer to (docs/DESIGN.md §8.2). The phone
 * recognises it by [uuid] / [major] / [minor] and turns its signal strength into a distance.
 */
@Serializable
data class BeaconRef(
    val id: String,
    val label: String,
    /** Proximity UUID, lower case with dashes. */
    val uuid: String,
    val major: Int,
    val minor: Int,
    /**
     * Signal strength at 1 m on agency phones (dBm, from calibration). Null = use the power the
     * beacon advertises in its own frame.
     */
    val txPowerAt1m: Double? = null,
    /** Path-loss exponent: about 2.0 in the open, 2.5 indoors, 3.0 in cluttered spaces. */
    val pathLossN: Double = DEFAULT_PATH_LOSS,
    /**
     * Gas edge (dBm, recorded where the gas should stop, e.g. the doorway): weaker signal gives
     * no reading, so a signal through a wall doesn't count. Null = no edge (reach only).
     */
    val edgeRssi: Double? = null,
) {
    companion object {
        const val DEFAULT_PATH_LOSS = 2.5
        /** Signal above the edge needed to come back inside (dead band against flicker). */
        const val EDGE_ENTER_DB = 3.0
        /** Above the edge, the reading rises from nothing to full over this many dB. */
        const val EDGE_RAMP_DB = 6.0
        /** Used when neither calibration nor the frame gives a power (a typical 0 dBm beacon). */
        const val FALLBACK_TX_POWER = -59.0
    }
}

/** One iBeacon advertisement. */
data class IBeaconFrame(val uuid: String, val major: Int, val minor: Int, val measuredPower: Int)

/** Reads iBeacon frames from Apple manufacturer-specific data (platform reference §3). */
object IBeaconParser {
    /** Bluetooth SIG company id of Apple, under which iBeacon data is advertised. */
    const val APPLE_COMPANY_ID = 0x004C

    /**
     * [data] = the manufacturer-specific data after the company id:
     * `02 15`, 16-byte UUID, major (2, big-endian), minor (2), measured power (1, signed).
     */
    fun parse(data: ByteArray): IBeaconFrame? {
        if (data.size < 23 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null
        val hex = (2 until 18).joinToString("") { (data[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
        val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        return IBeaconFrame(uuid, major, minor, measuredPower = data[22].toInt())
    }

    /** The ScanFilter mask for one beacon: matches `02 15` + UUID + major + minor, ignores power. */
    fun filterData(beacon: BeaconRef): Pair<ByteArray, ByteArray> {
        val bytes = ByteArray(23)
        bytes[0] = 0x02
        bytes[1] = 0x15
        val hex = beacon.uuid.replace("-", "")
        require(hex.length == 32) { "UUID must have 32 hex digits" }
        for (i in 0 until 16) bytes[2 + i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        bytes[18] = (beacon.major shr 8).toByte()
        bytes[19] = beacon.major.toByte()
        bytes[20] = (beacon.minor shr 8).toByte()
        bytes[21] = beacon.minor.toByte()
        val mask = ByteArray(23) { if (it < 22) 0xFF.toByte() else 0 }
        return bytes to mask
    }
}

/**
 * Turns noisy beacon signal strength into a distance per beacon (DESIGN §8.2): a moving median
 * over the last [medianWindowMillis] against outliers, then an exponential moving average with
 * time constant [timeConstantMillis], then the log-distance path-loss formula
 * `d = 10 ^ ((txPower@1m − rssi) / (10·n))`. Both steps work on time, not on packet count, so
 * smoothing doesn't depend on the beacon's advertising interval. A beacon not heard for
 * [staleAfterMillis] drops out, so its gas falls away through the sensor lag. Pure and
 * deterministic.
 */
class BeaconRanging(
    private val medianWindowMillis: Long = 2_000,
    private val timeConstantMillis: Long = 2_000,
    private val staleAfterMillis: Long = 8_000,
) {
    private class Track {
        val window = ArrayDeque<Pair<Long, Int>>()
        var smoothed: Double? = null
        var lastMillis: Long = 0
        var measuredPower: Int? = null
        var insideEdge = false
    }

    private val tracks = mutableMapOf<String, Track>()

    /** One advertisement of [beaconId] heard at [rssi] dBm. */
    fun observe(beaconId: String, rssi: Int, measuredPower: Int?, nowMillis: Long) {
        val t = tracks.getOrPut(beaconId) { Track() }
        t.window.addLast(nowMillis to rssi)
        while (nowMillis - t.window.first().first > medianWindowMillis) t.window.removeFirst()
        val sorted = t.window.map { it.second }.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        val previous = t.smoothed
        t.smoothed = if (previous == null || nowMillis - t.lastMillis > staleAfterMillis) {
            median // first packet, or back after dropping out: start from what we hear now
        } else {
            val dt = (nowMillis - t.lastMillis).coerceAtLeast(0).toDouble()
            previous + (1.0 - exp(-dt / timeConstantMillis)) * (median - previous)
        }
        t.lastMillis = nowMillis
        if (measuredPower != null) t.measuredPower = measuredPower
    }

    /** Smoothed RSSI of each beacon heard recently. */
    fun rssi(nowMillis: Long): Map<String, Double> =
        tracks.filterValues { nowMillis - it.lastMillis <= staleAfterMillis }.mapNotNull { (id, t) -> t.smoothed?.let { id to it } }.toMap()

    /** Estimated metres to each of [beacons] heard recently. */
    fun distances(beacons: List<BeaconRef>, nowMillis: Long): Map<String, Double> {
        val heard = rssi(nowMillis)
        return beacons.mapNotNull { b ->
            val rssi = heard[b.id] ?: return@mapNotNull null
            val power = b.txPowerAt1m ?: tracks[b.id]?.measuredPower?.toDouble() ?: BeaconRef.FALLBACK_TX_POWER
            b.id to distanceM(rssi, power, b.pathLossN)
        }.toMap()
    }

    /**
     * 0..1 per heard beacon that has a gas edge: 0 outside it, rising to 1 over
     * [BeaconRef.EDGE_RAMP_DB] above it. Coming back inside needs [BeaconRef.EDGE_ENTER_DB] more
     * than the edge, so a signal hovering at the edge doesn't flicker. Beacons without an edge are
     * left out (no limit). Call once per tick: it updates the inside/outside state.
     */
    fun presence(beacons: List<BeaconRef>, nowMillis: Long): Map<String, Double> {
        val heard = rssi(nowMillis)
        return beacons.mapNotNull { b ->
            val edge = b.edgeRssi ?: return@mapNotNull null
            val track = tracks[b.id] ?: return@mapNotNull null
            val rssi = heard[b.id]
            track.insideEdge = when {
                rssi == null -> false
                track.insideEdge -> rssi >= edge
                else -> rssi >= edge + BeaconRef.EDGE_ENTER_DB
            }
            val level = if (track.insideEdge && rssi != null) ((rssi - edge) / BeaconRef.EDGE_RAMP_DB).coerceIn(0.0, 1.0) else 0.0
            b.id to level
        }.toMap()
    }

    /** Drops beacons no longer in the exercise. */
    fun retain(ids: Set<String>) {
        tracks.keys.retainAll(ids)
    }

    companion object {
        fun distanceM(rssi: Double, txPowerAt1m: Double, pathLossN: Double): Double =
            10.0.pow((txPowerAt1m - rssi) / (10.0 * pathLossN))
    }
}
