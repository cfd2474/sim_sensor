package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.api.installErrorHandling
import com.taksolutions.simsensor.server.auth.AUTH_INSTRUCTOR
import com.taksolutions.simsensor.server.auth.installInstructorAuth
import com.taksolutions.simsensor.server.routes.authRoutes
import com.taksolutions.simsensor.server.routes.consoleRoutes
import com.taksolutions.simsensor.server.routes.deviceSocketRoutes
import com.taksolutions.simsensor.server.routes.exerciseRoutes
import com.taksolutions.simsensor.server.routes.healthRoutes
import com.taksolutions.simsensor.server.routes.instructorRoutes
import com.taksolutions.simsensor.server.routes.profileRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** Wires plugins and routes. All collaborators come from [services], so tests can replace them. */
fun Application.module(config: ServerConfig, services: AppServices) {
    // The server is only reachable through the Caddy proxy, so its X-Forwarded-* headers are trusted.
    install(XForwardedHeaders)
    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true })
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }
    installErrorHandling()
    installInstructorAuth(config, services.authService)

    routing {
        healthRoutes(config.version)
        deviceSocketRoutes(services.deviceMessageHandler)
        authRoutes(services.authService, services.instructorService)
        authenticate(AUTH_INSTRUCTOR) {
            instructorRoutes(services.instructorService)
            profileRoutes(services.profileService)
            exerciseRoutes(services.exerciseService)
        }
        config.consoleDir?.takeIf { it.isDirectory }?.let { consoleRoutes(it) }
    }
}
