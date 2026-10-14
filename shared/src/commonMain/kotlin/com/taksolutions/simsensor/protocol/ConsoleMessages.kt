package com.taksolutions.simsensor.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// Instructor console <-> server WebSocket (`/ws/console`, session cookie). See docs/PROTOCOL.md.

@Serializable
sealed interface ConsoleClientMessage

/** Start receiving live updates for one exercise (replaces any previous watch). */
@Serializable
@SerialName("watch")
data class WatchExercise(val exerciseId: String) : ConsoleClientMessage

@Serializable
@SerialName("unwatch")
data object UnwatchExercise : ConsoleClientMessage

@Serializable
sealed interface ConsoleServerMessage

/** Sent after `watch`: every device in the exercise with its latest known state. */
@Serializable
@SerialName("devices")
data class ExerciseDevices(val exerciseId: String, val devices: List<DeviceLiveState>) : ConsoleServerMessage

@Serializable
data class DeviceLiveState(
    val deviceId: String,
    val name: String,
    val online: Boolean,
    val appVersion: String? = null,
    val lastSample: TelemetrySample? = null,
)

@Serializable
@SerialName("telemetry")
data class DeviceTelemetry(val deviceId: String, val sample: TelemetrySample) : ConsoleServerMessage

@Serializable
@SerialName("status")
data class DeviceStatus(val deviceId: String, val online: Boolean) : ConsoleServerMessage

@Serializable
@SerialName("event")
data class DeviceEventNotice(val deviceId: String, val event: DeviceEvent) : ConsoleServerMessage

/** The exercise or its device list changed; reload it (and the device list) from the REST API. */
@Serializable
@SerialName("exerciseChanged")
data class ExerciseChanged(val exerciseId: String) : ConsoleServerMessage

@Serializable
@SerialName("error")
data class ConsoleError(val code: String, val message: String) : ConsoleServerMessage

/** Encodes and decodes console messages. */
object ConsoleCodec {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: ConsoleServerMessage): String = json.encodeToString(ConsoleServerMessage.serializer(), message)

    fun encodeClient(message: ConsoleClientMessage): String = json.encodeToString(ConsoleClientMessage.serializer(), message)

    fun decodeClientOrNull(text: String): ConsoleClientMessage? =
        try {
            json.decodeFromString(ConsoleClientMessage.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    fun decodeServerOrNull(text: String): ConsoleServerMessage? =
        try {
            json.decodeFromString(ConsoleServerMessage.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}
