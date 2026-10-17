package com.taksolutions.simsensor.source

import com.taksolutions.simsensor.profile.DefaultProfiles
import com.taksolutions.simsensor.protocol.ExerciseSnapshot
import com.taksolutions.simsensor.protocol.ExerciseUpdate
import com.taksolutions.simsensor.protocol.ProtocolCodec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceModelTest {
    private val center = LatLon(32.7157, -117.1611)

    /** The position [east], [north] metres from [center]. */
    private fun at(east: Double, north: Double) = Geo.fromEnu(center, east, north)

    private fun source(shape: SourceShape, falloff: Falloff = Falloff.LINEAR, timing: SourceTiming = SourceTiming(), vararg gases: GasRelease) =
        GasSource("s", "Leak", shape, if (gases.isEmpty()) listOf(GasRelease("CO", 200.0)) else gases.toList(), falloff, timing)

    private fun co(sources: List<GasSource>, position: LatLon, elapsed: Double = 60.0, wind: Double? = null) =
        SourceModel.contributions(sources, position, elapsed, emitting = true, exerciseWindFromDeg = wind)["CO"] ?: 0.0

    private fun near(expected: Double, actual: Double, tolerance: Double = 0.5) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, got $actual")

    // --- Geometry -------------------------------------------------------------------------

    @Test
    fun localProjectionMatchesGreatCircleDistance() {
        for ((e, n) in listOf(10.0 to 0.0, 0.0 to 250.0, -700.0 to 1200.0, 3000.0 to -4000.0)) {
            val p = at(e, n)
            val planar = Geo.toEnu(center, p).length
            near(Geo.distanceM(center, p), planar, tolerance = 0.5)
            near(kotlin.math.hypot(e, n), planar, tolerance = 0.5)
        }
    }

    @Test
    fun pointInPolygonAndEdgeDistance() {
        val square = listOf(Enu(0.0, 0.0), Enu(10.0, 0.0), Enu(10.0, 10.0), Enu(0.0, 10.0))
        assertTrue(Geo.inside(Enu(5.0, 5.0), square))
        assertTrue(!Geo.inside(Enu(15.0, 5.0), square))
        near(5.0, Geo.distanceToEdge(Enu(15.0, 5.0), square), 1e-9)
        near(kotlin.math.hypot(3.0, 4.0), Geo.distanceToEdge(Enu(13.0, 14.0), square), 1e-9)
    }

    // --- Point ----------------------------------------------------------------------------

    @Test
    fun pointFallsLinearlyToZeroAtItsRadius() {
        val s = listOf(source(PointShape(center, 100.0)))
        near(200.0, co(s, center))
        near(100.0, co(s, at(50.0, 0.0)))
        near(50.0, co(s, at(0.0, -75.0)))
        assertEquals(0.0, co(s, at(100.0, 0.0)))
        assertEquals(0.0, co(s, at(300.0, 0.0)))
    }

    @Test
    fun falloffShapesRankAsDescribed() {
        // Half way out: smooth stays high, linear is half, steep is low.
        val smooth = SourceModel.falloff(Falloff.SMOOTH, 0.5)
        val linear = SourceModel.falloff(Falloff.LINEAR, 0.5)
        val steep = SourceModel.falloff(Falloff.STEEP, 0.5)
        assertTrue(smooth > linear && linear > steep, "$smooth $linear $steep")
        Falloff.entries.forEach {
            assertEquals(1.0, SourceModel.falloff(it, 0.0))
            assertEquals(0.0, SourceModel.falloff(it, 1.0))
        }
    }

    // --- Plume ----------------------------------------------------------------------------

    @Test
    fun plumeReachesDownwindNotUpwind() {
        // Wind FROM the west (270): the plume drifts east.
        val s = listOf(source(PlumeShape(center, lengthM = 200.0, widthM = 60.0), timing = SourceTiming()))
        val east100 = co(s, at(100.0, 0.0), wind = 270.0)
        val west100 = co(s, at(-100.0, 0.0), wind = 270.0)
        assertTrue(east100 > 30, "downwind should read: $east100")
        assertEquals(0.0, west100, "upwind 100 m should read nothing")
        assertEquals(0.0, co(s, at(250.0, 0.0), wind = 270.0), "beyond the plume length")
    }

    @Test
    fun plumeFallsOffAcrossTheAxis() {
        val s = listOf(source(PlumeShape(center, 200.0, 60.0)))
        val onAxis = co(s, at(100.0, 0.0), wind = 270.0)
        val offAxis = co(s, at(100.0, 15.0), wind = 270.0)
        val outside = co(s, at(100.0, 40.0), wind = 270.0)
        assertTrue(onAxis > offAxis && offAxis > 0, "$onAxis > $offAxis > 0")
        assertEquals(0.0, outside)
    }

    @Test
    fun plumeFollowsTheWindAndItsOwnOverride() {
        val shape = PlumeShape(center, 200.0, 60.0)
        // Wind from the north (0): drifts south.
        assertTrue(co(listOf(source(shape)), at(0.0, -100.0), wind = 0.0) > 30)
        assertEquals(0.0, co(listOf(source(shape)), at(0.0, 100.0), wind = 0.0))
        // The plume's own wind (from the south) beats the exercise wind.
        val own = source(shape.copy(windFromDeg = 180.0))
        assertTrue(co(listOf(own), at(0.0, 100.0), wind = 0.0) > 30)
    }

    // --- Zone -----------------------------------------------------------------------------

    @Test
    fun zoneIsUniformInsideAndFadesOverItsEdge() {
        val polygon = listOf(at(0.0, 0.0), at(50.0, 0.0), at(50.0, 50.0), at(0.0, 50.0))
        val s = listOf(source(ZoneShape(polygon, edgeM = 20.0)))
        near(200.0, co(s, at(25.0, 25.0)))
        near(200.0, co(s, at(2.0, 48.0)))
        near(100.0, co(s, at(60.0, 25.0)))
        assertEquals(0.0, co(s, at(75.0, 25.0)))
    }

    // --- Timing ---------------------------------------------------------------------------

    @Test
    fun timingRampDurationAndDecay() {
        val timing = SourceTiming(Activation.AT_TIME, startAtS = 100.0, rampUpS = 20.0, durationS = 60.0, decayS = 40.0)
        val s = source(PointShape(center, 100.0), timing = timing)
        assertEquals(0.0, SourceModel.strengthAt(s, 99.0))
        near(0.5, SourceModel.strengthAt(s, 110.0), 1e-9)
        near(1.0, SourceModel.strengthAt(s, 140.0), 1e-9)
        near(0.5, SourceModel.strengthAt(s, 180.0), 1e-9) // 20 s into a 40 s decay
        assertEquals(0.0, SourceModel.strengthAt(s, 300.0))
    }

    @Test
    fun manualSourcesWaitForActivation() {
        val waiting = source(PointShape(center, 100.0), timing = SourceTiming(Activation.MANUAL))
        assertEquals(0.0, co(listOf(waiting), center, elapsed = 500.0))
        val activated = waiting.copy(activatedAtS = 450.0)
        near(200.0, co(listOf(activated), center, elapsed = 500.0))
        assertEquals(0.0, co(listOf(activated), center, elapsed = 400.0))
    }

    @Test
    fun growthWidensTheAreaUntilEmissionStops() {
        val timing = SourceTiming(growthMPerMin = 10.0, durationS = 300.0)
        val s = listOf(source(PointShape(center, 50.0), timing = timing))
        assertEquals(0.0, co(s, at(60.0, 0.0), elapsed = 0.0), "60 m is outside the initial 50 m")
        assertTrue(co(s, at(60.0, 0.0), elapsed = 120.0) > 0, "after 2 min the radius is 70 m")
    }

    @Test
    fun disabledSourcesAndNonEmittingExercisesReadNothing() {
        val s = source(PointShape(center, 100.0))
        assertEquals(0.0, co(listOf(s.copy(enabled = false)), center))
        assertEquals(emptyMap(), SourceModel.contributions(listOf(s), center, 60.0, emitting = false))
        assertEquals(emptyMap(), SourceModel.contributions(listOf(s), null, 60.0, emitting = true))
    }

    // --- Several gases, several sources, displacement -------------------------------------

    @Test
    fun gasesAndSourcesAddUp() {
        val fire = source(PointShape(center, 100.0), Falloff.LINEAR, SourceTiming(), GasRelease("CO", 200.0), GasRelease("HCN", 20.0))
        val second = source(PointShape(at(10.0, 0.0), 100.0), Falloff.LINEAR, SourceTiming(), GasRelease("CO", 100.0))
        val result = SourceModel.contributions(listOf(fire, second), center, 60.0, emitting = true)
        near(200.0 + 90.0, result["CO"]!!)
        near(20.0, result["HCN"]!!)
    }

    @Test
    fun oxygenIsDisplacedByOtherGases() {
        // 10,000 ppm CO at the centre lowers O₂ by 20.9 × 1 % = 0.209 %vol.
        val s = source(PointShape(center, 100.0), Falloff.LINEAR, SourceTiming(), GasRelease("CO", 10_000.0))
        val result = SourceModel.contributions(listOf(s), center, 60.0, emitting = true, o2Displacement = true, profile = DefaultProfiles.FIVE_GAS)
        near(-0.209, result["O2"]!!, 1e-9)
        val off = SourceModel.contributions(listOf(s), center, 60.0, emitting = true, o2Displacement = false, profile = DefaultProfiles.FIVE_GAS)
        assertTrue("O2" !in off)
    }

    @Test
    fun oxygenLeaksLowerOrRaiseItAndRaisingIsCappedAtFive() {
        val low = source(PointShape(center, 100.0), Falloff.LINEAR, SourceTiming(), GasRelease("O2", -4.9))
        near(-4.9, SourceModel.contributions(listOf(low), center, 60.0, emitting = true, profile = DefaultProfiles.FIVE_GAS)["O2"]!!, 1e-9)
        // Two overlapping enrichment leaks of +4 each: together still only +5.
        val rich = source(PointShape(center, 100.0), Falloff.LINEAR, SourceTiming(), GasRelease("O2", 4.0))
        near(5.0, SourceModel.contributions(listOf(rich, rich), center, 60.0, emitting = true, profile = DefaultProfiles.FIVE_GAS)["O2"]!!, 1e-9)
        near(5.0, SourceModel.contributions(listOf(rich, rich), center, 60.0, emitting = true)["O2"]!!, 1e-9)
    }

    @Test
    fun inertGasDisplacesOxygenInProportion() {
        val purge = source(PointShape(center, 100.0), Falloff.LINEAR, SourceTiming(), GasRelease(InertGas.ID, 20.0))
        // 20 % of the air replaced: O₂ 20.9 × 0.8 = 16.72, a drop of 4.18. Always, displacement on or off.
        for (displacement in listOf(false, true)) {
            val result = SourceModel.contributions(listOf(purge), center, 60.0, emitting = true, o2Displacement = displacement, profile = DefaultProfiles.FIVE_GAS)
            near(-4.18, result["O2"]!!, 1e-9)
            assertTrue(InertGas.ID !in result, "not a reading")
        }
        // Halfway to the edge of a linear leak: 10 % of the air.
        near(-2.09, SourceModel.contributions(listOf(purge), at(50.0, 0.0), 60.0, emitting = true, profile = DefaultProfiles.FIVE_GAS)["O2"]!!, 1e-6)
        // Overlapping leaks add, but never replace more than all of the air.
        val big = source(PointShape(center, 100.0), Falloff.LINEAR, SourceTiming(), GasRelease(InertGas.ID, 80.0))
        near(-20.9, SourceModel.contributions(listOf(big, big), center, 60.0, emitting = true, profile = DefaultProfiles.FIVE_GAS)["O2"]!!, 1e-9)
        // With a measured O₂ leak too, the drops add.
        val low = source(PointShape(center, 100.0), Falloff.LINEAR, SourceTiming(), GasRelease("O2", -1.0))
        near(-5.18, SourceModel.contributions(listOf(purge, low), center, 60.0, emitting = true, profile = DefaultProfiles.FIVE_GAS)["O2"]!!, 1e-9)
    }

    @Test
    fun oxygenReadsZeroToTwentyFiveAndOverRangeAbove() {
        val profile = DefaultProfiles.FIVE_GAS.copy(channels = DefaultProfiles.FIVE_GAS.channels.map { it.copy(noise = 0.0) })
        assertEquals(25.0, profile.channel("O2")!!.rangeMax)
        fun settle(rise: Double): com.taksolutions.simsensor.engine.ChannelReading {
            val engine = com.taksolutions.simsensor.engine.ReadingEngine(profile)
            var out = engine.step(0)
            for (i in 1..400) out = engine.step(i * 250L, contributions = mapOf("O2" to rise))
            return out.channels.first { it.channel.id == "O2" }
        }
        val rich = settle(5.0) // 20.9 + 5 = 25.9
        assertTrue(rich.overRange, "above 25 is over range")
        assertEquals(25.0, rich.value)
        assertTrue(com.taksolutions.simsensor.engine.AlarmLevel.OVER_RANGE in rich.active)
        val empty = settle(-20.9)
        assertEquals(0.0, empty.value)
        assertTrue(com.taksolutions.simsensor.engine.AlarmLevel.LOW in empty.active)
    }

    // --- With the meter ------------------------------------------------------------------

    @Test
    fun walkingIntoALeakRaisesTheMeterReadingAndAlarms() {
        val engine = com.taksolutions.simsensor.engine.ReadingEngine(
            DefaultProfiles.FIVE_GAS.copy(channels = DefaultProfiles.FIVE_GAS.channels.map { it.copy(noise = 0.0) }),
        )
        val leak = listOf(source(PointShape(center, 100.0), Falloff.LINEAR, SourceTiming(), GasRelease("CO", 300.0)))
        var output = engine.step(0)
        // Walk from 150 m east to the centre at 1.5 m/s, sampling 4 times a second.
        var t = 0.0
        while (t <= 100.0) {
            val position = at(150.0 - 1.5 * t, 0.0)
            output = engine.step((t * 1000).toLong(), contributions = SourceModel.contributions(leak, position, t, emitting = true))
            t += 0.25
        }
        val co = output.channels.first { it.channel.id == "CO" }
        // At the centre the true value is 300; after ~33 s inside the area the lagged reading is near it.
        assertTrue(co.value!! > 200, "CO should be high at the leak: ${co.value}")
        assertTrue(com.taksolutions.simsensor.engine.AlarmLevel.HIGH in co.active)
    }

    // --- Clock and protocol ---------------------------------------------------------------

    @Test
    fun clockAdvancesOnlyWhileRunning() {
        val running = ExerciseClock(elapsedS = 100.0, atServerMillis = 1_000_000, running = true)
        near(130.0, running.elapsedAt(1_030_000), 1e-9)
        val paused = running.copy(running = false)
        assertEquals(100.0, paused.elapsedAt(1_030_000))
    }

    @Test
    fun snapshotWithSourcesRoundTrips() {
        val snapshot = ExerciseSnapshot(
            exerciseId = "e", name = "Drill", version = 1, state = "running", profile = DefaultProfiles.FIVE_GAS,
            windDirDeg = 270.0,
            sources = listOf(
                source(PointShape(center, 50.0)),
                source(PlumeShape(center, 200.0, 60.0, windFromDeg = 90.0)),
                source(ZoneShape(listOf(at(0.0, 0.0), at(1.0, 0.0), at(0.0, 1.0)), 5.0), timing = SourceTiming(Activation.MANUAL)),
            ),
            clock = ExerciseClock(12.5, 1, true),
        )
        val update = ExerciseUpdate(snapshot)
        assertEquals(update, ProtocolCodec.decodeOrNull(ProtocolCodec.encode(update)))
    }
}
