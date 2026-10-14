package com.taksolutions.simsensor.server.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val version: String)

fun Route.healthRoutes(version: String) {
    get("/health") {
        call.respond(HealthResponse(status = "ok", version = version))
    }
}
