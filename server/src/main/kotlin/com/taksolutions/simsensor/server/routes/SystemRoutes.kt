package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.system.UpdateService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class UpdateRequest(val tag: String)

/** Settings → Server update (admins). Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.systemRoutes(updates: UpdateService) {
    get("/api/system/update") { call.respond(updates.info(call.actor())) }
    post("/api/system/update") {
        updates.request(call.actor(), call.receive<UpdateRequest>().tag)
        call.respond(HttpStatusCode.Accepted, updates.info(call.actor()))
    }
}
