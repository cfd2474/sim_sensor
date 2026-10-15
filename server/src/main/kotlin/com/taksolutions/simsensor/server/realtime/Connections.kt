package com.taksolutions.simsensor.server.realtime

import com.taksolutions.simsensor.protocol.ConsoleServerMessage
import com.taksolutions.simsensor.protocol.ProtocolMessage
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One device's WebSocket on this instance. */
interface DeviceConnection {
    val deviceId: UUID

    /** Exercise the device is in, kept current so telemetry can be routed without a lookup. */
    var exerciseId: UUID?

    suspend fun send(message: ProtocolMessage)
    suspend fun close(reason: String)
}

/** One console WebSocket on this instance. */
interface ConsoleConnection {
    val instructor: InstructorPrincipal

    var watchedExerciseId: UUID?

    suspend fun send(message: ConsoleServerMessage)
}

/** Devices connected to this instance. A device has at most one connection. */
class DeviceConnections {
    private val byDevice = ConcurrentHashMap<UUID, DeviceConnection>()

    /** Registers [connection] and returns the connection it replaced, if any. */
    fun register(connection: DeviceConnection): DeviceConnection? = byDevice.put(connection.deviceId, connection)

    /** Removes [connection] unless a newer one already replaced it. */
    fun unregister(connection: DeviceConnection) {
        byDevice.remove(connection.deviceId, connection)
    }

    operator fun get(deviceId: UUID): DeviceConnection? = byDevice[deviceId]

    fun all(): Collection<DeviceConnection> = byDevice.values

    val count: Int get() = byDevice.size
}

/** Consoles connected to this instance. */
class ConsoleConnections {
    private val connections = ConcurrentHashMap.newKeySet<ConsoleConnection>()

    fun register(connection: ConsoleConnection) {
        connections += connection
    }

    fun unregister(connection: ConsoleConnection) {
        connections -= connection
    }

    fun watching(exerciseId: UUID): List<ConsoleConnection> = connections.filter { it.watchedExerciseId == exerciseId }
}
