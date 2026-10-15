package com.taksolutions.simsensor.server.realtime

import com.taksolutions.simsensor.protocol.DeviceCommand
import java.util.UUID

/** How services announce changes that connected devices and consoles must receive. */
interface ChangeNotifier {
    fun exerciseChanged(exerciseId: UUID)
    fun deviceChanged(deviceId: UUID, previousExerciseId: UUID?, exerciseId: UUID?)
    fun command(deviceId: UUID, command: DeviceCommand)

    /** Overrides of [deviceIds] (all in [exerciseId]) changed. */
    fun overridesChanged(exerciseId: UUID, deviceIds: Collection<UUID>)

    /** Send [command] to [deviceIds] (all in [exerciseId]). */
    fun command(exerciseId: UUID, deviceIds: Collection<UUID>, command: DeviceCommand)

    fun settingsChanged()
}

class BusChangeNotifier(private val bus: RealtimeBus) : ChangeNotifier {
    override fun exerciseChanged(exerciseId: UUID) =
        bus.publish(ExerciseChangedEvent(exerciseId.toString()))

    override fun deviceChanged(deviceId: UUID, previousExerciseId: UUID?, exerciseId: UUID?) =
        bus.publish(DeviceChangedEvent(deviceId.toString(), previousExerciseId?.toString(), exerciseId?.toString()))

    override fun command(deviceId: UUID, command: DeviceCommand) =
        bus.publish(DeviceCommandEvent(deviceId.toString(), command))

    override fun overridesChanged(exerciseId: UUID, deviceIds: Collection<UUID>) {
        if (deviceIds.isNotEmpty()) bus.publish(OverridesChangedEvent(exerciseId.toString(), deviceIds.map(UUID::toString)))
    }

    override fun settingsChanged() = bus.publish(SettingsChangedEvent)

    override fun command(exerciseId: UUID, deviceIds: Collection<UUID>, command: DeviceCommand) {
        if (deviceIds.isNotEmpty()) bus.publish(DevicesCommandEvent(exerciseId.toString(), deviceIds.map(UUID::toString), command))
    }
}
