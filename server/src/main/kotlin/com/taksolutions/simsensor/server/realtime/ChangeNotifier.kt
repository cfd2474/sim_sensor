package com.taksolutions.simsensor.server.realtime

import com.taksolutions.simsensor.protocol.DeviceCommand
import java.util.UUID

/** How services announce changes that connected devices and consoles must receive. */
interface ChangeNotifier {
    fun exerciseChanged(exerciseId: UUID)
    fun deviceChanged(deviceId: UUID, previousExerciseId: UUID?, exerciseId: UUID?)
    fun command(deviceId: UUID, command: DeviceCommand)
}

class BusChangeNotifier(private val bus: RealtimeBus) : ChangeNotifier {
    override fun exerciseChanged(exerciseId: UUID) =
        bus.publish(ExerciseChangedEvent(exerciseId.toString()))

    override fun deviceChanged(deviceId: UUID, previousExerciseId: UUID?, exerciseId: UUID?) =
        bus.publish(DeviceChangedEvent(deviceId.toString(), previousExerciseId?.toString(), exerciseId?.toString()))

    override fun command(deviceId: UUID, command: DeviceCommand) =
        bus.publish(DeviceCommandEvent(deviceId.toString(), command))
}
