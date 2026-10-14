package com.taksolutions.simsensor.server.device

import com.taksolutions.simsensor.protocol.DeviceEvent
import com.taksolutions.simsensor.protocol.ErrorMessage
import com.taksolutions.simsensor.protocol.Hello
import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.protocol.ProtocolMessage
import com.taksolutions.simsensor.protocol.Telemetry
import com.taksolutions.simsensor.protocol.Welcome
import com.taksolutions.simsensor.server.devices.DeviceRepository
import com.taksolutions.simsensor.server.devices.DeviceStateService
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.realtime.DeviceConnection
import com.taksolutions.simsensor.server.realtime.DeviceConnections
import com.taksolutions.simsensor.server.realtime.DeviceEventEvent
import com.taksolutions.simsensor.server.realtime.RealtimeBus
import com.taksolutions.simsensor.server.realtime.StatusEvent
import com.taksolutions.simsensor.server.realtime.TelemetryEvent
import com.taksolutions.simsensor.server.telemetry.TelemetryRow
import com.taksolutions.simsensor.server.telemetry.TelemetrySink
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Runs one device's WebSocket (docs/PROTOCOL.md): waits for `hello`, sends `welcome` with the
 * full state, then stores telemetry and events until the socket closes.
 */
class DeviceSessionHandler(
    private val devices: DeviceRepository,
    private val state: DeviceStateService,
    private val connections: DeviceConnections,
    private val telemetry: TelemetrySink,
    private val eventLog: EventLog,
    private val bus: RealtimeBus,
    private val serverVersion: String,
    private val clock: Clock,
) {
    suspend fun run(deviceId: UUID, session: DefaultWebSocketServerSession) {
        val hello = withTimeoutOrNull(HELLO_TIMEOUT_MS) { receiveHello(session) }
        if (hello == null) {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Expected hello"))
            return
        }
        devices.recordHello(deviceId, hello.appVersion, hello.model, clock.instant())

        val connection = WebSocketDeviceConnection(deviceId, null, session)
        connections.register(connection)?.close("Replaced by a newer connection")
        try {
            // Read the device only after registering, so a change made in between is either in
            // this read or delivered to the registered connection by the dispatcher.
            val device = devices.find(deviceId) ?: return
            connection.exerciseId = device.exerciseId
            connection.send(
                Welcome(
                    serverTimeMillis = clock.millis(),
                    serverVersion = serverVersion,
                    deviceId = deviceId.toString(),
                    deviceName = device.name,
                    exercise = device.exerciseId?.let { state.exerciseSnapshot(it) },
                    overrides = state.overrides(device),
                ),
            )
            eventLog.record(EventTypes.DEVICE_CONNECTED, exerciseId = device.exerciseId, deviceId = deviceId,
                data = mapOf("appVersion" to hello.appVersion))
            bus.publish(StatusEvent(device.exerciseId?.toString(), deviceId.toString(), online = true))

            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                when (val message = ProtocolCodec.decodeOrNull(frame.readText())) {
                    is Telemetry -> onTelemetry(connection, message)
                    is DeviceEvent -> onEvent(connection, message)
                    null -> connection.send(ErrorMessage("bad_message", "Unrecognized message"))
                    else -> connection.send(ErrorMessage("unexpected_message", "Not accepted from a device"))
                }
            }
        } finally {
            connections.unregister(connection)
            eventLog.record(EventTypes.DEVICE_DISCONNECTED, exerciseId = connection.exerciseId, deviceId = deviceId)
            bus.publish(StatusEvent(connection.exerciseId?.toString(), deviceId.toString(), online = false))
        }
    }

    private suspend fun receiveHello(session: DefaultWebSocketServerSession): Hello? {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            return ProtocolCodec.decodeOrNull(frame.readText()) as? Hello
        }
        return null
    }

    private fun onTelemetry(connection: DeviceConnection, message: Telemetry) {
        if (message.samples.isEmpty()) return
        val exerciseId = connection.exerciseId
        message.samples.take(MAX_SAMPLES_PER_MESSAGE).forEach {
            telemetry.enqueue(TelemetryRow(connection.deviceId, exerciseId, it))
        }
        // Consoles only need the newest position and readings.
        if (exerciseId != null) {
            bus.publish(TelemetryEvent(exerciseId.toString(), connection.deviceId.toString(), message.samples.maxBy { it.ts }))
        }
    }

    private suspend fun onEvent(connection: DeviceConnection, event: DeviceEvent) {
        val exerciseId = connection.exerciseId
        eventLog.record(
            EventTypes.DEVICE_EVENT, exerciseId = exerciseId, deviceId = connection.deviceId,
            data = mapOf("kind" to event.kind, "channel" to event.channelId, "detail" to event.detail),
            ts = Instant.ofEpochMilli(event.ts).takeIf { it.isBefore(clock.instant().plusSeconds(60)) },
        )
        if (exerciseId != null) {
            bus.publish(DeviceEventEvent(exerciseId.toString(), connection.deviceId.toString(), event))
        }
    }

    private class WebSocketDeviceConnection(
        override val deviceId: UUID,
        exerciseId: UUID?,
        private val session: DefaultWebSocketServerSession,
    ) : DeviceConnection {
        @Volatile
        override var exerciseId: UUID? = exerciseId

        override suspend fun send(message: ProtocolMessage) {
            session.outgoing.send(Frame.Text(ProtocolCodec.encode(message)))
        }

        override suspend fun close(reason: String) {
            session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        }
    }

    private companion object {
        const val HELLO_TIMEOUT_MS = 10_000L
        const val MAX_SAMPLES_PER_MESSAGE = 1_000
    }
}
