package com.taksolutions.simsensor.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileValidatorTest {
    private val fiveGas = DefaultProfiles.FIVE_GAS

    private fun errors(overrides: CriteriaOverrides) = ProfileValidator.validateOverrides(fiveGas, overrides)
    private fun one(channel: String, override: ChannelOverride) = CriteriaOverrides(mapOf(channel to override))

    @Test
    fun builtInTemplatesAreValid() {
        DefaultProfiles.ALL.forEach { assertEquals(emptyList(), ProfileValidator.validate(it), it.name) }
    }

    @Test
    fun overridesReplaceOnlyTheGivenFields() {
        val effective = fiveGas.withOverrides(one("H2S", ChannelOverride(lowAlarm = 5.0)))
        val h2s = effective.channel("H2S")!!
        assertEquals(5.0, h2s.lowAlarm)
        assertEquals(15.0, h2s.highAlarm)
        assertEquals(fiveGas.channel("CO"), effective.channel("CO"))
    }

    @Test
    fun latchingCanBeOverriddenPerAlarm() {
        val co = fiveGas.withOverrides(one("CO", ChannelOverride(highAlarmLatching = false, lowAlarmLatching = true))).channel("CO")!!
        assertEquals(false, co.highAlarmLatching)
        assertEquals(true, co.lowAlarmLatching)
        assertEquals(true, co.stelAlarmLatching)
    }

    @Test
    fun validOverridesPass() {
        assertEquals(emptyList(), errors(one("HCN", ChannelOverride(lowAlarm = 2.5, highAlarm = 5.0))))
    }

    @Test
    fun highBelowLowIsRejected() {
        val result = errors(one("CO", ChannelOverride(lowAlarm = 100.0, highAlarm = 50.0)))
        assertTrue(result.any { it.channelId == "CO" && it.field == "highAlarm" }, result.toString())
    }

    @Test
    fun setPointOutsideRangeIsRejected() {
        val result = errors(one("HCN", ChannelOverride(highAlarm = 31.0)))
        assertTrue(result.any { it.channelId == "HCN" && it.field == "highAlarm" }, result.toString())
    }

    @Test
    fun toxicAlarmAtBaselineIsRejected() {
        val result = errors(one("CO", ChannelOverride(lowAlarm = 0.0)))
        assertTrue(result.any { it.field == "lowAlarm" }, result.toString())
    }

    @Test
    fun oxygenLowAlarmMustBeBelowBaseline() {
        val result = errors(one("O2", ChannelOverride(lowAlarm = 21.0)))
        assertTrue(result.any { it.channelId == "O2" && it.field == "lowAlarm" }, result.toString())
    }

    @Test
    fun oxygenHighAlarmMustBeAboveBaseline() {
        val result = errors(one("O2", ChannelOverride(highAlarm = 20.0)))
        assertTrue(result.any { it.channelId == "O2" && it.field == "highAlarm" }, result.toString())
    }

    @Test
    fun unknownChannelIsRejected() {
        val result = errors(one("NH3", ChannelOverride(lowAlarm = 25.0)))
        assertTrue(result.any { it.channelId == "NH3" && it.field == "id" }, result.toString())
    }

    @Test
    fun loweringRangeBelowSetPointIsRejected() {
        val result = errors(one("CO", ChannelOverride(rangeMax = 100.0)))
        assertTrue(result.any { it.field == "highAlarm" || it.field == "stelAlarm" }, result.toString())
    }

    @Test
    fun duplicateChannelIdsAreRejected() {
        val profile = fiveGas.copy(channels = fiveGas.channels + fiveGas.channels.first())
        assertTrue(ProfileValidator.validate(profile).any { it.field == "id" })
    }
}
