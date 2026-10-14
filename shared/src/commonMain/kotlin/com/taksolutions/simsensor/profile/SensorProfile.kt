package com.taksolutions.simsensor.profile

import kotlinx.serialization.Serializable

/**
 * One gas sensor in a profile (docs/DESIGN.md §7).
 *
 * Alarm set points are null when that alarm is not used. A rising channel alarms when the
 * reading goes **above** a set point. When [lowAlarmFalling] is true (O₂), the low alarm
 * fires when the reading drops **below** [lowAlarm] and the high alarm when it rises above
 * [highAlarm].
 */
@Serializable
data class ChannelConfig(
    /** Stable key, e.g. "O2". Overrides and readings refer to channels by this id. */
    val id: String,
    /** Display label, e.g. "O₂". */
    val label: String,
    val unit: String,
    val rangeMin: Double,
    val rangeMax: Double,
    val resolution: Double,
    /** Clean-air reading. */
    val baseline: Double,
    val lowAlarm: Double? = null,
    val highAlarm: Double? = null,
    val stelAlarm: Double? = null,
    val twaAlarm: Double? = null,
    val lowAlarmFalling: Boolean = false,
    /** Time to reach 90 % of a step change. */
    val t90Seconds: Double,
    /** Peak random jitter added to readings. */
    val noise: Double = 0.0,
)

/** A named instrument definition: a list of channels plus alarm behavior. */
@Serializable
data class SensorProfile(
    val name: String,
    val channels: List<ChannelConfig>,
    /** Latched alarms stay on until acknowledged, even after the reading clears. */
    val alarmLatching: Boolean = true,
) {
    fun channel(id: String): ChannelConfig? = channels.firstOrNull { it.id == id }
}
