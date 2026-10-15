package com.taksolutions.simsensor.server.realtime

import com.taksolutions.simsensor.protocol.DeviceEventNotice
import com.taksolutions.simsensor.protocol.DeviceStatus
import com.taksolutions.simsensor.protocol.DeviceTelemetry
import com.taksolutions.simsensor.protocol.ExerciseChanged
import com.taksolutions.simsensor.protocol.ExerciseUpdate
import com.taksolutions.simsensor.server.devices.DeviceRepository
import com.taksolutions.simsensor.server.devices.DeviceStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Delivers bus events to the devices and consoles connected to **this** instance.
 * Events are handled one at a time, in arrival order.
 */
class RealtimeDispatcher(
    private val deviceConnections: DeviceConnections,
    private val consoleConnections: ConsoleConnections,
    private val devices: DeviceRepository,
    private val state: DeviceStateService,
) {
    private val log = LoggerFactory.getLogger(RealtimeDispatcher::class.java)
    private val queue = Channel<BusEvent>(Channel.UNLIMITED)

    fun start(bus: RealtimeBus, scope: CoroutineScope) {
        bus.subscribe { queue.trySend(it) }
        scope.launch {
            for (event in queue) {
                runCatching { handle(event) }.onFailure { log.warn("Failed to deliver {}", event, it) }
            }
        }
    }

    private suspend fun handle(event: BusEvent) {
        when (event) {
            is ExerciseChangedEvent -> exerciseChanged(UUID.fromString(event.exerciseId))
            is DeviceChangedEvent -> deviceChanged(event)
            is DeviceCommandEvent -> deviceConnections[UUID.fromString(event.deviceId)]?.send(event.command)
            is OverridesChangedEvent -> overridesChanged(event)
            is DevicesCommandEvent -> event.deviceIds.forEach { deviceConnections[UUID.fromString(it)]?.send(event.command) }
            is TelemetryEvent -> toConsoles(event.exerciseId) { it.send(DeviceTelemetry(event.deviceId, event.sample)) }
            is StatusEvent -> event.exerciseId?.let { ex -> toConsoles(ex) { it.send(DeviceStatus(event.deviceId, event.online)) } }
            is DeviceEventEvent -> toConsoles(event.exerciseId) { it.send(DeviceEventNotice(event.deviceId, event.event)) }
        }
    }

    private suspend fun exerciseChanged(exerciseId: UUID) {
        val local = devices.listInExercise(exerciseId).mapNotNull { deviceConnections[it.id] }
        if (local.isNotEmpty()) {
            val snapshot = state.exerciseSnapshot(exerciseId)
            local.forEach { it.send(ExerciseUpdate(snapshot)) }
        }
        toConsoles(exerciseId.toString()) { it.send(ExerciseChanged(exerciseId.toString())) }
    }

    /** Only devices connected here are looked up, all with two queries. */
    private suspend fun overridesChanged(event: OverridesChangedEvent) {
        val local = event.deviceIds.map(UUID::fromString).filter { deviceConnections[it] != null }
        if (local.isEmpty()) return
        state.overridesFor(local).forEach { (id, update) -> deviceConnections[id]?.send(update) }
    }

    private suspend fun deviceChanged(event: DeviceChangedEvent) {
        val deviceId = UUID.fromString(event.deviceId)
        deviceConnections[deviceId]?.let { connection ->
            val device = devices.find(deviceId)
            if (device == null || device.revoked) {
                connection.close("Device revoked")
            } else {
                val exerciseChanged = connection.exerciseId != device.exerciseId
                connection.exerciseId = device.exerciseId
                if (exerciseChanged || event.previousExerciseId != event.exerciseId) {
                    connection.send(ExerciseUpdate(device.exerciseId?.let { state.exerciseSnapshot(it) }))
                }
                connection.send(state.overrides(device))
            }
        }
        setOfNotNull(event.previousExerciseId, event.exerciseId).forEach { ex ->
            toConsoles(ex) { it.send(ExerciseChanged(ex)) }
        }
    }

    private suspend fun toConsoles(exerciseId: String, send: suspend (ConsoleConnection) -> Unit) {
        consoleConnections.watching(UUID.fromString(exerciseId)).forEach { send(it) }
    }
}
