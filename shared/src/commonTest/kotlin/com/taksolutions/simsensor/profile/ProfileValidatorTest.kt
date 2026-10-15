package com.taksolutions.simsensor.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileValidatorTest {
    private val fiveGas = DefaultProfiles.FIVE_GAS

    /** Errors for the 5-gas monitor with one channel changed. */
    private fun errors(channel: String, change: (ChannelConfig) -> ChannelConfig) =
        ProfileValidator.validate(fiveGas.copy(channels = fiveGas.channels.map { if (it.id == channel) change(it) else it }))

    @Test
    fun builtInTemplatesAreValid() {
        DefaultProfiles.ALL.forEach { assertEquals(emptyList(), ProfileValidator.validate(it), it.name) }
    }

    @Test
    fun builtInMonitorIsGeneric() {
        assertEquals("generic", fiveGas.screen)
    }

    @Test
    fun validChangesPass() {
        assertEquals(emptyList(), errors("HCN") { it.copy(lowAlarm = 2.5, highAlarm = 5.0) })
    }

    @Test
    fun highBelowLowIsRejected() {
        val result = errors("CO") { it.copy(lowAlarm = 100.0, highAlarm = 50.0) }
        assertTrue(result.any { it.channelId == "CO" && it.field == "highAlarm" }, result.toString())
    }

    @Test
    fun setPointOutsideRangeIsRejected() {
        val result = errors("HCN") { it.copy(highAlarm = 31.0) }
        assertTrue(result.any { it.channelId == "HCN" && it.field == "highAlarm" }, result.toString())
    }

    @Test
    fun toxicAlarmAtBaselineIsRejected() {
        val result = errors("CO") { it.copy(lowAlarm = 0.0) }
        assertTrue(result.any { it.field == "lowAlarm" }, result.toString())
    }

    @Test
    fun oxygenLowAlarmMustBeBelowBaseline() {
        val result = errors("O2") { it.copy(lowAlarm = 21.0) }
        assertTrue(result.any { it.channelId == "O2" && it.field == "lowAlarm" }, result.toString())
    }

    @Test
    fun oxygenHighAlarmMustBeAboveBaseline() {
        val result = errors("O2") { it.copy(highAlarm = 20.0) }
        assertTrue(result.any { it.channelId == "O2" && it.field == "highAlarm" }, result.toString())
    }

    @Test
    fun loweringRangeBelowSetPointIsRejected() {
        val result = errors("CO") { it.copy(rangeMax = 100.0) }
        assertTrue(result.any { it.field == "highAlarm" || it.field == "stelAlarm" }, result.toString())
    }

    @Test
    fun duplicateChannelIdsAreRejected() {
        val profile = fiveGas.copy(channels = fiveGas.channels + fiveGas.channels.first())
        assertTrue(ProfileValidator.validate(profile).any { it.field == "id" })
    }

    @Test
    fun overlongModelIsRejected() {
        assertTrue(ProfileValidator.validate(fiveGas.copy(model = "x".repeat(81))).any { it.field == "model" })
    }
}
