package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.device.DeviceMessageHandler
import com.taksolutions.simsensor.server.routes.consoleRoutes
import com.taksolutions.simsensor.server.routes.deviceSocketRoutes
import com.taksolutions.simsensor.server.routes.healthRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

/** Wires plugins and routes. Collaborators are passed in so tests can replace them. */
fun Application.module(
    config: ServerConfig,
    deviceMessageHandler: DeviceMessageHandler = DeviceMessageHandler(serverVersion = config.version),
) {
    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }

    routing {
        healthRoutes(config.version)
        deviceSocketRoutes(deviceMessageHandler)
        config.consoleDir?.takeIf { it.isDirectory }?.let { consoleRoutes(it) }
    }
}
