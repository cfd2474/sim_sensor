package com.taksolutions.simsensor.server.console

import com.taksolutions.simsensor.protocol.ConsoleCodec
import com.taksolutions.simsensor.protocol.ConsoleError
import com.taksolutions.simsensor.protocol.ConsoleServerMessage
import com.taksolutions.simsensor.protocol.DeviceLiveState
import com.taksolutions.simsensor.protocol.ExerciseDevices
import com.taksolutions.simsensor.protocol.UnwatchExercise
import com.taksolutions.simsensor.protocol.WatchExercise
import com.taksolutions.simsensor.server.api.ApiException
import com.taksolutions.simsensor.server.devices.DeviceService
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.realtime.ConsoleConnection
import com.taksolutions.simsensor.server.realtime.ConsoleConnections
import com.taksolutions.simsensor.server.telemetry.TelemetryRepository
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Runs one instructor console WebSocket: the console watches one exercise at a time and
 * receives that exercise's live device telemetry, status and events.
 */
class ConsoleSessionHandler(
    private val devices: DeviceService,
    private val telemetry: TelemetryRepository,
    private val connections: ConsoleConnections,
    private val clock: Clock,
) {
    suspend fun run(instructor: InstructorPrincipal, session: DefaultWebSocketServerSession) {
        val connection = WebSocketConsoleConnection(instructor, session)
        connections.register(connection)
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                when (val message = ConsoleCodec.decodeClientOrNull(frame.readText())) {
                    is WatchExercise -> watch(connection, message.exerciseId)
                    UnwatchExercise -> connection.watchedExerciseId = null
                    null -> connection.send(ConsoleError("bad_message", "Unrecognized message"))
                }
            }
        } finally {
            connections.unregister(connection)
        }
    }

    private suspend fun watch(connection: ConsoleConnection, rawId: String) {
        val exerciseId = runCatching { UUID.fromString(rawId) }.getOrNull()
        if (exerciseId == null) {
            connection.send(ConsoleError("bad_request", "Invalid exercise id"))
            return
        }
        val inExercise = try {
            devices.listInExercise(connection.instructor, exerciseId)
        } catch (e: ApiException) {
            connection.send(ConsoleError(e.code, e.message))
            return
        }
        // Start forwarding before taking the snapshot, so nothing between the two is missed.
        connection.watchedExerciseId = exerciseId
        val latest = telemetry.latest(inExercise.map { it.id })
        val onlineSince = clock.instant().minus(ONLINE_WINDOW)
        connection.send(
            ExerciseDevices(
                exerciseId = rawId,
                devices = inExercise.map { device ->
                    DeviceLiveState(
                        deviceId = device.id.toString(),
                        name = device.name,
                        online = device.lastSeenAt?.isAfter(onlineSince) == true,
                        appVersion = device.appVersion,
                        lastSample = latest[device.id],
                    )
                },
            ),
        )
    }

    private class WebSocketConsoleConnection(
        override val instructor: InstructorPrincipal,
        private val session: DefaultWebSocketServerSession,
    ) : ConsoleConnection {
        @Volatile
        override var watchedExerciseId: UUID? = null

        override suspend fun send(message: ConsoleServerMessage) {
            session.outgoing.send(Frame.Text(ConsoleCodec.encode(message)))
        }
    }

    private companion object {
        /** A device counts as online when it was heard from this recently (any server instance). */
        val ONLINE_WINDOW: Duration = Duration.ofSeconds(30)
    }
}
