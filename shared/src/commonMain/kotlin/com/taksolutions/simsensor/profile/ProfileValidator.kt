package com.taksolutions.simsensor.profile

import kotlinx.serialization.Serializable

/** One problem with a profile or with an exercise's criteria. */
@Serializable
data class ValidationError(
    /** Channel id, or null for profile-level problems. */
    val channelId: String?,
    val field: String,
    val message: String,
)

/**
 * Checks profiles and exercise criteria. Used by the server before saving, and by the console
 * (through the JS build) before submitting, so both apply the same rules.
 */
object ProfileValidator {

    fun validate(profile: SensorProfile): List<ValidationError> = buildList {
        if (profile.name.isBlank()) add(ValidationError(null, "name", "Name is required"))
        if (profile.channels.isEmpty()) add(ValidationError(null, "channels", "At least one channel is required"))
        profile.channels.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
            add(ValidationError(id, "id", "Channel id '$id' is used more than once"))
        }
        profile.channels.forEach { addAll(validateChannel(it)) }
    }

    /** Validates [overrides] against [template]: unknown channels, then the merged result. */
    fun validateOverrides(template: SensorProfile, overrides: CriteriaOverrides): List<ValidationError> {
        val unknown = overrides.channels.keys.filter { template.channel(it) == null }.map { id ->
            ValidationError(id, "id", "The profile has no channel '$id'")
        }
        return unknown + validate(template.withOverrides(overrides))
    }

    private fun validateChannel(c: ChannelConfig): List<ValidationError> = buildList {
        fun error(field: String, message: String) = add(ValidationError(c.id, field, message))

        if (c.id.isBlank()) error("id", "Channel id is required")
        if (c.label.isBlank()) error("label", "Label is required")
        if (c.unit.isBlank()) error("unit", "Unit is required")
        if (c.rangeMin >= c.rangeMax) error("rangeMax", "Range maximum must be above the minimum")
        if (c.resolution <= 0.0) error("resolution", "Resolution must be greater than 0")
        if (c.t90Seconds < 0.0) error("t90Seconds", "Response time can't be negative")
        if (c.noise < 0.0) error("noise", "Noise can't be negative")
        if (c.baseline !in c.rangeMin..c.rangeMax) error("baseline", "Baseline must be within the range")

        val setPoints = listOf(
            "lowAlarm" to c.lowAlarm,
            "highAlarm" to c.highAlarm,
            "stelAlarm" to c.stelAlarm,
            "twaAlarm" to c.twaAlarm,
        )
        setPoints.forEach { (field, value) ->
            if (value != null && value !in c.rangeMin..c.rangeMax) {
                error(field, "Set point must be within the range ${c.rangeMin}–${c.rangeMax}")
            }
        }

        if (c.lowAlarmFalling) {
            // O₂ style: low alarm below clean air, high alarm above it.
            if (c.lowAlarm != null && c.lowAlarm >= c.baseline) {
                error("lowAlarm", "Falling low alarm must be below the baseline (${c.baseline})")
            }
            if (c.highAlarm != null && c.highAlarm <= c.baseline) {
                error("highAlarm", "High alarm must be above the baseline (${c.baseline})")
            }
        } else {
            // Toxic / LEL style: every alarm above clean air, otherwise it alarms in clean air.
            setPoints.forEach { (field, value) ->
                if (value != null && value <= c.baseline) {
                    error(field, "Set point must be above the baseline (${c.baseline})")
                }
            }
            if (c.lowAlarm != null && c.highAlarm != null && c.highAlarm < c.lowAlarm) {
                error("highAlarm", "High alarm must be at or above the low alarm")
            }
        }
    }
}
