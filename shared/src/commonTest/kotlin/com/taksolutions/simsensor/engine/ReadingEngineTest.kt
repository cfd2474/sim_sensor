package com.taksolutions.simsensor.engine

import com.taksolutions.simsensor.profile.DefaultProfiles
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.protocol.OverrideMode
import com.taksolutions.simsensor.protocol.ReadingOverride
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingEngineTest {
    /** 5-Gas without noise, so readings are exact. */
    private val profile: SensorProfile = DefaultProfiles.FIVE_GAS.copy(
        channels = DefaultProfiles.FIVE_GAS.channels.map { it.copy(noise = 0.0) },
    )

    private fun engine(p: SensorProfile = profile) = ReadingEngine(p)

    /** Runs the engine at 4 Hz from [from] to [to] seconds and returns the last output. */
    private fun ReadingEngine.run(from: Double, to: Double, overrides: List<ReadingOverride> = emptyList()): EngineOutput {
        var t = from
        var out = step((t * 1000).toLong(), overrides)
        while (t < to) {
            t += 0.25
            out = step((t * 1000).toLong(), overrides)
        }
        return out
    }

    private fun EngineOutput.channel(id: String) = channels.first { it.channel.id == id }

    private fun fixed(channel: String?, value: Double) =
        ReadingOverride(channelId = channel, mode = OverrideMode.FIXED, value = value, startedAtMillis = 0)

    @Test
    fun cleanAirShowsBaselinesAndNoAlarms() {
        val out = engine().run(0.0, 10.0)
        assertEquals(20.9, out.channel("O2").value)
        assertEquals(0.0, out.channel("CO").value)
        assertFalse(out.inAlarm)
        assertEquals(mapOf("O2" to 20.9, "LEL" to 0.0, "CO" to 0.0, "H2S" to 0.0, "HCN" to 0.0), out.readings)
    }

    @Test
    fun sensorReachesNinetyPercentAtT90() {
        val e = engine()
        // CO T90 is 30 s: step to 100 ppm.
        val at30 = e.run(0.0, 30.0, listOf(fixed("CO", 100.0))).channel("CO").value!!
        assertTrue(abs(at30 - 90.0) <= 1.0, "CO after T90 was $at30")
        val at120 = e.run(30.25, 120.0, listOf(fixed("CO", 100.0))).channel("CO").value!!
        assertEquals(100.0, at120)
    }

    @Test
    fun channelOverrideBeatsAllChannelOverride() {
        val out = engine().run(0.0, 300.0, listOf(fixed(null, 5.0), fixed("CO", 50.0)))
        assertEquals(50.0, out.channel("CO").value)
        assertEquals(5.0, out.channel("H2S").value)
    }

    @Test
    fun rampMovesLinearlyFromTheCurrentReading() {
        val e = ReadingEngine(profile.copy(channels = profile.channels.map { it.copy(t90Seconds = 0.0) }))
        val ramp = ReadingOverride("CO", OverrideMode.RAMP, value = 200.0, rampSeconds = 100.0, startedAtMillis = 0)
        assertEquals(100.0, e.run(0.0, 50.0, listOf(ramp)).channel("CO").value)
        assertEquals(200.0, e.run(50.25, 150.0, listOf(ramp)).channel("CO").value)
    }

    @Test
    fun rampResumesFromItsServerStartTime() {
        // A device that first sees the ramp 30 s after it started (reconnect) is already 30 % in.
        val e = ReadingEngine(profile.copy(channels = profile.channels.map { it.copy(t90Seconds = 0.0) }))
        val ramp = ReadingOverride("CO", OverrideMode.RAMP, value = 100.0, rampSeconds = 100.0, startedAtMillis = 0)
        assertEquals(30.0, e.step(30_000, listOf(ramp)).channels.first { it.channel.id == "CO" }.value)
    }

    @Test
    fun offsetAddsToTheNaturalReading() {
        val out = engine().run(0.0, 300.0, listOf(ReadingOverride("O2", OverrideMode.OFFSET, value = -2.0, startedAtMillis = 0)))
        assertEquals(18.9, out.channel("O2").value)
    }

    @Test
    fun faultHidesTheReadingAndAlarms() {
        val out = engine().run(0.0, 1.0, listOf(ReadingOverride("HCN", OverrideMode.FAULT, startedAtMillis = 0)))
        val hcn = out.channel("HCN")
        assertNull(hcn.value)
        assertTrue(hcn.fault)
        assertEquals(setOf(AlarmLevel.FAULT), hcn.active)
        assertFalse("HCN" in out.readings)
        assertTrue("HCN:FAULT" in out.alarmCodes)
    }

    @Test
    fun toxicLowAndHighAlarms() {
        val e = engine()
        assertEquals(setOf(AlarmLevel.LOW), e.run(0.0, 600.0, listOf(fixed("H2S", 12.0))).channel("H2S").active)
        assertEquals(setOf(AlarmLevel.LOW, AlarmLevel.HIGH), e.run(600.25, 1200.0, listOf(fixed("H2S", 16.0))).channel("H2S").active - AlarmLevel.STEL - AlarmLevel.TWA)
    }

    @Test
    fun oxygenAlarmsInBothDirections() {
        assertEquals(setOf(AlarmLevel.LOW), engine().run(0.0, 300.0, listOf(fixed("O2", 19.0))).channel("O2").active)
        assertEquals(setOf(AlarmLevel.HIGH), engine().run(0.0, 300.0, listOf(fixed("O2", 24.0))).channel("O2").active)
        assertEquals(19.0, engine().run(0.0, 300.0, listOf(fixed("O2", 19.0))).channel("O2").peak, "O₂ peak tracks the lowest value")
    }

    @Test
    fun overRangeClampsAndAlarms() {
        val hcn = engine().run(0.0, 600.0, listOf(fixed("HCN", 50.0))).channel("HCN")
        assertEquals(30.0, hcn.value)
        assertTrue(hcn.overRange)
        assertTrue(AlarmLevel.OVER_RANGE in hcn.active && AlarmLevel.HIGH in hcn.active)
    }

    @Test
    fun latchedAlarmStaysUntilAcknowledgedAfterClearing() {
        val e = engine()
        e.run(0.0, 300.0, listOf(fixed("CO", 50.0)))
        val cleared = e.run(300.25, 900.0)
        assertTrue(cleared.channel("CO").active.isEmpty())
        assertTrue(AlarmLevel.LOW in cleared.channel("CO").alarms, "latched alarm should remain")
        e.acknowledge()
        assertTrue(e.run(900.25, 901.0).channel("CO").alarms.isEmpty())
    }

    @Test
    fun acknowledgeDoesNotClearAnActiveAlarm() {
        val e = engine()
        e.run(0.0, 300.0, listOf(fixed("CO", 50.0)))
        e.acknowledge()
        assertTrue(AlarmLevel.LOW in e.run(300.25, 301.0, listOf(fixed("CO", 50.0))).channel("CO").alarms)
    }

    @Test
    fun withoutLatchingAlarmsClearOnTheirOwn() {
        val e = engine(profile.copy(alarmLatching = false))
        e.run(0.0, 300.0, listOf(fixed("CO", 50.0)))
        assertTrue(e.run(300.25, 900.0).channel("CO").alarms.isEmpty())
    }

    @Test
    fun stelAlarmsOnFifteenMinuteAverage() {
        // H2S STEL is 15 ppm. 14 ppm for 15 minutes: no STEL. 20 ppm for 15 minutes: STEL.
        val below = engine().run(0.0, 900.0, listOf(fixed("H2S", 14.0))).channel("H2S")
        assertFalse(AlarmLevel.STEL in below.active)
        val above = engine().run(0.0, 960.0, listOf(fixed("H2S", 20.0))).channel("H2S")
        assertTrue(AlarmLevel.STEL in above.active)
    }

    @Test
    fun profileUpdateAppliesNewSetPoints() {
        val e = engine()
        e.run(0.0, 300.0, listOf(fixed("CO", 30.0)))
        assertTrue(e.run(300.25, 301.0, listOf(fixed("CO", 30.0))).channel("CO").active.isEmpty())
        e.updateProfile(profile.copy(channels = profile.channels.map { if (it.id == "CO") it.copy(lowAlarm = 25.0) else it }))
        assertEquals(setOf(AlarmLevel.LOW), e.run(301.25, 302.0, listOf(fixed("CO", 30.0))).channel("CO").active)
    }

    @Test
    fun readingsAreRoundedToResolution() {
        val out = engine().run(0.0, 600.0, listOf(fixed("H2S", 3.14159), fixed("LEL", 7.6)))
        assertEquals(3.1, out.channel("H2S").value)
        assertEquals(8.0, out.channel("LEL").value)
    }
}
