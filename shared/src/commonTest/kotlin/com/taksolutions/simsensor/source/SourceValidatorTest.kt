package com.taksolutions.simsensor.source

import com.taksolutions.simsensor.profile.DefaultProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceValidatorTest {
    private val profile = DefaultProfiles.FIVE_GAS
    private val here = LatLon(32.7, -117.1)
    private val valid = GasSource("s", "Tank leak", PointShape(here, 50.0), listOf(GasRelease("CO", 300.0)))

    private fun fields(source: GasSource) = SourceValidator.validate(source, profile).map { it.field }

    @Test
    fun aSensibleLeakPasses() {
        assertEquals(emptyList(), SourceValidator.validate(valid, profile))
        val plume = valid.copy(shape = PlumeShape(here, 200.0, 50.0, windFromDeg = 270.0))
        assertEquals(emptyList(), SourceValidator.validate(plume, profile))
        val zone = valid.copy(shape = ZoneShape(listOf(here, LatLon(32.701, -117.1), LatLon(32.7, -117.099)), 10.0))
        assertEquals(emptyList(), SourceValidator.validate(zone, profile))
    }

    @Test
    fun gasesMustExistAndHavePositivePeaks() {
        assertTrue("gases" in fields(valid.copy(gases = emptyList())))
        assertTrue("gases" in fields(valid.copy(gases = listOf(GasRelease("NH3", 50.0)))))
        assertTrue("peak" in fields(valid.copy(gases = listOf(GasRelease("CO", 0.0)))))
        assertTrue("gases" in fields(valid.copy(gases = listOf(GasRelease("CO", 1.0), GasRelease("CO", 2.0)))))
    }

    @Test
    fun oxygenIsBelowOrAboveTheBaseline() {
        fun o2(peak: Double) = SourceValidator.validate(valid.copy(gases = listOf(GasRelease("O2", peak))), profile)
        assertEquals(emptyList(), o2(-4.9), "depletion")
        assertEquals(emptyList(), o2(-20.9), "down to a reading of 0")
        assertEquals(emptyList(), o2(5.0), "enrichment up to +5")
        assertTrue(o2(-21.0).any { it.field == "peak" }, "below 0")
        assertTrue(o2(5.1).any { it.field == "peak" }, "more than +5")
        assertTrue(o2(0.0).any { it.field == "peak" }, "no change")
        assertTrue("peak" in fields(valid.copy(gases = listOf(GasRelease("CO", -5.0)))), "other gases stay positive")
    }

    @Test
    fun inertGasIsAShareOfTheAir() {
        fun inert(percent: Double) = SourceValidator.validate(valid.copy(gases = listOf(GasRelease(InertGas.ID, percent))), profile)
        assertEquals(emptyList(), inert(20.0), "no monitor measures it, yet it is allowed")
        assertEquals(emptyList(), inert(100.0))
        assertTrue(inert(0.0).any { it.field == "peak" })
        assertTrue(inert(101.0).any { it.field == "peak" })
    }

    @Test
    fun shapesNeedSaneSizesAndPositions() {
        assertTrue("radiusM" in fields(valid.copy(shape = PointShape(here, 0.0))))
        assertTrue("radiusM" in fields(valid.copy(shape = PointShape(here, 50_000.0))))
        assertTrue("center" in fields(valid.copy(shape = PointShape(LatLon(95.0, 0.0), 10.0))))
        assertTrue("windFromDeg" in fields(valid.copy(shape = PlumeShape(here, 100.0, 20.0, windFromDeg = 360.0))))
        assertTrue("polygon" in fields(valid.copy(shape = ZoneShape(listOf(here, here), 5.0))))
    }

    @Test
    fun timingCantBeNegative() {
        assertTrue("rampUpS" in fields(valid.copy(timing = SourceTiming(rampUpS = -1.0))))
        assertTrue("durationS" in fields(valid.copy(timing = SourceTiming(durationS = 0.0))))
    }

    @Test
    fun previewIgnoringTimingShowsFullStrength() {
        val later = valid.copy(timing = SourceTiming(Activation.MANUAL, rampUpS = 600.0))
        assertEquals(emptyMap(), SourceModel.contributions(listOf(later), here, 0.0, emitting = true))
        assertEquals(300.0, SourceModel.contributions(listOf(later), here, 0.0, emitting = true, ignoreTiming = true)["CO"])
    }
}
