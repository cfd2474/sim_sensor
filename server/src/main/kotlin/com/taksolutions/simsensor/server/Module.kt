package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.api.installErrorHandling
import com.taksolutions.simsensor.server.auth.AUTH_INSTRUCTOR
import com.taksolutions.simsensor.server.auth.installAuth
import com.taksolutions.simsensor.server.routes.authRoutes
import com.taksolutions.simsensor.server.routes.consoleRoutes
import com.taksolutions.simsensor.server.routes.consoleSocketRoute
import com.taksolutions.simsensor.server.routes.deviceEnrollmentRoute
import com.taksolutions.simsensor.server.routes.deviceRoutes
import com.taksolutions.simsensor.server.routes.deviceSocketRoute
import com.taksolutions.simsensor.server.routes.exerciseControlRoutes
import com.taksolutions.simsensor.server.routes.exerciseRoutes
import com.taksolutions.simsensor.server.routes.healthRoutes
import com.taksolutions.simsensor.server.routes.instructorRoutes
import com.taksolutions.simsensor.server.routes.profileRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** Wires plugins and routes. All collaborators come from [services], so tests can replace them. */
fun Application.module(config: ServerConfig, services: AppServices) {
    services.start()
    monitor.subscribe(ApplicationStopped) { services.close() }

    // The server is only reachable through the Caddy proxy, so its X-Forwarded-* headers are trusted.
    install(XForwardedHeaders)
    install(CallLogging) {
        // Telemetry arrives over WebSockets; don't log health checks from the proxy every few seconds.
        filter { it.request.path() != "/health" }
    }
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true })
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 1L shl 20 // 1 MiB: room for a large offline telemetry batch
    }
    installErrorHandling()
    installAuth(config, services.authService, services::resolveDevice)

    routing {
        healthRoutes(config.version)
        authRoutes(services.authService, services.instructorService)
        deviceEnrollmentRoute(services.enrollmentService)
        deviceSocketRoute(services.deviceSessions)
        consoleSocketRoute(services.consoleSessions)
        authenticate(AUTH_INSTRUCTOR) {
            instructorRoutes(services.instructorService)
            profileRoutes(services.profileService)
            exerciseRoutes(services.exerciseService)
            exerciseControlRoutes(services.deviceService)
            deviceRoutes(services.enrollmentService, services.deviceService, services.deviceGroups)
        }
        config.consoleDir?.takeIf { it.isDirectory }?.let { consoleRoutes(it) }
    }
}
