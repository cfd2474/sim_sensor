package com.taksolutions.simsensor.protocol

import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.source.ExerciseClock
import com.taksolutions.simsensor.source.GasSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Version of the device <-> server WebSocket protocol (docs/PROTOCOL.md).
 * Raise it only for breaking changes; additive fields keep the same version.
 */
const val PROTOCOL_VERSION: Int = 1

/** Every message on the device WebSocket. The JSON `type` field selects the subtype. */
@Serializable
sealed interface ProtocolMessage

// --- Device -> server -----------------------------------------------------------------------

/** First message after the socket opens. The device is identified by its bearer token. */
@Serializable
@SerialName("hello")
data class Hello(
    val appVersion: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val model: String? = null,
) : ProtocolMessage

/** One or more readings. More than one when the device catches up after a network drop. */
@Serializable
@SerialName("telemetry")
data class Telemetry(val samples: List<TelemetrySample>) : ProtocolMessage

@Serializable
data class TelemetrySample(
    /** Device time corrected by the clock offset from [Welcome], epoch milliseconds. */
    val ts: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val headingDeg: Float? = null,
    val speedMps: Float? = null,
    /** 0.0–1.0 */
    val battery: Float? = null,
    /** Displayed reading per channel id. */
    val readings: Map<String, Double> = emptyMap(),
    /** Active alarm codes, e.g. "CO:HIGH", "O2:LOW". */
    val alarms: List<String> = emptyList(),
)

/** Something the trainee did or the meter did: alarm raised, alarm acknowledged, bump test... */
@Serializable
@SerialName("event")
data class DeviceEvent(
    val ts: Long,
    val kind: String,
    val channelId: String? = null,
    val detail: String? = null,
) : ProtocolMessage

// --- Server -> device -----------------------------------------------------------------------

/** Reply to [Hello]: identity plus the full current state. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val serverTimeMillis: Long,
    val serverVersion: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceId: String,
    val deviceName: String,
    /** Null when the device isn't in an exercise (standby). */
    val exercise: ExerciseSnapshot? = null,
    val overrides: OverridesUpdate = OverridesUpdate(0, emptyList()),
    /** Display units chosen by the admin (system setting). */
    val units: Units = Units.IMPERIAL,
) : ProtocolMessage

/** System settings that affect the phone's display changed. */
@Serializable
@SerialName("settings")
data class SettingsUpdate(val units: Units) : ProtocolMessage

/**
 * This device's own details changed on the server (a rename in the console). Sent with every
 * device change; apps before v0.13.1 ignore it (unknown types are skipped).
 */
@Serializable
@SerialName("device")
data class DeviceInfo(val deviceName: String) : ProtocolMessage

/** Display units. Values are always metric on the wire and in storage. */
@Serializable
enum class Units { IMPERIAL, METRIC }

/** Full exercise state for the device; replaces what it had. Null exercise = standby. */
@Serializable
@SerialName("exercise")
data class ExerciseUpdate(val exercise: ExerciseSnapshot?) : ProtocolMessage

/** The complete list of this device's active overrides; replaces what it had. */
@Serializable
@SerialName("overrides")
data class OverridesUpdate(
    val version: Int,
    val overrides: List<ReadingOverride>,
) : ProtocolMessage

@Serializable
@SerialName("command")
data class DeviceCommand(
    val kind: CommandKind,
    val text: String? = null,
    val value: Double? = null,
) : ProtocolMessage

/** The last message could not be handled. */
@Serializable
@SerialName("error")
data class ErrorMessage(
    val code: String,
    val message: String,
) : ProtocolMessage

// --- Shared state types ---------------------------------------------------------------------

@Serializable
data class ExerciseSnapshot(
    val exerciseId: String,
    val name: String,
    /** Raised on every change; devices can ignore snapshots older than what they have. */
    val version: Int,
    val state: String,
    /** Effective profile: template + the exercise's criteria. */
    val profile: SensorProfile,
    val o2Displacement: Boolean = false,
    /** Direction the wind comes from, degrees (0 = north). */
    val windDirDeg: Double? = null,
    val windSpeedMps: Double? = null,
    /** Virtual leaks (Phase 2). Older phones ignore them. */
    val sources: List<GasSource> = emptyList(),
    /** Exercise time for source timing; null when the exercise isn't running or paused. */
    val clock: ExerciseClock? = null,
)

@Serializable
enum class OverrideMode {
    /** Show [ReadingOverride.value]. */
    FIXED,

    /** Move from the current reading to [ReadingOverride.value] over [ReadingOverride.rampSeconds]. */
    RAMP,

    /** Add [ReadingOverride.value] to the computed reading. */
    OFFSET,

    /** Show a sensor fault instead of a reading. */
    FAULT,
}

@Serializable
data class ReadingOverride(
    /** Channel id, or null for every channel without its own override. */
    val channelId: String? = null,
    val mode: OverrideMode,
    val value: Double? = null,
    val rampSeconds: Double? = null,
    /** Server time the override was set, so a ramp resumes correctly after a reconnect. */
    val startedAtMillis: Long,
)

@Serializable
enum class CommandKind {
    SILENCE_ALARMS,
    SHOW_MESSAGE,

    /** [DeviceCommand.value] = seconds between telemetry samples. */
    SET_TELEMETRY_INTERVAL,
}
