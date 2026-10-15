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
}
