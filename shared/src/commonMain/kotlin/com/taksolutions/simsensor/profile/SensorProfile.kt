package com.taksolutions.simsensor.profile

import kotlinx.serialization.Serializable

/**
 * One gas sensor in a profile (docs/DESIGN.md §7).
 *
 * Alarm set points are null when that alarm is not used. The low alarm is shown as **A1** and
 * the high alarm as **A2** (industry names); the stored names stay low/high. A rising channel
 * alarms when the reading goes **above** a set point. When [lowAlarmFalling] is true (O₂), the
 * low alarm fires when the reading drops **below** [lowAlarm] and the high alarm when it rises
 * above [highAlarm].
 *
 * Each alarm latches or not on its own: a latching alarm stays on until acknowledged even
 * after the reading clears; a non-latching one clears itself. Typical meters: A1 clears
 * itself, A2 latches.
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
    val lowAlarmLatching: Boolean = false,
    val highAlarmLatching: Boolean = true,
    val stelAlarmLatching: Boolean = true,
    val twaAlarmLatching: Boolean = true,
    /** Response time: seconds to show 90 % of a change in concentration (T90). */
    val t90Seconds: Double,
    /** Peak random jitter added to readings. */
    val noise: Double = 0.0,
)

/**
 * A **monitor**: the gas meter a phone imitates (e.g. a Dräger X-am 2800 as the agency has it
 * configured), with its sensors and each sensor's alarm levels, latching and response time.
 * Stored and sent as a "profile"; the console calls it a monitor.
 */
@Serializable
data class SensorProfile(
    val name: String,
    val channels: List<ChannelConfig>,
    /** Maker and model it mirrors, e.g. "Dräger X-am 2800" (free text, may be empty). */
    val model: String = "",
    /** Screen the phone draws: one of [SCREENS]. */
    val screen: String = GENERIC_SCREEN,
) {
    companion object {
        const val GENERIC_SCREEN = "generic"
        /** Tiles in two columns that turn yellow (A1) and red (A2), like a Blackline G8. */
        const val G8_SCREEN = "g8"
        /** Grey LCD with a row per gas and A1/A2 alternating with the value, like a Dräger X-am. */
        const val XAM_SCREEN = "xam"
        val SCREENS = setOf(GENERIC_SCREEN, G8_SCREEN, XAM_SCREEN)
    }

    fun channel(id: String): ChannelConfig? = channels.firstOrNull { it.id == id }
}
