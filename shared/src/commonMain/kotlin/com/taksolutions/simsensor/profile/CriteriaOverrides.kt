package com.taksolutions.simsensor.profile

import kotlinx.serialization.Serializable

/**
 * Per-exercise changes to one channel of the profile template (docs/DESIGN.md §7.2).
 * A null field means "use the template value".
 *
 * Limitation: an override can change a set point but cannot remove an alarm the template
 * defines. Remove it in the template instead.
 */
@Serializable
data class ChannelOverride(
    val lowAlarm: Double? = null,
    val highAlarm: Double? = null,
    val stelAlarm: Double? = null,
    val twaAlarm: Double? = null,
    val rangeMax: Double? = null,
    val baseline: Double? = null,
    val t90Seconds: Double? = null,
    val lowAlarmLatching: Boolean? = null,
    val highAlarmLatching: Boolean? = null,
    val stelAlarmLatching: Boolean? = null,
    val twaAlarmLatching: Boolean? = null,
) {
    fun applyTo(channel: ChannelConfig): ChannelConfig = channel.copy(
        lowAlarm = lowAlarm ?: channel.lowAlarm,
        highAlarm = highAlarm ?: channel.highAlarm,
        stelAlarm = stelAlarm ?: channel.stelAlarm,
        twaAlarm = twaAlarm ?: channel.twaAlarm,
        rangeMax = rangeMax ?: channel.rangeMax,
        baseline = baseline ?: channel.baseline,
        t90Seconds = t90Seconds ?: channel.t90Seconds,
        lowAlarmLatching = lowAlarmLatching ?: channel.lowAlarmLatching,
        highAlarmLatching = highAlarmLatching ?: channel.highAlarmLatching,
        stelAlarmLatching = stelAlarmLatching ?: channel.stelAlarmLatching,
        twaAlarmLatching = twaAlarmLatching ?: channel.twaAlarmLatching,
    )
}

/** All of an exercise's criteria changes, keyed by channel id. */
@Serializable
data class CriteriaOverrides(
    val channels: Map<String, ChannelOverride> = emptyMap(),
) {
    companion object {
        val NONE = CriteriaOverrides()
    }
}

/**
 * The profile devices in an exercise actually use: template + exercise overrides.
 * Overrides for channels the template doesn't have are ignored here and reported by
 * [ProfileValidator.validateOverrides].
 */
fun SensorProfile.withOverrides(overrides: CriteriaOverrides): SensorProfile = copy(
    channels = channels.map { channel -> overrides.channels[channel.id]?.applyTo(channel) ?: channel },
)
