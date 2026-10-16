package com.taksolutions.simsensor.source

import com.taksolutions.simsensor.profile.DefaultProfiles
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeaconTest {
    private val beacon = BeaconRef(
        id = "b1", label = "Drum 1",
        uuid = "fda50693-a4e2-4fb1-afcf-c6eb07647825", major = 10001, minor = 19641,
        txPowerAt1m = -59.0, pathLossN = 2.0,
    )

    @Test
    fun parsesAnIBeaconFrame() {
        val (data, _) = IBeaconParser.filterData(beacon)
        data[22] = 0xC5.toByte() // measured power −59 dBm
        val frame = IBeaconParser.parse(data)!!
        assertEquals(beacon.uuid, frame.uuid)
        assertEquals(10001, frame.major)
        assertEquals(19641, frame.minor)
        assertEquals(-59, frame.measuredPower)
    }

    @Test
    fun rejectsOtherManufacturerData() {
        assertNull(IBeaconParser.parse(byteArrayOf(0x12, 0x19, 0x00)))
        assertNull(IBeaconParser.parse(ByteArray(23) { 0x01 }))
    }

    @Test
    fun filterMaskIgnoresOnlyTheMeasuredPower() {
        val (_, mask) = IBeaconParser.filterData(beacon)
        assertEquals(23, mask.size)
        assertTrue((0 until 22).all { mask[it] == 0xFF.toByte() })
        assertEquals(0, mask[22].toInt())
    }

    @Test
    fun distanceFollowsThePathLossFormula() {
        assertTrue(abs(BeaconRanging.distanceM(-59.0, -59.0, 2.0) - 1.0) < 1e-9, "at the 1 m power: 1 m")
        assertTrue(abs(BeaconRanging.distanceM(-79.0, -59.0, 2.0) - 10.0) < 1e-9, "20 dB weaker with n = 2: 10 m")
    }

    @Test
    fun medianIgnoresASingleOutlier() {
        val r = BeaconRanging()
        listOf(-65, -65, -95, -65, -65).forEachIndexed { i, rssi -> r.observe("b1", rssi, -59, i * 100L) }
        val d = r.distances(listOf(beacon), 500)["b1"]!!
        assertTrue(d < 2.5, "one −95 dBm packet must not throw the distance out ($d m)")
    }

    @Test
    fun unheardBeaconDropsOut() {
        val r = BeaconRanging(staleAfterMillis = 8_000)
        r.observe("b1", -60, -59, 0)
        assertTrue("b1" in r.distances(listOf(beacon), 7_000))
        assertTrue(r.distances(listOf(beacon), 9_000).isEmpty())
    }

    @Test
    fun usesTheBeaconsOwnPowerWithoutCalibration() {
        val r = BeaconRanging()
        r.observe("b1", -69, -69, 0)
        val uncalibrated = beacon.copy(txPowerAt1m = null)
        assertTrue(abs(r.distances(listOf(uncalibrated), 0)["b1"]!! - 1.0) < 1e-9)
    }

    private val leak = GasSource(
        id = "s1", name = "Drum", shape = BeaconShape("b1", radiusM = 10.0, anchor = LatLon(32.7157, -117.1611)),
        gases = listOf(GasRelease("CO", 300.0)), falloff = Falloff.LINEAR,
    )

    @Test
    fun beaconLeakWorksWithoutGps() {
        val near = SourceModel.contributions(listOf(leak), position = null, elapsedS = 10.0, emitting = true, beaconDistances = mapOf("b1" to 2.5))
        assertEquals(225.0, near["CO"]!!, 1e-9) // linear: 300 × (1 − 2.5 / 10)
        val gone = SourceModel.contributions(listOf(leak), position = null, elapsedS = 10.0, emitting = true, beaconDistances = mapOf("other" to 1.0))
        assertTrue((gone["CO"] ?: 0.0) == 0.0)
    }

    @Test
    fun previewMeasuresFromTheMapPin() {
        val fiveMetresNorth = Geo.fromEnu(LatLon(32.7157, -117.1611), 0.0, 5.0)
        val d = SourceModel.anchorDistances(listOf(leak), fiveMetresNorth)["b1"]!!
        assertTrue(abs(d - 5.0) < 0.01, "$d")
    }

    @Test
    fun validatorChecksBeaconLeaks() {
        assertEquals(emptyList(), SourceValidator.validate(leak, DefaultProfiles.FIVE_GAS))
        val bad = leak.copy(shape = BeaconShape("", radiusM = 0.0))
        val fields = SourceValidator.validate(bad, DefaultProfiles.FIVE_GAS).map { it.field }
        assertTrue("beaconId" in fields && "radiusM" in fields, fields.toString())
    }

    /** Feeds [rssiAt] at a 200 ms advertising interval from [fromMs] until [toMs]. */
    private fun BeaconRanging.feed(fromMs: Long, toMs: Long, rssiAt: (Long) -> Int) {
        var t = fromMs
        while (t < toMs) { observe("b1", rssiAt(t), -59, t); t += 200 }
    }

    @Test
    fun noisySignalGivesASteadyDistance() {
        // Like the 3 m point of the first walk: about ±7 dB of packet-to-packet noise.
        val calibrated = beacon.copy(txPowerAt1m = -59.0, pathLossN = 3.3)
        val random = kotlin.random.Random(42)
        val r = BeaconRanging()
        // What the phone reports once a second, after a 10 s settle.
        val perSecond = (10..69).map { s ->
            r.feed(if (s == 10) 0 else (s - 1) * 1000L, s * 1000L) { -77 + random.nextInt(-7, 8) }
            r.distances(listOf(calibrated), s * 1000L)["b1"]!!
        }
        val spread = perSecond.max() / perSecond.min()
        assertTrue(spread < 1.5, "1 s readings stay within a 1.5× band (was ${perSecond.min()}–${perSecond.max()} m)")
        val truth = BeaconRanging.distanceM(-77.0, -59.0, 3.3)
        assertTrue(abs(perSecond.sorted()[perSecond.size / 2] - truth) / truth < 0.15, "median near the true distance")
    }

    @Test
    fun followsAMoveWithinAFewSeconds() {
        val r = BeaconRanging()
        r.feed(0, 10_000) { -59 }
        r.feed(10_000, 11_000) { -79 }
        val early = r.distances(listOf(beacon), 11_000)["b1"]!!
        r.feed(11_000, 18_000) { -79 }
        val later = r.distances(listOf(beacon), 18_000)["b1"]!!
        val target = BeaconRanging.distanceM(-79.0, -59.0, beacon.pathLossN)
        assertTrue(early < target * 0.8, "smoothed, not instant ($early m after 1 s)")
        assertTrue(abs(later - target) / target < 0.1, "settled 8 s after the move ($later m, target $target m)")
    }

    @Test
    fun aBeaconHeardAgainStartsFromItsNewSignal() {
        val r = BeaconRanging(staleAfterMillis = 8_000)
        r.feed(0, 5_000) { -59 }
        r.feed(20_000, 20_001) { -79 } // one packet after 15 s of silence
        assertTrue(abs(r.distances(listOf(beacon), 20_000)["b1"]!! - BeaconRanging.distanceM(-79.0, -59.0, beacon.pathLossN)) < 1e-9)
    }

    @Test
    fun calibrationRecoversPowerAndExponent() {
        val exact = BeaconCalibration.fit(listOf(CalibrationPoint(1.0, -59.0), CalibrationPoint(10.0, -92.0)), fallbackN = 2.5)
        assertTrue(abs(exact.txPowerAt1m + 59.0) < 1e-9 && abs(exact.pathLossN - 3.3) < 1e-9, "$exact")
        // The first walk (2026-10-15): 1 m −59, 3 m −77, 5 m −88.
        val walk = BeaconCalibration.fit(listOf(CalibrationPoint(1.0, -59.0), CalibrationPoint(3.0, -77.0), CalibrationPoint(5.0, -88.0)), 2.5)
        assertTrue(walk.pathLossN in 3.9..4.3 && walk.txPowerAt1m in -60.0..-57.0, "$walk")
        assertTrue(walk.rmsErrorDb < 2.0)
    }

    @Test
    fun calibrationWithOneDistanceKeepsTheExponent() {
        val atOne = BeaconCalibration.fit(listOf(CalibrationPoint(1.0, -63.0)), fallbackN = 2.5)
        assertEquals(-63.0, atOne.txPowerAt1m)
        assertEquals(2.5, atOne.pathLossN)
        // At 2 m with n = 2: the 1 m power is 6 dB stronger than measured.
        val atTwo = BeaconCalibration.fit(listOf(CalibrationPoint(2.0, -65.0)), fallbackN = 2.0)
        assertTrue(abs(atTwo.txPowerAt1m + 58.979) < 0.01, "$atTwo")
    }

    @Test
    fun calibrationIsClampedToSaneValues() {
        // Signal stronger further away (someone stood in the way at 1 m): no negative exponent.
        val odd = BeaconCalibration.fit(listOf(CalibrationPoint(1.0, -80.0), CalibrationPoint(3.0, -60.0)), 2.5)
        assertEquals(BeaconCalibration.MIN_N, odd.pathLossN)
    }

    @Test
    fun theGasEdgeCutsOffWeakSignalWithADeadBand() {
        val edged = beacon.copy(edgeRssi = -75.0)
        val r = BeaconRanging()
        r.feed(0, 10_000) { -60 }
        assertEquals(1.0, r.presence(listOf(edged), 10_000)["b1"], "15 dB above the edge: full")
        r.feed(10_000, 25_000) { -73 }
        val nearEdge = r.presence(listOf(edged), 25_000)["b1"]!!
        assertTrue(abs(nearEdge - 2.0 / 6.0) < 0.05, "2 dB above the edge: a third ($nearEdge)")
        r.feed(25_000, 40_000) { -80 } // through the wall
        assertEquals(0.0, r.presence(listOf(edged), 40_000)["b1"], "below the edge: nothing")
        r.feed(40_000, 55_000) { -74 } // back to just above the edge: not enough to re-enter
        assertEquals(0.0, r.presence(listOf(edged), 55_000)["b1"], "dead band: stays outside")
        r.feed(55_000, 70_000) { -71 } // 4 dB above: inside again
        assertTrue(r.presence(listOf(edged), 70_000)["b1"]!! > 0.5)
        assertTrue(r.presence(listOf(beacon), 70_000).isEmpty(), "no edge: no limit")
    }

    @Test
    fun aBeaconLeakIsScaledByItsGasEdge() {
        val leak = GasSource(id = "s1", name = "Drum", shape = BeaconShape("b1", radiusM = 8.0), gases = listOf(GasRelease("H2S", 40.0)))
        val full = SourceModel.contributions(listOf(leak), null, 0.0, true, beaconDistances = mapOf("b1" to 0.5))
        val outside = SourceModel.contributions(listOf(leak), null, 0.0, true, beaconDistances = mapOf("b1" to 0.5), beaconPresence = mapOf("b1" to 0.0))
        assertTrue(full["H2S"]!! > 30.0)
        assertEquals(0.0, outside["H2S"] ?: 0.0)
    }
}
