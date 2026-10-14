package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.auth.AUTH_DEVICE
import com.taksolutions.simsensor.server.auth.AUTH_INSTRUCTOR
import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.auth.device
import com.taksolutions.simsensor.server.console.ConsoleSessionHandler
import com.taksolutions.simsensor.server.device.DeviceSessionHandler
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket

const val DEVICE_SOCKET_PATH = "/ws/device"
const val CONSOLE_SOCKET_PATH = "/ws/console"

/** Device socket: `Authorization: Bearer <device token>` on the upgrade request. */
fun Route.deviceSocketRoute(handler: DeviceSessionHandler) {
    authenticate(AUTH_DEVICE) {
        webSocket(DEVICE_SOCKET_PATH) {
            handler.run(call.device().deviceId, this)
        }
    }
}

/**
 * Console socket: the instructor session cookie. The cookie is SameSite=Strict, so another
 * site can't open this socket with the instructor's session.
 */
fun Route.consoleSocketRoute(handler: ConsoleSessionHandler) {
    authenticate(AUTH_INSTRUCTOR) {
        webSocket(CONSOLE_SOCKET_PATH) {
            handler.run(call.actor(), this)
        }
    }
}
