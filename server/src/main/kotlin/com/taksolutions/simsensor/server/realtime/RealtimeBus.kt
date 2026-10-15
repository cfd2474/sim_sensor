package com.taksolutions.simsensor.server.realtime

import com.taksolutions.simsensor.protocol.DeviceCommand
import com.taksolutions.simsensor.protocol.DeviceEvent
import com.taksolutions.simsensor.protocol.TelemetrySample
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Events fanned out to every server instance (docs/DESIGN.md §3.2). They are notifications:
 * receivers reload state from the database, which stays the source of truth. Telemetry is
 * the exception and carries its sample, because consoles need it live.
 */
@Serializable
sealed interface BusEvent {
    /** Exercise-scoped events go to that exercise's channel; others to the device's channel. */
    val channelKey: String
}

/** Exercise settings, criteria or profile changed. */
@Serializable
@SerialName("exerciseChanged")
data class ExerciseChangedEvent(val exerciseId: String) : BusEvent {
    override val channelKey get() = "ex:$exerciseId"
}

/** A device's assignment, overrides, name or revocation changed. */
@Serializable
@SerialName("deviceChanged")
data class DeviceChangedEvent(
    val deviceId: String,
    val previousExerciseId: String? = null,
    val exerciseId: String? = null,
) : BusEvent {
    override val channelKey get() = "dev:$deviceId"
}

/** The override lists of several devices in one exercise changed (one event, not one per device). */
@Serializable
@SerialName("overridesChanged")
data class OverridesChangedEvent(val exerciseId: String, val deviceIds: List<String>) : BusEvent {
    override val channelKey get() = "ex:$exerciseId"
}

/** The same command for several devices in one exercise. */
@Serializable
@SerialName("commandMany")
data class DevicesCommandEvent(val exerciseId: String, val deviceIds: List<String>, val command: DeviceCommand) : BusEvent {
    override val channelKey get() = "ex:$exerciseId"
}

@Serializable
@SerialName("command")
data class DeviceCommandEvent(val deviceId: String, val command: DeviceCommand) : BusEvent {
    override val channelKey get() = "dev:$deviceId"
}

/** System settings changed; every connected phone gets them. */
@Serializable
@SerialName("settingsChanged")
data object SettingsChangedEvent : BusEvent {
    override val channelKey get() = "settings"
}

@Serializable
@SerialName("telemetry")
data class TelemetryEvent(val exerciseId: String, val deviceId: String, val sample: TelemetrySample) : BusEvent {
    override val channelKey get() = "ex:$exerciseId"
}

@Serializable
@SerialName("status")
data class StatusEvent(val exerciseId: String?, val deviceId: String, val online: Boolean) : BusEvent {
    override val channelKey get() = exerciseId?.let { "ex:$it" } ?: "dev:$deviceId"
}

@Serializable
@SerialName("deviceEvent")
data class DeviceEventEvent(val exerciseId: String, val deviceId: String, val event: DeviceEvent) : BusEvent {
    override val channelKey get() = "ex:$exerciseId"
}

/**
 * Publish/subscribe between server instances. Every instance receives every event, including
 * its own, and acts only through its listener, so local and remote events take the same path.
 */
interface RealtimeBus : AutoCloseable {
    fun publish(event: BusEvent)
    fun subscribe(listener: (BusEvent) -> Unit)
}

object BusCodec {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(event: BusEvent): String = json.encodeToString(BusEvent.serializer(), event)

    fun decode(text: String): BusEvent = json.decodeFromString(BusEvent.serializer(), text)
}

/** Single-instance bus (and tests). */
class InMemoryBus : RealtimeBus {
    private val listeners = CopyOnWriteArrayList<(BusEvent) -> Unit>()

    override fun publish(event: BusEvent) {
        // Round-trip through JSON so in-memory and Redis behave the same.
        val copy = BusCodec.decode(BusCodec.encode(event))
        listeners.forEach { it(copy) }
    }

    override fun subscribe(listener: (BusEvent) -> Unit) {
        listeners += listener
    }

    override fun close() {
        listeners.clear()
    }
}
