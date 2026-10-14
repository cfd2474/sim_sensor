package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.server.device.DeviceMessageHandler
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

const val DEVICE_SOCKET_PATH = "/ws/device"

fun Route.deviceSocketRoutes(handler: DeviceMessageHandler) {
    webSocket(DEVICE_SOCKET_PATH) {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            handler.handle(frame.readText())?.let { send(Frame.Text(ProtocolCodec.encode(it))) }
        }
    }
}
